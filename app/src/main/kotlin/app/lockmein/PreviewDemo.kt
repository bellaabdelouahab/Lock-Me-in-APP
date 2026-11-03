package app.lockmein

import android.content.Context
import app.lockmein.core.ChangeRequest
import app.lockmein.core.LockConfig
import app.lockmein.core.RequestKind
import java.time.Duration
import java.time.Instant

/** Preview build only: fills the app with sample settings so every screen can be looked at, and wipes them again. Nothing touches the phone's policy. */
object PreviewDemo {
    fun seed(context: Context) {
        val store = ConfigStore(context)
        val inventory = DeviceInventory(context)
        val apps = inventory.launchableApps().map { it.packageName }.filter { it != context.packageName }
        store.save(
            LockConfig(
                dnsHost = "demo.dns.nextdns.io",
                allowedBrowser = "com.android.chrome",
                nightAllowlist = apps.take(3).toSet(),
                alwaysBlocked = apps.drop(3).take(3).toSet(),
            ),
        )
        store.protectionActive = true
        val state = LockState(context)
        val now = Instant.now()
        state.requests = listOfNotNull(
            apps.getOrNull(7)?.let { ChangeRequest(RequestKind.NIGHT_ADD, it, now.minus(Duration.ofDays(3)).toEpochMilli()) },
            ChangeRequest(RequestKind.BROWSER, "com.brave.browser", now.minus(Duration.ofDays(1)).toEpochMilli()),
        )
    }

    fun reset(context: Context) {
        ConfigStore(context).protectionActive = false
        context.getSharedPreferences("lock_config", Context.MODE_PRIVATE).edit().clear().apply()
        LockState(context).clear()
        SecretStore(context).clear()
        EventLog(context).clear()
    }
}
