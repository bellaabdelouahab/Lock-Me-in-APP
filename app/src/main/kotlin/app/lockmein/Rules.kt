package app.lockmein

import app.lockmein.core.AppRules
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.LockConfig
import app.lockmein.core.Schedule
import java.time.ZoneId

/** Turns the person's settings and what is on the phone into the rules the planner uses. */
object Rules {
    fun forDevice(snapshot: DeviceSnapshot, appPackage: String, config: LockConfig) = AppRules(
        appPackage = appPackage,
        allowedBrowser = config.allowedBrowser,
        browsers = snapshot.browsers,
        nightAllowlist = config.nightAllowlist,
        protectedPackages = snapshot.protectedPackages,
        alwaysBlocked = config.alwaysBlocked,
    )

    fun schedule(config: LockConfig, zone: ZoneId = ZoneId.systemDefault()) =
        Schedule(config.scheduleConfig(), AstronomicalFajr(config.fajrConfig(), zone), zone)
}
