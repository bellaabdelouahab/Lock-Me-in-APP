package app.lockmein

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.lockmein.core.DeltaPatch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The newest build published on GitHub (version.json next to the APKs of the latest release). */
data class Release(val code: Int, val name: String, val notes: String, val patch: Patch? = null)

/** A small update (FEATURES 3.19): the file that turns this exact installed version into the new one. */
data class Patch(val file: String, val size: Long)

/**
 * Updates from the repo's latest GitHub release, like the Money app: check, download, install. As device owner the app installs
 * silently; otherwise Android shows its own install prompt. Each kind of build updates to the same kind (preview, test, sealed).
 * Every APK is signed with the same release key in CI, which is what lets one install over the other.
 * Blocks (network), so call it off the main thread.
 */
object Updater {
    private const val BASE = "https://github.com/bellaabdelouahab/Lock-Me-in-APP/releases/latest/download"
    val kind = when {
        BuildConfig.PREVIEW -> "preview"
        BuildConfig.TEST_BUILD -> "test-build"
        else -> "sealed"
    }

    /** The latest release, or null when there is none or the network is down. */
    fun latest(): Release? = runCatching {
        val json = JSONObject(open("$BASE/version.json").inputStream.bufferedReader().use { it.readText() })
        val patch = json.optJSONObject("patches")?.optJSONArray(kind)?.let { list ->
            (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optInt("from") == BuildConfig.VERSION_CODE }
        }?.let { Patch(it.getString("file"), it.getLong("size")) }
        Release(json.getInt("versionCode"), json.getString("versionName"), json.optString("notes"), patch)
    }.getOrNull()

    fun isNewer(release: Release?) = release != null && release.code > BuildConfig.VERSION_CODE

    /**
     * Installs the latest release. Small update first (FEATURES 3.19): when the release has a patch for this exact version, only
     * the patch is downloaded and the new APK is rebuilt from the installed one, checked byte for byte against the new APK's
     * fingerprint before it goes to Android. Anything wrong on that path (no patch, a different installed APK, a bad download)
     * falls back to downloading the whole APK, as before. Null when handed to Android, or why it failed.
     */
    fun install(context: Context, progress: (Float) -> Unit): String? {
        saveResult(context, "")
        val patch = latest()?.patch
        if (patch != null) {
            val patched = runCatching { session(context) { installer, id -> writePatched(context, installer, id, patch, progress) } }
            if (patched.isSuccess) return null
        }
        return runCatching { session(context) { installer, id -> writeFull(installer, id, progress) }; null }
            .getOrElse { "The update could not be downloaded: ${it.message ?: "no connection"}" }
    }

    /** One install session: written by [write], then committed; a failure on the way never leaves a half-written one. */
    private fun session(context: Context, write: (PackageInstaller, Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        try {
            write(installer, id)
            installer.openSession(id).use { session ->
                val done = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(done.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun writeFull(installer: PackageInstaller, id: Int, progress: (Float) -> Unit) {
        installer.openSession(id).use { session ->
            val connection = open("$BASE/lock-me-in-$kind.apk")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                session.openWrite("update.apk", 0, total ?: -1).use { out ->
                    copy(input, out) { done -> if (total != null) progress(done.toFloat() / total) }
                    session.fsync(out)
                }
            }
        }
    }

    /** Downloads the patch (a few hundred KB) and writes the rebuilt APK; throws, so the session is dropped, if it is not exact. */
    private fun writePatched(context: Context, installer: PackageInstaller, id: Int, patch: Patch, progress: (Float) -> Unit) {
        val file = File(context.cacheDir, "update.patch")
        try {
            open("$BASE/${patch.file}").inputStream.use { input -> file.outputStream().use { out -> copy(input, out) { progress(0.2f * it / patch.size.coerceAtLeast(1)) } } }
            val head = DeltaPatch.header(file)
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, head.newSize).use { out ->
                    DeltaPatch.apply(File(context.applicationInfo.sourceDir), file, out) { progress(0.2f + 0.8f * it) }
                    session.fsync(out)
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, done: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            done(total)
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        if (responseCode !in 200..299) error("GitHub answered $responseCode")
    }

    fun lastResult(context: Context): String? = context.getSharedPreferences("lock_ui", Context.MODE_PRIVATE).getString(RESULT, null)?.ifBlank { null }
    internal fun saveResult(context: Context, text: String) = context.getSharedPreferences("lock_ui", Context.MODE_PRIVATE).edit().putString(RESULT, text).apply()

    private const val RESULT = "update_result"
}

/** Android's answer to an update: shows its prompt when it needs the person's yes, and keeps a failure to show in Settings. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.saveResult(context, "Updated.")
            else -> Updater.saveResult(context, "The update did not install (code $status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"}. Tap Update now to try again.")
        }
    }
}
