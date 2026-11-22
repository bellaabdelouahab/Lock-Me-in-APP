package app.lockmein

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's link to the Lock Me In relay (server/relay.py), which the MCP talks to. The phone keeps one WebSocket open from the
 * filter watcher (a foreground service that always runs), answers each call with [RemoteCommands], and reconnects on its own.
 * The server's address and the phone's token are kept encrypted with a key-store key. Disconnecting is always allowed.
 */
object RemoteLink {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var appContext: Context? = null
    private var backoffMs = 5_000L

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastCallAt: Long = 0
        private set

    fun isPaired(context: Context) = Store(context).token() != null
    fun server(context: Context) = Store(context).url()

    /** Sends the pair code once; the server answers with this phone's own token. Blocks: call off the main thread. */
    fun pair(context: Context, url: String, code: String) {
        val base = normalize(url)
        val body = buildJsonObject { put("code", code.trim()) }.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$base/phone/pair").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            check(r.isSuccessful) { json?.get("error")?.jsonPrimitive?.content ?: "the server answered ${r.code}" }
            Store(context).save(base, requireNotNull(json?.get("token")?.jsonPrimitive?.content) { "no token in the answer" })
        }
        EventLog(context).add(app.lockmein.core.EventKind.REMOTE_CHANGE, "Connected to the relay at $base")
        stop()
        start(context)
    }

    fun disconnect(context: Context) {
        Store(context).clear()
        stop()
        EventLog(context).add(app.lockmein.core.EventKind.REMOTE_CHANGE, "Disconnected from the relay")
    }

    @Synchronized
    fun start(context: Context) {
        if (BuildConfig.PREVIEW) return
        appContext = context.applicationContext
        if (socket != null) return
        val store = Store(context)
        val url = store.url() ?: return
        val token = store.token() ?: return
        val ws = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/phone/ws"
        socket = client.newWebSocket(Request.Builder().url(ws).header("Authorization", "Bearer $token").build(), listener)
    }

    @Synchronized
    fun stop() {
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, null)
        socket = null
        connected = false
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            lastError = null
            backoffMs = 5_000L
            webSocket.send(buildJsonObject { put("type", "hello"); put("version", BuildConfig.VERSION_NAME); put("device", android.os.Build.MODEL) }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if (msg["type"]?.jsonPrimitive?.content != "call") return
            val id = msg["id"]?.jsonPrimitive?.content ?: return
            val tool = msg["tool"]?.jsonPrimitive?.content ?: return
            val args = msg["args"] as? JsonObject ?: JsonObject(emptyMap())
            val context = appContext ?: return
            worker.execute {
                lastCallAt = System.currentTimeMillis()
                val result = RemoteCommands(context).run(tool, args)
                val reply = buildJsonObject {
                    put("type", "result"); put("id", id); put("ok", result.isSuccess)
                    result.onSuccess { put("data", it) }.onFailure { put("error", it.message ?: it.javaClass.simpleName) }
                }
                webSocket.send(reply.toString())
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, if (code == 4401) "the server did not accept this phone (pair again)" else null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            dropped(webSocket, if (response?.code == 403 || response?.code == 401) "the server did not accept this phone (pair again)" else t.message)
    }

    private fun dropped(webSocket: WebSocket, error: String?) {
        synchronized(this) {
            if (socket !== webSocket) return
            socket = null
            connected = false
            lastError = error
        }
        val context = appContext ?: return
        main.postDelayed({ start(context) }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
    }

    fun normalize(url: String): String {
        val u = url.trim().trimEnd('/')
        require(u.isNotEmpty()) { "the server address is empty" }
        val full = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        require(full.startsWith("https://") || full.startsWith("http://10.") || full.startsWith("http://127.")) { "the address must be https" }
        return full
    }

    /** Server address and token, encrypted with a key-store key, in no-backup preferences. */
    private class Store(context: Context) {
        private val prefs = context.getSharedPreferences("remote_link", Context.MODE_PRIVATE)

        fun url(): String? = prefs.getString("url", null)
        fun token(): String? = runCatching {
            val data = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
            String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }.doFinal(data, 12, data.size - 12))
        }.getOrNull()

        fun save(url: String, token: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.iv + cipher.doFinal(token.toByteArray())
            prefs.edit().putString("url", url).putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }

        fun clear() = prefs.edit().clear().apply()

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
                )
            }.generateKey()
        }

        companion object { const val ALIAS = "lock_me_in_remote" }
    }
}
