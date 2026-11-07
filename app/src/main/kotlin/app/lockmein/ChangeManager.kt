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
import app.lockmein.core.EventKind
import app.lockmein.core.FriendAction
import app.lockmein.core.FriendCode
import app.lockmein.core.ListOp
import app.lockmein.core.LockConfig
import app.lockmein.core.RequestKind
import app.lockmein.core.RequestResolution
import app.lockmein.core.Requests
import java.time.Duration
import java.time.Instant

/** What a change came to: done, or refused with a sentence the person can read. */
sealed interface Outcome {
    data class Done(val message: String = "Done.") : Outcome
    data class Refused(val reason: String) : Outcome
}

/**
 * Every change goes through here, so the rules are in one place.
 * Adding apps to lists is tightening and applies instantly.
 * Loosening changes during the week are recorded as requests for Sunday review (20:00 to 21:00).
 * Sunday settings window is for approving or dismissing waiting requests only (no modifications on the spot).
 * Friend's code validates once and unlocks exactly one action from a bottom sheet with no second prompt.
 */
class ChangeManager(private val context: Context) {
    private val store = ConfigStore(context)
    private val state = LockState(context)
    private val events = EventLog(context)
    private val secrets = SecretStore(context)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val editor = AppListEditor()

    /** The rules are locked only once the setup completed with every check passing; before that, every change is free. */
    val locked get() = store.protectionActive && store.setupComplete

    fun config(): LockConfig = checkNotNull(store.load()) { "the setup has not been finished" }

    private fun gate(config: LockConfig) = ChangeGate(Rules.schedule(config))

    private fun refused(why: String, what: String, now: Instant): Outcome.Refused {
        events.add(EventKind.DENIED, "$what: $why", now)
        return Outcome.Refused("Not now: $why.")
    }

    /** Validates a 6-digit friend code. If valid, burns the code counter immediately and clears lockout. */
    fun checkFriendCode(code: String, now: Instant = TrustedClock(context).now()): Outcome {
        val secret = secrets.load() ?: return Outcome.Refused("No friend's code is set up.")
        val check = CodeChecker(secret).check(
            entered = code,
            now = now,
            lastUsedCounter = state.lastCodeCounter,
            failedAttempts = state.failedCodeAttempts,
            lockoutUntil = state.lockoutUntil,
        )
        return when (check) {
            is CodeCheck.LockedOut -> {
                state.failedCodeAttempts = CodeChecker.MAX_FAILED_ATTEMPTS
                state.lockoutUntil = check.until
                events.add(EventKind.EMERGENCY_REFUSED, "lockout until ${check.until}", now)
                Outcome.Refused("Too many wrong codes. Locked out for 30 minutes.")
            }
            CodeCheck.Wrong -> {
                val nextFailed = state.failedCodeAttempts + 1
                state.failedCodeAttempts = nextFailed
                events.add(EventKind.EMERGENCY_REFUSED, "wrong code attempt $nextFailed", now)
                if (nextFailed >= CodeChecker.MAX_FAILED_ATTEMPTS) {
                    val until = now.plus(CodeChecker.LOCKOUT_DURATION)
                    state.lockoutUntil = until
                    Outcome.Refused("Too many wrong codes. Locked out for 30 minutes.")
                } else {
                    Outcome.Refused("That code is wrong. ${CodeChecker.MAX_FAILED_ATTEMPTS - nextFailed} attempts remaining.")
                }
            }
            CodeCheck.AlreadyUsed -> {
                events.add(EventKind.EMERGENCY_REFUSED, "already used code", now)
                Outcome.Refused("That code was already used. Wait for the next one.")
            }
            is CodeCheck.Accepted -> {
                state.lastCodeCounter = check.counter
                state.failedCodeAttempts = 0
                state.lockoutUntil = null
                Outcome.Done("Code verified.")
            }
        }
    }

    /** Authorizes friend code and returns token for test and UI flows */
    fun checkHolderCode(code: String, now: Instant = TrustedClock(context).now()): Pair<app.lockmein.core.FriendCodeAuthorizer.FriendAuthToken?, Outcome> {
        val outcome = checkFriendCode(code, now)
        return if (outcome is Outcome.Done) {
            app.lockmein.core.FriendCodeAuthorizer.FriendAuthToken(state.lastCodeCounter ?: 0L) to outcome
        } else {
            null to outcome
        }
    }

    fun friendUnblockApp(packageName: String, now: Instant = TrustedClock(context).now()): Outcome =
        applyFriendAction(FriendAction.OpenAppForHour(packageName), now)

    fun friendRedoSetup(now: Instant = TrustedClock(context).now()): Outcome =
        applyFriendAction(FriendAction.RedoSetup, now)

    fun approveRequest(id: String, now: Instant = TrustedClock(context).now(), networkTime: Instant? = null): Outcome =
        approveSundayRequest(id, now, networkTime)

    fun dismissRequest(id: String, now: Instant = TrustedClock(context).now()): Outcome =
        dismissSundayRequest(id, now)

    /** Applies exactly one action chosen from the bottom sheet after friend's code verification. No second code asked. */
    fun applyFriendAction(action: FriendAction, now: Instant = TrustedClock(context).now()): Outcome {
        return when (action) {
            is FriendAction.OpenAppForHour -> {
                state.unblock(action.packageName, now.plus(Duration.ofHours(1)))
                events.add(EventKind.LOOSENED, "Opened ${action.packageName} for 1 hour with friend code", now)
                PolicyRunner(context).runIfActive(now)
                Outcome.Done("Opened ${DeviceInventory(context).label(action.packageName)} for one hour.")
            }
            is FriendAction.ApproveRequest -> {
                val request = state.requests.firstOrNull { it.id == action.requestId }
                    ?: return Outcome.Refused("That request is no longer waiting.")
                carryOut(request, config(), now, " with friend code")
                state.recordResolution(request, RequestResolution.APPROVED, now)
                Outcome.Done("Approved request.")
            }
            is FriendAction.StopBlockingApp -> {
                val config = config()
                val untouchable = DeviceInventory(context).read().protectedPackages + context.packageName
                if (action.packageName in untouchable) return Outcome.Refused("This app keeps the phone working and cannot be listed.")
                val updated = when (action.list) {
                    AppList.NIGHT_CLOSED -> config.copy(nightClosed = config.nightClosed - action.packageName)
                    AppList.ALWAYS_BLOCKED -> config.copy(alwaysBlocked = config.alwaysBlocked - action.packageName)
                    AppList.WINDOW_APPS -> config.copy(windowApps = config.windowApps - action.packageName)
                }
                ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
                store.save(updated)
                events.add(EventKind.LOOSENED, "Stop blocking ${action.packageName} on ${action.list} with friend code", now)
                PolicyRunner(context).runIfActive(now)
                Outcome.Done("Stopped blocking ${DeviceInventory(context).label(action.packageName)}.")
            }
            is FriendAction.ReleaseApp -> {
                state.release(action.packageName)
                events.add(EventKind.APP_RELEASED, "${action.packageName} with friend code", now)
                PolicyRunner(context).runIfActive(now)
                Outcome.Done("Let ${DeviceInventory(context).label(action.packageName)} through.")
            }
            is FriendAction.ApproveBrowser -> {
                state.approveBrowser(action.packageName)
                events.add(EventKind.BROWSER_APPROVED, "${action.packageName} with friend code", now)
                PolicyRunner(context).runIfActive(now)
                Outcome.Done("Approved browser ${DeviceInventory(context).label(action.packageName)}.")
            }
            FriendAction.RedoSetup -> {
                reopenSetup()
                events.add(EventKind.LOOSENED, "Redo setup with friend code", now)
                Outcome.Done("The setup is open. Go through the steps, then Start to lock it again.")
            }
        }
    }

    /** Called when the user dismisses the bottom sheet without selecting an action, wasting the entered code. */
    fun wasteFriendCode(now: Instant = TrustedClock(context).now()): Outcome {
        events.add(EventKind.EMERGENCY_REFUSED, "Code wasted without choosing an action", now)
        return Outcome.Refused("This code is now used. Pick one action.")
    }

    /** Re-keys the holder secret. Requires the current valid code first. */
    fun reKeySecret(code: String, now: Instant = TrustedClock(context).now()): ByteArray? {
        val secret = secrets.load() ?: return null
        val check = CodeChecker(secret).check(
            entered = code,
            now = now,
            lastUsedCounter = state.lastCodeCounter,
            failedAttempts = state.failedCodeAttempts,
            lockoutUntil = state.lockoutUntil,
        )
        if (check !is CodeCheck.Accepted) {
            if (check is CodeCheck.LockedOut) {
                state.failedCodeAttempts = CodeChecker.MAX_FAILED_ATTEMPTS
                state.lockoutUntil = check.until
            } else if (check is CodeCheck.Wrong) {
                val nextFailed = state.failedCodeAttempts + 1
                state.failedCodeAttempts = nextFailed
                if (nextFailed >= CodeChecker.MAX_FAILED_ATTEMPTS) {
                    state.lockoutUntil = now.plus(CodeChecker.LOCKOUT_DURATION)
                }
            }
            return null
        }
        state.lastCodeCounter = check.counter
        state.failedCodeAttempts = 0
        state.lockoutUntil = null
        return newFriendSecret()
    }

    /** The waiting requests, sorted by most asked first. */
    fun requests(): List<ChangeRequest> = Requests.sortByMostAsked(state.requests)

    /** Gives up a waiting request. This closes things, so it is always allowed. */
    fun cancelRequest(id: String, now: Instant = TrustedClock(context).now()): Outcome {
        state.requests = Requests.cancel(state.requests, id)
        events.add(EventKind.REQUEST_CANCELLED, id, now)
        return Outcome.Done("Cancelled.")
    }

    /** Approves a waiting request during Sunday settings window review (20:00 to 21:00). */
    fun approveSundayRequest(id: String, now: Instant = TrustedClock(context).now(), networkTime: Instant? = null): Outcome {
        val request = state.requests.firstOrNull { it.id == id } ?: return Outcome.Refused("That request is gone.")
        val config = config()
        val decision = gate(config).decideSundayReview(request, now, networkTime)
        if (decision is Decision.Denied) return refused(decision.why, "approve ${request.id}", now)
        val carryResult = carryOut(request, config, now, "")
        if (carryResult is Outcome.Done) {
            state.recordResolution(request, RequestResolution.APPROVED, now)
        }
        return carryResult
    }

    /** Dismisses a waiting request during Sunday review. */
    fun dismissSundayRequest(id: String, now: Instant = TrustedClock(context).now()): Outcome {
        val request = state.requests.firstOrNull { it.id == id } ?: return Outcome.Refused("That request is gone.")
        state.recordResolution(request, RequestResolution.DISMISSED, now)
        events.add(EventKind.REQUEST_CANCELLED, "Sunday dismiss ${request.id}", now)
        return Outcome.Done("Dismissed.")
    }

    /** Alias for Sunday review approval. */
    fun applyRequest(id: String, now: Instant = TrustedClock(context).now(), networkTime: Instant? = null): Outcome =
        approveSundayRequest(id, now, networkTime)

    /** Applies a request and drops it from the queue. */
    private fun carryOut(request: ChangeRequest, config: LockConfig, now: Instant, how: String): Outcome {
        val updated: LockConfig = when (request.kind) {
            RequestKind.NIGHT_ADD -> config
            RequestKind.NIGHT_REMOVE -> config.copy(nightClosed = config.nightClosed - request.target)
            RequestKind.BLOCKED_REMOVE -> config.copy(alwaysBlocked = config.alwaysBlocked - request.target)
            RequestKind.WINDOW_REMOVE -> config.copy(windowApps = config.windowApps - request.target)
            RequestKind.SETTINGS -> request.settings ?: return Outcome.Refused("That request holds no settings.")
            RequestKind.BROWSER -> { state.approveBrowser(request.target); config }
            RequestKind.RELEASE_APP -> { state.release(request.target); config }
            RequestKind.REDO_SETUP -> { reopenSetup(); config }
            RequestKind.REMOVE_PROTECTION -> return Outcome.Refused("Use Remove Lock Me In during the monthly uninstall window.")
        }
        ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
        if (updated != config) store.save(updated)
        state.requests = Requests.cancel(state.requests, request.id)
        events.add(EventKind.LOOSENED, "${request.kind} ${request.target}$how", now)
        if (request.kind == RequestKind.BROWSER) events.add(EventKind.BROWSER_APPROVED, request.target, now)
        if (request.kind == RequestKind.RELEASE_APP) events.add(EventKind.APP_RELEASED, request.target, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Carried out.")
    }

    /** Add or remove an app on the night list, always-blocked list, or window apps list. */
    fun editList(list: AppList, op: ListOp, packageName: String, now: Instant = TrustedClock(context).now()): Outcome {
        val config = config()
        val current = when (list) {
            AppList.NIGHT_CLOSED -> config.nightClosed
            AppList.ALWAYS_BLOCKED -> config.alwaysBlocked
            AppList.WINDOW_APPS -> config.windowApps
        }
        val untouchable = DeviceInventory(context).read().protectedPackages + context.packageName
        if (packageName in untouchable) return Outcome.Refused("This app keeps the phone working and cannot be listed.")

        if (op == ListOp.ADD) {
            val newList = current + packageName
            val updated = when (list) {
                AppList.NIGHT_CLOSED -> config.copy(nightClosed = newList)
                AppList.ALWAYS_BLOCKED -> config.copy(alwaysBlocked = newList)
                AppList.WINDOW_APPS -> config.copy(windowApps = newList)
            }
            ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
            store.save(updated)
            if (store.protectionActive) events.add(EventKind.TIGHTENED, "$op $packageName on $list", now)
            PolicyRunner(context).runIfActive(now)
            return Outcome.Done()
        }

        // REMOVE (loosening)
        if (!locked) {
            val newList = current - packageName
            val updated = when (list) {
                AppList.NIGHT_CLOSED -> config.copy(nightClosed = newList)
                AppList.ALWAYS_BLOCKED -> config.copy(alwaysBlocked = newList)
                AppList.WINDOW_APPS -> config.copy(windowApps = newList)
            }
            store.save(updated)
            PolicyRunner(context).runIfActive(now)
            return Outcome.Done()
        }

        // When locked: removal is only a request reviewed on Sunday
        val kind = when (list) {
            AppList.NIGHT_CLOSED -> RequestKind.NIGHT_REMOVE
            AppList.ALWAYS_BLOCKED -> RequestKind.BLOCKED_REMOVE
            AppList.WINDOW_APPS -> RequestKind.WINDOW_REMOVE
        }
        val req = ChangeRequest(kind, packageName, now.toEpochMilli())
        val count = state.addRequest(req, now)
        events.add(EventKind.LOOSEN_REQUESTED, "Requested removal: $packageName from $list (asked $count times)", now)
        return Outcome.Done("Removal requested. Review on Sunday 20:00 to 21:00.")
    }

    /** Saves changed settings (DNS host, browser, rhythm, location). When locked, recorded as request. */
    fun saveSettings(updated: LockConfig, now: Instant = TrustedClock(context).now()): Outcome {
        ConfigRules.problems(updated).firstOrNull()?.let { return Outcome.Refused(it) }
        if (locked) {
            if (config() == updated) return Outcome.Done("Nothing changed.")
            val req = ChangeRequest(RequestKind.SETTINGS, "", now.toEpochMilli(), settings = updated)
            val count = state.addRequest(req, now)
            events.add(EventKind.LOOSEN_REQUESTED, "Requested settings change (asked $count times)", now)
            return Outcome.Done("Settings change requested. Review on Sunday 20:00 to 21:00.")
        }
        store.save(updated)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Saved.")
    }

    /** Lets a held-back new app through. When locked, recorded as request. */
    fun releaseApp(packageName: String, now: Instant = TrustedClock(context).now()): Outcome {
        if (locked) {
            val req = ChangeRequest(RequestKind.RELEASE_APP, packageName, now.toEpochMilli())
            val count = state.addRequest(req, now)
            events.add(EventKind.LOOSEN_REQUESTED, "Requested to let $packageName through (asked $count times)", now)
            return Outcome.Done("Requested to let through. Review on Sunday 20:00 to 21:00.")
        }
        state.release(packageName)
        events.add(EventKind.APP_RELEASED, packageName, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Let through.")
    }

    /** Approves a closed browser. When locked, recorded as request. */
    fun approveBrowser(packageName: String, now: Instant = TrustedClock(context).now()): Outcome {
        if (locked) {
            val req = ChangeRequest(RequestKind.BROWSER, packageName, now.toEpochMilli())
            val count = state.addRequest(req, now)
            events.add(EventKind.LOOSEN_REQUESTED, "Requested to approve browser $packageName (asked $count times)", now)
            return Outcome.Done("Browser approval requested. Review on Sunday 20:00 to 21:00.")
        }
        state.approveBrowser(packageName)
        events.add(EventKind.BROWSER_APPROVED, packageName, now)
        PolicyRunner(context).runIfActive(now)
        return Outcome.Done("Approved.")
    }

    /** Removes Lock Me In during the monthly uninstall window. */
    fun removeLockMeIn(now: Instant = TrustedClock(context).now()): Outcome {
        val schedule = Rules.schedule(config())
        if (!schedule.inUninstallWindow(now)) {
            return Outcome.Refused("Lock Me In can only be removed during the monthly uninstall window (1st of the month, 20:00 to 21:00).")
        }
        if (!BuildConfig.PREVIEW) {
            PolicyApplier(context).release(DeviceInventory(context).read().installed)
            PolicyRunner(context).cancelAlarm()
        }
        store.protectionActive = false
        events.add(EventKind.PROTECTION_REMOVED, "monthly uninstall window", now)
        state.clear()
        if (!BuildConfig.PREVIEW) dpm.clearDeviceOwnerApp(context.packageName)
        return Outcome.Done("Protection removed.")
    }

    /**
     * Checks a code while the friend/holder is on the phone during setup, to prove the secret reached them.
     */
    fun verifyEnrolment(code: String, now: Instant = TrustedClock(context).now()): Boolean {
        val secret = secrets.load() ?: return false
        val check = CodeChecker(secret).check(code, now, state.lastCodeCounter)
        if (check is CodeCheck.Accepted) state.lastCodeCounter = check.counter
        return check is CodeCheck.Accepted
    }

    /** Makes the holder secret (once, in setup) and returns it for the QR code. Replaces any earlier one. */
    fun newFriendSecret(): ByteArray = FriendCode.newSecret().also {
        secrets.save(it)
        state.lastCodeCounter = null
    }

    val hasFriendSecret: Boolean get() = secrets.hasSecret

    /** Turns the protection on. Needs the device-owner role, valid settings and a holder code. */
    fun startProtection(now: Instant = TrustedClock(context).now()): Outcome {
        if (BuildConfig.PREVIEW) {
            store.protectionActive = true
            events.add(EventKind.PROTECTION_STARTED, "preview", now)
            return Outcome.Done("Preview: the app now looks as it will when protection is on. Nothing was applied to this phone.")
        }
        if (!dpm.isDeviceOwnerApp(context.packageName)) return Outcome.Refused("This app is not the device owner yet. Finish the first setup step.")
        TrustedClock(context).renewIfStale()
        val config = store.load() ?: return Outcome.Refused("Finish the setup first.")
        ConfigRules.problems(config).firstOrNull()?.let { return Outcome.Refused(it) }
        if (!secrets.hasSecret) return Outcome.Refused("Set up the holder code first.")
        store.protectionActive = true
        events.add(EventKind.PROTECTION_STARTED, "", now)
        val result = PolicyRunner(context).run(config, now)
        if (!onlineAfterDns()) {
            PolicyApplier(context).release(DeviceInventory(context).read().installed)
            PolicyRunner(context).cancelAlarm()
            store.protectionActive = false
            events.add(EventKind.PASS_FAILED, "no internet with ${config.dnsHost}; undone", now)
            return Outcome.Refused("With ${config.dnsHost} as private DNS this phone could not reach the internet, so everything was undone and the internet is back. Check the host in step 2, or try on another network (some block secure DNS).")
        }
        return if (result.report.ok) Outcome.Done("Protection is on.") else Outcome.Done("Protection is on, but not everything could be applied: ${result.report.failed.joinToString("; ")}")
    }

    /**
     * Back to square one while the setup is not finished.
     */
    fun resetSetup(): Outcome {
        if (store.redoing) return Outcome.Refused("This is a redo of a completed setup: Reset is closed. Finish the setup to lock the rules again.")
        if (store.setupComplete) return Outcome.Refused("The setup is complete, so the protection can only be removed in the monthly uninstall window.")
        if (!BuildConfig.PREVIEW && dpm.isDeviceOwnerApp(context.packageName)) {
            val report = PolicyApplier(context).release(DeviceInventory(context).read().installed)
            if (!report.ok) return Outcome.Refused("Could not undo everything: ${report.failed.joinToString("; ")}")
        }
        PolicyRunner(context).cancelAlarm()
        store.protectionActive = false
        context.getSharedPreferences("lock_config", Context.MODE_PRIVATE).edit().clear().apply()
        state.clear()
        secrets.clear()
        events.clear()
        return Outcome.Done("Reset. Private DNS is back to automatic, nothing is blocked, and the setup starts again from the beginning.")
    }

    /** Redo setup immediately if setup is not complete. When locked, requests redo for Sunday review. */
    fun startRedo(now: Instant = TrustedClock(context).now()): Outcome {
        if (locked) {
            val req = ChangeRequest(RequestKind.REDO_SETUP, "", now.toEpochMilli())
            val count = state.addRequest(req, now)
            events.add(EventKind.LOOSEN_REQUESTED, "Requested redo setup (asked $count times)", now)
            return Outcome.Done("Redo setup requested. Review on Sunday 20:00 to 21:00.")
        }
        reopenSetup()
        events.add(EventKind.LOOSENED, "Redo setup started", now)
        return Outcome.Done("Setup is open.")
    }

    /** Lists and settings change freely until Start passes every check again; the protection itself stays on meanwhile. */
    private fun reopenSetup() {
        store.setupComplete = false
        store.redoing = true
    }

    private fun onlineAfterDns(): Boolean {
        repeat(5) {
            Thread.sleep(4_000)
            if (NetworkClock.now() != null) return true
        }
        return false
    }
}
