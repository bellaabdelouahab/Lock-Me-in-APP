package app.lockmein

import android.app.admin.DevicePolicyManager
import android.content.Context
import app.lockmein.core.AppList
import app.lockmein.core.AppListEditor
import app.lockmein.core.ChangeGate
import app.lockmein.core.ChangeKind
import app.lockmein.core.ChangeRequest
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
import app.lockmein.core.RequestKind
import app.lockmein.core.Requests
import java.time.Duration
import java.time.Instant

/** What a change came to: done, or refused with a sentence the person can read. */
sealed interface Outcome {
    data class Done(val message: String = "Done.") : Outcome
    data class Refused(val reason: String) : Outcome
}

/**
 * Every change goes through here, so the rules are in one place. Whatever closes the phone more applies at once. Whatever opens it up
 * becomes a request that waits (seven days by default) and is carried out only in the open window, with a clock the network agrees
 * with. The friend's code never changes a setting: it unblocks one named app for an hour. Several calls ask the network for the time,
 * so call them off the main thread.
 */
class ChangeManager(private val context: Context) {
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)
    private val secrets = SecretStore(context)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val editor = AppListEditor()

    private fun config(): LockConfig = checkNotNull(store.load()) { "the setup has not been finished" }

    private fun gate(config: LockConfig) = ChangeGate(Rules.schedule(config), wait = Duration.ofDays(config.loosenWaitDays.toLong()))

    private fun refused(why: String, what: String, now: Instant): Outcome.Refused {
        events.add(EventKind.DENIED, "$what: $why", now)
        return Outcome.Refused("Not now: $why.")
    }

    /** Puts something that opens the phone up in the queue. Asking again never restarts the wait. */
    private fun ask(kind: RequestKind, target: String, now: Instant, settings: LockConfig? = null): Outcome {
        val config = config()
        val request = ChangeRequest(kind, target, now.toEpochMilli(), settings)
        state.requests = Requests.add(state.requests, request)
        val kept = state.requests.first { it.id == request.id }
        events.add(EventKind.LOOSEN_REQUESTED, "$kind $target", now)
        val ready = gate(config).readyAt(kept)
        return Outcome.Done("Requested. It can be carried out in the open window after $ready.")
    }

    /** The waiting requests, oldest first. */
    fun requests(): List<ChangeRequest> = state.requests.sortedBy { it.requestedAtMillis }

    /** Gives up a waiting request. This closes things, so it is always allowed. */
    fun cancelRequest(id: String, now: Instant = Instant.now()): Outcome {
        state.requests = Requests.cancel(state.requests, id)
        events.add(EventKind.REQUEST_CANCELLED, id, now)
        return Outcome.Done("Cancelled.")
    }

    /** Carries out a waiting request, if its wait is over and the open window is running. */
    fun applyRequest(id: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val request = state.requests.firstOrNull { it.id == id } ?: return Outcome.Refused("That request is gone.")
        val config = config()
        val decision = gate(config).decideApply(request, now, networkTime)
        if (decision is Decision.Denied) return refused(decision.why, "apply ${request.id}", now)

        val updated: LockConfig = when (request.kind) {
            RequestKind.NIGHT_ADD -> config.copy(nightAllowlist = config.nightAllowlist + request.target, alwaysBlocked = config.alwaysBlocked - request.target)
            RequestKind.BLOCKED_REMOVE -> config.copy(alwaysBlocked = config.alwaysBlocked - request.target)
            RequestKind.SETTINGS -> request.settings ?: return Outcome.Refused("That request holds no settings.")
            RequestKind.BROWSER -> { state.approveBrowser(request.target); config }
            RequestKind.RELEASE_APP -> { state.release(request.target); config }
            RequestKind.REMOVE_PROTECTION -> return Outcome.Refused("Taking the protection off needs your friend's code: use Remove protection.")
        }
        ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
        if (updated != config) store.save(updated)
        state.requests = Requests.cancel(state.requests, id)
        events.add(EventKind.LOOSENED, "${request.kind} ${request.target}", now)
        if (request.kind == RequestKind.BROWSER) events.add(EventKind.BROWSER_APPROVED, request.target, now)
        if (request.kind == RequestKind.RELEASE_APP) events.add(EventKind.APP_RELEASED, request.target, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Carried out.")
    }

    /** Add or remove an app on the night list or the always-blocked list. Before protection starts these are free. */
    fun editList(list: AppList, op: ListOp, packageName: String, now: Instant = Instant.now()): Outcome {
        val config = config()
        val current = if (list == AppList.NIGHT_ALLOWED) config.nightAllowlist else config.alwaysBlocked
        val untouchable = DeviceInventory(context).read().protectedPackages + context.packageName
        if (packageName in untouchable) return Outcome.Refused("This app keeps the phone working and cannot be listed.")

        val loosens = editor.kindOf(list, op) == ChangeKind.LOOSEN
        if (store.protectionActive && loosens) return ask(editor.requestKind(list), packageName, now)

        val newList: Set<String> = if (op == ListOp.ADD) current + packageName else current - packageName
        val updated = if (list == AppList.NIGHT_ALLOWED) config.copy(nightAllowlist = newList) else config.copy(alwaysBlocked = newList)
        ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
        store.save(updated)
        if (store.protectionActive) events.add(EventKind.TIGHTENED, "$op $packageName on $list", now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done()
    }

    /** Saves changed settings (DNS host, browser, rhythm, location). After protection starts every change is a request that waits. */
    fun saveSettings(updated: LockConfig, now: Instant = Instant.now()): Outcome {
        ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
        if (store.protectionActive) {
            if (config() == updated) return Outcome.Done("Nothing changed.")
            return ask(RequestKind.SETTINGS, "settings", now, updated)
        }
        store.save(updated)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Saved.")
    }

    /** A new app held back since it was installed: asking to let it through is a request that waits. */
    fun releaseApp(packageName: String, now: Instant = Instant.now()): Outcome = ask(RequestKind.RELEASE_APP, packageName, now)

    /** Ask for a closed browser. A browser is a new door, so it waits like every other opening. */
    fun requestBrowser(packageName: String, now: Instant = Instant.now()): Outcome {
        events.add(EventKind.BROWSER_REQUESTED, packageName, now)
        return ask(RequestKind.BROWSER, packageName, now)
    }

    /**
     * The friend's code unblocks one app for 60 minutes, then it closes again by itself. It changes no setting and no list.
     */
    fun redeemEmergency(code: String, packageName: String, now: Instant = Instant.now()): Outcome {
        if (packageName.isBlank()) return Outcome.Refused("Choose the app to unblock.")
        val untouchable = DeviceInventory(context).read().protectedPackages + context.packageName
        if (packageName in untouchable) return Outcome.Refused("That app is never blocked.")
        val secret = secrets.load() ?: return Outcome.Refused("No friend code is set up.")
        val redemption = Emergency(secret).redeem(code, now, state.lastCodeCounter)
        if (redemption == null) {
            events.add(EventKind.EMERGENCY_REFUSED, "wrong or used code", now)
            return Outcome.Refused("That code is wrong or was already used.")
        }
        state.unblock(packageName, redemption.until)
        state.lastCodeCounter = redemption.counter
        events.add(EventKind.EMERGENCY_USED, "$packageName until ${redemption.until}", now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("$packageName is open for 60 minutes. It closes by itself.")
    }

    /**
     * Checks a code while the friend is on the phone during setup, to prove the secret reached them. The counter is remembered so the
     * same code cannot be used for an unblock a moment later.
     */
    fun verifyEnrolment(code: String, now: Instant = Instant.now()): Boolean {
        val secret = secrets.load() ?: return false
        val check = CodeChecker(secret).check(code, now, state.lastCodeCounter)
        if (check is CodeCheck.Accepted) state.lastCodeCounter = check.counter
        return check is CodeCheck.Accepted
    }

    /** Makes the friend's secret (once, in setup) and returns it for the QR code. Replaces any earlier one. */
    fun newFriendSecret(): ByteArray = FriendCode.newSecret().also {
        secrets.save(it)
        state.lastCodeCounter = null
    }

    val hasFriendSecret: Boolean get() = secrets.hasSecret

    /** Turns the protection on. Needs the device-owner role, valid settings and a friend code. */
    fun startProtection(now: Instant = Instant.now()): Outcome {
        if (BuildConfig.PREVIEW) return Outcome.Refused("This is the preview build: it is for looking at the screens and cannot start protection. Install the test build for that.")
        if (!dpm.isDeviceOwnerApp(context.packageName)) return Outcome.Refused("This app is not the device owner yet. Finish the first setup step.")
        val config = store.load() ?: return Outcome.Refused("Finish the setup first.")
        ConfigRules.problems(config).firstOrNull()?.let { return Outcome.Refused(it) }
        if (!secrets.hasSecret) return Outcome.Refused("Set up the friend's code first.")
        store.protectionActive = true
        events.add(EventKind.PROTECTION_STARTED, "", now)
        val result = PolicyRunner(context).run(config, now)
        return if (result.report.ok) Outcome.Done("Protection is on.") else Outcome.Done("Protection is on, but not everything could be applied: ${result.report.failed.joinToString("; ")}")
    }

    /** Asks to take the protection off. It waits like every opening; the friend's code is needed when it is carried out. */
    fun requestRemoval(now: Instant = Instant.now()): Outcome = ask(RequestKind.REMOVE_PROTECTION, "", now)

    /** Takes the protection off: the request has waited, the open window is running, and the friend's code is fresh. */
    fun removeProtection(code: String, now: Instant = Instant.now(), networkTime: Instant? = NetworkClock.now()): Outcome {
        val config = config()
        val secret = secrets.load() ?: return Outcome.Refused("No friend code is set up.")
        val request = state.requests.firstOrNull { it.kind == RequestKind.REMOVE_PROTECTION }
            ?: return Outcome.Refused("Ask to remove the protection first. The request waits ${config.loosenWaitDays} days.")
        events.add(EventKind.REMOVAL_REQUESTED, "", now)
        val gate = gate(config).decideApply(request, now, networkTime)
        val check = CodeChecker(secret).check(code, now, state.lastCodeCounter)
        val decision = Removal.decide(gate, check)
        if (decision is Decision.Denied) return refused(decision.why, "remove protection", now)
        if (check is CodeCheck.Accepted) state.lastCodeCounter = check.counter

        val everything = DeviceInventory(context).read().installed
        PolicyApplier(context).release(everything)
        PolicyRunner(context).cancelAlarm()
        store.protectionActive = false
        events.add(EventKind.PROTECTION_REMOVED, "", now)
        state.clearUnblocks()
        state.requests = emptyList()
        dpm.clearDeviceOwnerApp(context.packageName)
        return Outcome.Done("Protection removed. This app is no longer the device owner and can be uninstalled.")
    }
}
