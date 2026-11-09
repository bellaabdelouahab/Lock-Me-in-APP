package app.lockmein

import android.content.Context
import app.lockmein.core.ALWAYS_BLOCKED_DEFAULT
import app.lockmein.core.ConfigCodec
import app.lockmein.core.LockConfig
import app.lockmein.core.WATCHED_APPS_DEFAULT
import app.lockmein.core.WINDOW_APPS_DEFAULT

/** The person's settings, saved as text, and whether protection has been started. Holds no secrets. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("lock_config", Context.MODE_PRIVATE)

    /** Null until the setup has saved a configuration. A saved but unusable file counts as none. */
    fun load(): LockConfig? {
        val raw = prefs.getString(CONFIG, null) ?: return null
        val decoded = runCatching { ConfigCodec.decode(raw) }.getOrNull() ?: return null
        // Each migration runs once: running the list defaults on every load put back apps that a Sunday approval had removed.
        val version = prefs.getInt(VERSION, 0)
        if (version >= CURRENT_VERSION) return decoded
        var migrated = decoded
        if (version < 1) migrated = migrate(migrated)
        if (version < 2) migrated = tunedThresholds(migrated)
        if (version < 3) migrated = migrated.copy(filterSeductiveLive = true) // v0.4.80: out of test mode
        save(migrated)
        prefs.edit().putInt(VERSION, CURRENT_VERSION).apply()
        return migrated
    }

    fun save(config: LockConfig) = prefs.edit().putString(CONFIG, ConfigCodec.encode(config)).apply()

    /** True from "Start protection" until the protection is removed. Before that nothing is applied and everything can be changed freely. */
    var protectionActive: Boolean
        get() = prefs.getBoolean(ACTIVE, false)
        set(value) = prefs.edit().putBoolean(ACTIVE, value).apply()

    /**
     * True once the setup finished with every check read back from Android passing. Until then the setup can be reset to the
     * phone's defaults at any time (a setup that went wrong must never leave the phone stuck); after it, only the removal path.
     */
    var setupComplete: Boolean
        get() = prefs.getBoolean(COMPLETE, false)
        // Completing ends a redo too.
        set(value) = prefs.edit().putBoolean(COMPLETE, value).apply { if (value) putBoolean(REDO, false) }.apply()

    /**
     * True while a completed setup was reopened to go through the steps again (a waited request, or the friend's code). The rules
     * are free while it lasts, like a first setup, but Reset stays closed: a redo is never a way to take the protection off.
     */
    var redoing: Boolean
        get() = prefs.getBoolean(REDO, false)
        set(value) = prefs.edit().putBoolean(REDO, value).apply()

    companion object {
        private const val REDO = "redoing"
        private const val COMPLETE = "setup_complete"
        private const val CONFIG = "config_json"
        private const val ACTIVE = "protection_active"

        /** Migrates config: ensures defaults for alwaysBlocked, windowApps, and watchedApps are present without dropping existing. */
        private const val VERSION = "config_version"
        private const val CURRENT_VERSION = 3

        /** v0.4.67: the filter scans the screen in tiles; thresholds tuned on 600 test images. Only untouched defaults move. */
        fun tunedThresholds(config: LockConfig): LockConfig =
            if (config.filterPornHentaiThreshold == 0.50f && config.filterSexyThreshold == 0.50f) {
                config.copy(filterPornHentaiThreshold = 0.70f, filterSexyThreshold = 0.60f, filterAnimeThreshold = 0.85f)
            } else config

        fun migrate(config: LockConfig): LockConfig {
            val newBlocked = config.alwaysBlocked + ALWAYS_BLOCKED_DEFAULT
            val newWindow = if (config.windowApps.isEmpty()) WINDOW_APPS_DEFAULT else config.windowApps
            val newWatched = config.watchedApps + WATCHED_APPS_DEFAULT
            return if (newBlocked != config.alwaysBlocked || newWindow != config.windowApps || newWatched != config.watchedApps) {
                config.copy(alwaysBlocked = newBlocked, windowApps = newWindow, watchedApps = newWatched)
            } else {
                config
            }
        }
    }
}
