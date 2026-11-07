package app.lockmein

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.lockmein.core.AppList
import app.lockmein.core.BuildMode
import app.lockmein.core.EventKind
import app.lockmein.core.Inventory
import app.lockmein.core.LockConfig
import app.lockmein.core.PolicyPlan
import app.lockmein.core.PolicyPlanner
import app.lockmein.core.ScheduledRemovals
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
    fun runIfActive(now: Instant = TrustedClock(context).now()): RunResult? {
        val store = ConfigStore(context)
        val config = store.load()
        if (!store.protectionActive || config == null) return null
        return run(config, now)
    }

    fun run(config: LockConfig, now: Instant = TrustedClock(context).now()): RunResult {
        // The preview build only shows the screens: it is never the device owner and applies nothing.
        if (BuildConfig.PREVIEW) return RunResult(ApplyReport(emptyList()), now.plus(CHECK_EVERY))

        val state = LockState(context)
        var currentConfig = config

        // Apply any scheduled removals that are now due
        val dueRemovals = ScheduledRemovals.due(state.scheduledRemovals, now)
        if (dueRemovals.isNotEmpty()) {
            var updatedConfig = currentConfig
            for (removal in dueRemovals) {
                updatedConfig = when (removal.list) {
                    AppList.NIGHT_CLOSED -> updatedConfig.copy(nightClosed = updatedConfig.nightClosed - removal.packageName)
                    AppList.ALWAYS_BLOCKED -> updatedConfig.copy(alwaysBlocked = updatedConfig.alwaysBlocked - removal.packageName)
                    AppList.WINDOW_APPS -> updatedConfig.copy(windowApps = updatedConfig.windowApps - removal.packageName)
                }
                events.add(EventKind.LOOSENED, "Scheduled removal: ${removal.packageName} from ${removal.list}", now)
            }
            state.scheduledRemovals = ScheduledRemovals.remaining(state.scheduledRemovals, now)
            ConfigStore(context).save(updatedConfig)
            currentConfig = updatedConfig
        }

        val schedule = Rules.schedule(currentConfig)
        state.noteInstalled(DeviceInventory(context).read().installed).forEach { events.add(EventKind.APP_QUARANTINED, it, now) }

        val plan = planFor(currentConfig, now)

        // Before suspending window apps outside the daily window, send the user home if one is in foreground
        val willSuspendWindowApps = plan.suspended.intersect(currentConfig.windowApps)
        if (willSuspendWindowApps.isNotEmpty()) {
            sendHomeIfForeground(willSuspendWindowApps)
        }

        val report = PolicyApplier(context).apply(plan)
        state.noteUnsuspendable(report.unsuspendable)
        events.add(if (report.ok) EventKind.PASS_OK else EventKind.PASS_FAILED, report.failed.joinToString("; "), now)

        val candidates = buildList {
            schedule.nextTransition(now)?.let(::add)
            add(now.plus(CHECK_EVERY))
            state.sessionUntil?.takeIf { it.isAfter(now) }?.let(::add)
            addAll(state.scheduledRemovals.map { it.dueAt }.filter { it.isAfter(now) })
        }
        val next = candidates.min()
        scheduleAt(next, now)
        return RunResult(report, next)
    }

    private fun sendHomeIfForeground(packages: Set<String>) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val isForeground = runCatching {
            val procs = am.runningAppProcesses ?: emptyList()
            procs.any { proc ->
                proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    proc.pkgList?.any { it in packages } == true
            }
        }.getOrDefault(false)

        if (isForeground) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(home)
        }
    }

    /** The policy that should be in force right now. The health check compares the phone against it. */
    fun planFor(config: LockConfig, now: Instant = TrustedClock(context).now()): PolicyPlan {
        val snapshot = DeviceInventory(context).read()
        val state = LockState(context)
        val mode = if (BuildConfig.TEST_BUILD) BuildMode.TEST else BuildMode.SEALED
        val schedule = Rules.schedule(config)
        val dailyWindowOpen = schedule.inDailyWindow(now)
        return PolicyPlanner(Rules.forDevice(snapshot, context.packageName, config, state.unsuspendable), config.dnsHost).plan(
            schedule.phaseAt(now),
            mode,
            Inventory(snapshot.installed, state.approvedBrowsers, state.quarantined, emptySet()),
            dailyWindowOpen = dailyWindowOpen,
        )
    }

    /** Set on the time-since-boot clock, which can't be changed by hand, as a delay from the trusted [now]. */
    private fun scheduleAt(at: Instant, now: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReapplyReceiver::class.java).setAction(ReapplyReceiver.ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = android.os.SystemClock.elapsedRealtime() + Duration.between(now, at).toMillis().coerceAtLeast(1_000)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, millis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, millis, intent)
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
