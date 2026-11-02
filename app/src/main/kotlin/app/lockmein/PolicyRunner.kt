package app.lockmein

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.BrowserApproval
import app.lockmein.core.BuildMode
import app.lockmein.core.FajrConfig
import app.lockmein.core.Inventory
import app.lockmein.core.PolicyPlanner
import app.lockmein.core.Schedule
import app.lockmein.core.ScheduleConfig
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class RunResult(val report: ApplyReport, val nextRunAt: Instant)

/**
 * One full pass: read the phone, work out the phase, apply the plan, and set the alarm for the next pass.
 * Runs on boot, on every phase change, and at least every [CHECK_EVERY] as a watchdog that repairs anything that was undone.
 */
class PolicyRunner(private val context: Context) {
    fun run(now: Instant = Instant.now()): RunResult {
        val zone = ZoneId.systemDefault()
        val schedule = Schedule(ScheduleConfig(), AstronomicalFajr(FajrConfig(), zone), zone)

        val snapshot = DeviceInventory(context).read()
        val state = LockState(context)
        state.noteInstalled(snapshot.installed)
        BrowserApproval(schedule).due(state.browserRequests, now).forEach(state::approveBrowser)

        val rules = OwnerRules.forDevice(snapshot, context.packageName)
        val mode = if (BuildConfig.DEBUG) BuildMode.TEST else BuildMode.SEALED
        val plan = PolicyPlanner(rules, BuildConfig.DNS_HOST).plan(
            schedule.phaseAt(now),
            mode,
            Inventory(snapshot.installed, state.approvedBrowsers, state.quarantined),
        )
        val report = PolicyApplier(context).apply(plan)

        val next = minOf(schedule.nextTransition(now), now.plus(CHECK_EVERY))
        scheduleAt(next)
        return RunResult(report, next)
    }

    private fun scheduleAt(at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReapplyReceiver::class.java).setAction(ReapplyReceiver.ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = at.toEpochMilli()
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    companion object {
        val CHECK_EVERY: Duration = Duration.ofMinutes(15)
    }
}
