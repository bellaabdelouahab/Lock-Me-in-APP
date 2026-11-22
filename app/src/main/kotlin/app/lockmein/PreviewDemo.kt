package app.lockmein

import android.content.Context
import app.lockmein.core.ChangeRequest
import app.lockmein.core.LockConfig
import app.lockmein.core.RequestKind
import app.lockmein.core.RequestResolution
import app.lockmein.core.ResolvedRequest
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
                nightClosed = apps.take(3).toSet(),
                alwaysBlocked = apps.drop(3).take(3).toSet() + LockConfig.ALWAYS_BLOCKED_DEFAULT,
                windowApps = LockConfig.WINDOW_APPS_DEFAULT,
            ),
        )
        store.protectionActive = true
        store.setupComplete = true
        val state = LockState(context)
        val now = Instant.now()
        state.requests = listOfNotNull(
            apps.getOrNull(3)?.let { ChangeRequest(RequestKind.BLOCKED_REMOVE, it, now.minus(Duration.ofDays(3)).toEpochMilli(), count = 4) },
            apps.getOrNull(7)?.let { ChangeRequest(RequestKind.NIGHT_REMOVE, it, now.minus(Duration.ofDays(2)).toEpochMilli(), count = 2) },
            ChangeRequest(RequestKind.BROWSER, "com.brave.browser", now.minus(Duration.ofDays(1)).toEpochMilli(), count = 1),
        )
        // A morning of phone use for the Phone use page.
        val use = PhoneUseLog(context)
        val day = java.time.LocalDate.now().atTime(7, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().let { minOf(it, now.minus(Duration.ofHours(3))) }
        val names = (apps + listOf("com.whatsapp", "com.google.android.youtube", "com.instagram.android")).distinct()
        var at = day
        for ((i, minutes) in listOf(12, 4, 25, 7, 18, 3).withIndex()) {
            use.add(app.lockmein.core.UseKind.SCREEN_ON, at = at)
            use.add(app.lockmein.core.UseKind.UNLOCK, at = at)
            use.add(app.lockmein.core.UseKind.APP, names[i % minOf(3, names.size)], at)
            use.add(app.lockmein.core.UseKind.NOTIFICATION, names[(i + 1) % minOf(3, names.size)], at.plusSeconds(30))
            at = at.plus(Duration.ofMinutes(minutes.toLong()))
            use.add(app.lockmein.core.UseKind.SCREEN_OFF, at = at)
            at = at.plus(Duration.ofMinutes(20))
        }
        // Tries on closed apps, and a few blocks by the screen filter (two marked "Wrong block?") for the Screen filter page.
        use.add(app.lockmein.core.UseKind.BLOCKED, "com.instagram.android", day.plus(Duration.ofMinutes(15)))
        use.add(app.lockmein.core.UseKind.BLOCKED, "com.instagram.android", day.plus(Duration.ofMinutes(95)))
        use.add(app.lockmein.core.UseKind.BLOCKED, "", day.plus(Duration.ofMinutes(130)))
        val feedback = FeedbackStore(context)
        for (i in 0 until 4) {
            val r = app.lockmein.core.BlockRecord(
                "demo-$i", now.minus(Duration.ofHours(2L + i * 5)).toEpochMilli(), if (i % 2 == 0) "com.brave.browser" else "com.android.chrome",
                app.lockmein.core.StoredScores(porn = 0.62f, sexy = 0.3f, drawings = 0.7f, neutral = 0.1f, animeSuggestive = 0.7f, animeExplicit = 0.58f, seductive = 0.5f),
                agreed = listOf(app.lockmein.core.AgreedFrame(1500, false), app.lockmein.core.AgreedFrame(1000, true), app.lockmein.core.AgreedFrame(500, true), app.lockmein.core.AgreedFrame(0, true)),
            )
            feedback.add(r)
            if (i < 2) feedback.saveForReview(r.id, null)
        }
        state.requestHistory = listOfNotNull(
            apps.getOrNull(5)?.let { ResolvedRequest(RequestKind.BLOCKED_REMOVE, it, 5, now.minus(Duration.ofDays(10)).toEpochMilli(), now.minus(Duration.ofDays(3)).toEpochMilli(), RequestResolution.DISMISSED) },
            apps.getOrNull(6)?.let { ResolvedRequest(RequestKind.RELEASE_APP, it, 1, now.minus(Duration.ofDays(12)).toEpochMilli(), now.minus(Duration.ofDays(5)).toEpochMilli(), RequestResolution.APPROVED) },
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
