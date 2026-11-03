package app.lockmein

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.lockmein.core.BuildMode
import app.lockmein.core.EventKind
import app.lockmein.core.Inventory
import app.lockmein.core.LockConfig
import app.lockmein.core.PolicyPlanner
import java.time.Duration
import java.time.Instant

data class RunResult(val report: ApplyReport, val nextRunAt: Instant)

/**
 * One full pass: read the phone, work out the phase, apply the plan, and set the alarm for the next pass.
 * Runs on boot, on every phase change, and at least every [CHECK_EVERY] as a watchdog that repairs anything that was undone.
 */
class PolicyRunner(private val context: Context) {
    private val events = EventLog(context)

    /** The pass the alarms and the boot receiver call: does nothing unless protection was started. */
    fun runIfActive(now: Instant = Instant.now()): RunResult? {
        val store = ConfigStore(context)
        val config = store.load()
        if (!store.protectionActive || config == null) return null
        return run(config, now)
    }

    fun run(config: LockConfig, now: Instant = Instant.now()): RunResult {
        val schedule = Rules.schedule(config)
        val snapshot = DeviceInventory(context).read()
        val state = LockState(context)

        state.noteInstalled(snapshot.installed).forEach { events.add(EventKind.APP_QUARANTINED, it, now) }
        val unblocks = state.unblocks(now)

        val mode = if (BuildConfig.TEST_BUILD) BuildMode.TEST else BuildMode.SEALED
        val plan = PolicyPlanner(Rules.forDevice(snapshot, context.packageName, config), config.dnsHost).plan(
            schedule.phaseAt(now),
            mode,
            Inventory(snapshot.installed, state.approvedBrowsers, state.quarantined, unblocks.keys),
        )
        val report = PolicyApplier(context).apply(plan)
        events.add(if (report.ok) EventKind.PASS_OK else EventKind.PASS_FAILED, report.failed.joinToString("; "), now)

        val next = listOfNotNull(schedule.nextTransition(now), now.plus(CHECK_EVERY), unblocks.values.minOrNull()).min()
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

    /** Stops the alarm chain, when the protection is removed. */
    fun cancelAlarm() {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ReapplyReceiver::class.java).setAction(ReapplyReceiver.ACTION_RUN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    companion object {
        val CHECK_EVERY: Duration = Duration.ofMinutes(15)
    }
}
