package app.lockmein

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The newest build published on GitHub (version.json next to the APKs of the latest release). */
data class Release(val code: Int, val name: String, val notes: String)

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
        Release(json.getInt("versionCode"), json.getString("versionName"), json.optString("notes"))
    }.getOrNull()

    fun isNewer(release: Release?) = release != null && release.code > BuildConfig.VERSION_CODE

    /** Downloads the APK into an install session and commits it. Null when handed to Android, or why it failed. */
    fun install(context: Context, progress: (Float) -> Unit): String? = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (BuildConfig.TEST_BUILD) allowTestPackages(this)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val connection = open("$BASE/lock-me-in-$kind.apk")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                session.openWrite("update.apk", 0, total ?: -1).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total != null) progress(done.toFloat() / total)
                    }
                    session.fsync(out)
                }
            }
            val done = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(done.intentSender)
        }
        null
    }.getOrElse { "The update could not be downloaded: ${it.message ?: "no connection"}" }

    /** The test build is a test-only package, which Android installs only with this (hidden) flag. Without it the install says so. */
    private fun allowTestPackages(params: PackageInstaller.SessionParams) {
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            field.isAccessible = true
            field.setInt(params, field.getInt(params) or INSTALL_ALLOW_TEST)
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        if (responseCode !in 200..299) error("GitHub answered $responseCode")
    }

    fun lastResult(context: Context): String? = context.getSharedPreferences("lock_ui", Context.MODE_PRIVATE).getString(RESULT, null)
    internal fun saveResult(context: Context, text: String) = context.getSharedPreferences("lock_ui", Context.MODE_PRIVATE).edit().putString(RESULT, text).apply()

    private const val INSTALL_ALLOW_TEST = 0x00000004
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
            else -> Updater.saveResult(context, "The update was refused (code $status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"}")
        }
    }
}
