package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.Context
import app.lockmein.core.AppList
import app.lockmein.core.AppListEditor
import app.lockmein.core.BrowserApproval
import app.lockmein.core.ChangeGate
import app.lockmein.core.ChangeKind
import app.lockmein.core.CodeCheck
import app.lockmein.core.CodeChecker
import app.lockmein.core.ConfigRules
import app.lockmein.core.Decision
import app.lockmein.core.Emergency
import app.lockmein.core.EventKind
import app.lockmein.core.FriendCode
import app.lockmein.core.ListOp
import app.lockmein.core.LockConfig
import app.lockmein.core.Removal
import java.time.Duration
import java.time.Instant

/** What a change came to: done, or refused with a sentence the person can read. */
sealed interface Outcome {
    data class Done(val message: String = "Done.") : Outcome
    data class Refused(val reason: String) : Outcome
}

/**
 * Every change goes through here, so the rules are in one place: whatever opens the phone up needs the open window (or the friend's
 * emergency window) and a clock the network agrees with; whatever closes it more is always allowed. Several calls ask the network for
 * the time, so call them off the main thread.
 */
class ChangeManager(private val context: Context) {
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)
    private val secrets = SecretStore(context)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    private fun config(): LockConfig = checkNotNull(store.load()) { "the setup has not been finished" }

    private fun gate(config: LockConfig) = ChangeGate(Rules.schedule(config))

    private fun decide(kind: ChangeKind, config: LockConfig, now: Instant, networkTime: Instant?): Decision =
        gate(config).decide(kind, now, networkTime, state.emergencyUntil)

    private fun refused(why: String, what: String, now: Instant): Outcome.Refused {
        events.add(EventKind.DENIED, "$what: $why", now)
        return Outcome.Refused("Not now: $why.")
    }

    /** Add or remove an app on the night list or the always-blocked list. Before protection starts these are free. */
    fun editList(list: AppList, op: ListOp, packageName: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val config = config()
        val current = if (list == AppList.NIGHT_ALLOWED) config.nightAllowlist else config.alwaysBlocked
        val untouchable = DeviceInventory(context).read().protectedPackages + context.packageName
        val editor = AppListEditor(gate(config))
        val newList: Set<String> = if (store.protectionActive) {
            val result = editor.edit(list, op, current, packageName, untouchable, now, networkTime, state.emergencyUntil)
            val decision = result.decision
            if (decision is Decision.Denied) return refused(decision.why, "$op $packageName on $list", now)
            result.list
        } else {
            // Before protection starts everything is free, except the packages that keep the phone working.
            if (packageName in untouchable) return Outcome.Refused("This app keeps the phone working and cannot be listed.")
            if (op == ListOp.ADD) current + packageName else current - packageName
        }
        val updated = if (list == AppList.NIGHT_ALLOWED) config.copy(nightAllowlist = newList) else config.copy(alwaysBlocked = newList)
        val problem = ConfigRules.problems(updated).firstOrNull()
        if (problem != null) return Outcome.Refused(problem)
        store.save(updated)
        events.add(if (editor.kindOf(list, op) == ChangeKind.LOOSEN) EventKind.LOOSENED else EventKind.TIGHTENED, "$op $packageName on $list", now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done()
    }

    /** Saves changed settings (DNS host, browser, rhythm, location). Every change counts as opening up, so after protection starts it needs the window. */
    fun saveSettings(updated: LockConfig, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val problem = ConfigRules.problems(updated).firstOrNull()
        if (problem != null) return Outcome.Refused(problem)
        if (store.protectionActive) {
            val old = config()
            if (old == updated) return Outcome.Done("Nothing changed.")
            val decision = decide(ChangeKind.LOOSEN, old, now, networkTime)
            if (decision is Decision.Denied) return refused(decision.why, "settings", now)
            events.add(EventKind.LOOSENED, "settings changed", now)
        }
        store.save(updated)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Saved.")
    }

    /** The open window: an app held back after it was installed is let through. */
    fun releaseApp(packageName: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val decision = decide(ChangeKind.LOOSEN, config(), now, networkTime)
        if (decision is Decision.Denied) return refused(decision.why, "release $packageName", now)
        state.release(packageName)
        events.add(EventKind.APP_RELEASED, packageName, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done()
    }

    /** Ask for a closed browser. The wait starts now and a second request does not restart it. */
    fun requestBrowser(packageName: String, now: Instant = Instant.now()): Outcome {
        val config = config()
        val approval = BrowserApproval(Rules.schedule(config), Duration.ofMinutes(config.browserWaitMinutes.toLong()))
        state.browserRequests = approval.requested(state.browserRequests, packageName, now)
        events.add(EventKind.BROWSER_REQUESTED, packageName, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Requested.")
    }

    /** The friend's code opens a 60-minute emergency window. */
    fun redeemEmergency(code: String, now: Instant = Instant.now()): Outcome {
        val secret = secrets.load() ?: return Outcome.Refused("No friend code is set up.")
        val redemption = Emergency(secret).redeem(code, now, state.lastCodeCounter)
        if (redemption == null) {
            events.add(EventKind.EMERGENCY_REFUSED, "wrong or used code", now)
            return Outcome.Refused("That code is wrong or was already used.")
        }
        state.emergencyUntil = redemption.until
        state.lastCodeCounter = redemption.counter
        events.add(EventKind.EMERGENCY_USED, "until ${redemption.until}", now)
        return Outcome.Done("Emergency window open for 60 minutes.")
    }

    /**
     * Checks a code while the friend is on the phone during setup, to prove the secret reached them. Does not use up the code for real
     * unlocks: the counter is remembered so the same code cannot open an emergency window a moment later.
     */
    fun verifyEnrolment(code: String, now: Instant = Instant.now()): Boolean {
        val secret = secrets.load() ?: return false
        val check = CodeChecker(secret).check(code, now, state.lastCodeCounter)
        if (check is CodeCheck.Accepted) state.lastCodeCounter = check.counter
        return check is CodeCheck.Accepted
    }

    /** Makes the friend's secret (once, in setup) and returns it for the owner to send. Replaces any earlier one. */
    fun newFriendSecret(): ByteArray = FriendCode.newSecret().also {
        secrets.save(it)
        state.lastCodeCounter = null
    }

    val hasFriendSecret: Boolean get() = secrets.hasSecret

    /** Turns the protection on. Needs the device-owner role, valid settings and a friend code. */
    fun startProtection(now: Instant = Instant.now()): Outcome {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return Outcome.Refused("This app is not the device owner yet. Finish the first setup step.")
        val config = store.load() ?: return Outcome.Refused("Finish the setup first.")
        ConfigRules.problems(config).firstOrNull()?.let { return Outcome.Refused(it) }
        if (!secrets.hasSecret) return Outcome.Refused("Set up the friend's code first.")
        store.protectionActive = true
        events.add(EventKind.PROTECTION_STARTED, "", now)
        val result = PolicyRunner(context).run(config, now)
        return if (result.report.ok) Outcome.Done("Protection is on.") else Outcome.Done("Protection is on, but not everything could be applied: ${result.report.failed.joinToString("; ")}")
    }

    /** Takes the protection off: needs the open window (or an emergency) and the friend's code. */
    fun removeProtection(code: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val config = config()
        val secret = secrets.load() ?: return Outcome.Refused("No friend code is set up.")
        events.add(EventKind.REMOVAL_REQUESTED, "", now)
        val gate = decide(ChangeKind.LOOSEN, config, now, networkTime)
        val check = CodeChecker(secret).check(code, now, state.lastCodeCounter)
        val decision = Removal.decide(gate, check)
        if (decision is Decision.Denied) return refused(decision.why, "remove protection", now)
        if (check is CodeCheck.Accepted) state.lastCodeCounter = check.counter

        val everything = DeviceInventory(context).read().installed
        PolicyApplier(context).release(everything)
        PolicyRunner(context).cancelAlarm()
        store.protectionActive = false
        events.add(EventKind.PROTECTION_REMOVED, "", now)
        state.emergencyUntil = null
        dpm.clearDeviceOwnerApp(context.packageName)
        return Outcome.Done("Protection removed. This app is no longer the device owner and can be uninstalled.")
    }
}
