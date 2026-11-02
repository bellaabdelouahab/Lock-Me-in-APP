package app.lockmein

import android.content.Context
import app.lockmein.core.AppList
import app.lockmein.core.AppListEditor
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.ChangeGate
import app.lockmein.core.Decision
import app.lockmein.core.FajrConfig
import app.lockmein.core.ListOp
import app.lockmein.core.Schedule
import app.lockmein.core.ScheduleConfig
import java.time.Instant
import java.time.ZoneId

/** Adds and removes apps on the night list and the blocked list, through the change gate, then re-applies the policy. */
class AppListManager(private val context: Context) {
    private val state = LockState(context)

    fun current(list: AppList): Set<String> = if (list == AppList.NIGHT_ALLOWED) state.nightAllowlist else state.alwaysBlocked

    /** Blocks (asks the network for the time and re-applies the policy): call off the main thread. */
    fun edit(list: AppList, op: ListOp, packageName: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Decision {
        val zone = ZoneId.systemDefault()
        val gate = ChangeGate(Schedule(ScheduleConfig(), AstronomicalFajr(FajrConfig(), zone), zone))
        val snapshot = DeviceInventory(context).read()
        val result = AppListEditor(gate).edit(
            list = list,
            op = op,
            current = current(list),
            packageName = packageName,
            untouchable = snapshot.protectedPackages + context.packageName,
            now = now,
            networkTime = networkTime,
            emergencyUntil = state.emergencyUntil,
        )
        if (result.decision is Decision.Allowed && result.list != current(list)) {
            if (list == AppList.NIGHT_ALLOWED) state.nightAllowlist = result.list else state.alwaysBlocked = result.list
            PolicyRunner(context).run(now)
        }
        return result.decision
    }
}
