package app.lockmein

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.lockmein.core.BlockRecord
import app.lockmein.core.EventKind
import app.lockmein.core.Feedback
import app.lockmein.core.FilterJudge
import app.lockmein.core.Sealed
import app.lockmein.core.Training
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Sends the screen filter's blocks to the training server once a day (FEATURES 3.20): from 09:00, on an unmetered network, each
 * block's record and label, with its screenshot sealed for the trainer ([Sealed]). A block's screenshot is deleted from the phone
 * once the server has it. Then asks for the newest judge the trainer passed and files it through the change gate (a loosening:
 * it waits like any other). Only release builds of the real app send: never the preview, never a debug or emulator build.
 * Called from the filter watcher's tick, on its own thread; it paces itself.
 */
object TrainingUpload {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastTry = 0L

    fun tick(context: Context) {
        if (BuildConfig.PREVIEW || BuildConfig.DEBUG) return
        if (!ConfigStore(context).protectionActive) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTry < Training.TRY_EVERY_MS) return
        lastTry = nowMs
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = prefs.getString("server", null) ?: Training.DEFAULT_SERVER
        try {
            // Registered as soon as possible, so this phone is the one the server takes uploads from.
            val token = prefs.getString("token", null) ?: register(server)?.also { prefs.edit().putString("token", it).apply() } ?: return
            val store = FeedbackStore(context)
            val zone = ZoneId.systemDefault()
            val pending = Training.pending(store.records())
            val doneOn = prefs.getString("done_on", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (!Training.due(TrustedClock(context).now().atZone(zone), doneOn, unmetered(context), pending.size)) return
            val key = get(server, "/train/key", token).getValue("public_key").jsonPrimitive.content
            var sent = 0
            for (record in pending.take(Training.MAX_PER_RUN)) {
                val image = store.image(record)?.let { android.util.Base64.encodeToString(Sealed.seal(it, key), android.util.Base64.NO_WRAP) }
                val body = buildJsonObject {
                    put("record", json.parseToJsonElement(json.encodeToString(BlockRecord.serializer(), record.copy(hasImage = image != null))))
                    put("label", Training.label(record))
                    put("image", image?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("app_version", BuildConfig.VERSION_NAME)
                }
                post(server, "/train/upload", token, body.toString())
                store.uploaded(record.id, System.currentTimeMillis())
                sent++
            }
            if (sent > 0) EventLog(context).add(EventKind.TRAINING_SENT, "$sent blocks")
            if (sent == pending.size) prefs.edit().putString("done_on", LocalDate.now(zone).toString()).apply()
            offer(context, server, token)
        } catch (e: Exception) {
            Log.w("TrainingUpload", "not sent: ${e.message}")
        }
    }

    /** The newest judge the trainer passed: filed once, through the change gate (a loosening, so it waits like any other). */
    private fun offer(context: Context, server: String, token: String) {
        val judgeJson = get(server, "/train/offer", token)["judge"]?.takeIf { it is JsonObject } ?: return
        val judge = json.decodeFromString(FilterJudge.serializer(), judgeJson.toString())
        judge.problem()?.let { return }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = ChangeManager(context)
        if (prefs.getString("offered", null) == judge.version || manager.config().filterJudge?.version == judge.version) return
        val outcome = manager.saveSettings(manager.config().copy(filterJudge = judge))
        prefs.edit().putString("offered", judge.version).apply()
        EventLog(context).add(EventKind.TRAINING_OFFER, "${judge.version}: $outcome")
    }

    private fun register(server: String): String? {
        val request = Request.Builder().url("$server/train/register").post("{}".toRequestBody(JSON)).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) { Log.w("TrainingUpload", "register: ${r.code}"); return null }
            return Json.parseToJsonElement(r.body!!.string()).jsonObject.getValue("token").jsonPrimitive.content
        }
    }

    private fun get(server: String, path: String, token: String): JsonObject =
        client.newCall(Request.Builder().url(server + path).header("Authorization", "Bearer $token").build()).execute().use { r ->
            check(r.isSuccessful) { "$path answered ${r.code}" }
            Json.parseToJsonElement(r.body!!.string()).jsonObject
        }

    private fun post(server: String, path: String, token: String, body: String) {
        client.newCall(Request.Builder().url(server + path).header("Authorization", "Bearer $token").post(body.toRequestBody(JSON)).build()).execute().use { r ->
            check(r.isSuccessful) { "$path answered ${r.code}" }
        }
    }

    private fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val JSON = "application/json".toMediaType()
    private const val PREFS = "lock_training"
}
