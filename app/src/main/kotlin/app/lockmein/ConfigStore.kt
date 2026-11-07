package app.lockmein

import android.content.Context
import app.lockmein.core.ALWAYS_BLOCKED_DEFAULT
import app.lockmein.core.ConfigCodec
import app.lockmein.core.LockConfig
import app.lockmein.core.WINDOW_APPS_DEFAULT

/** The person's settings, saved as text, and whether protection has been started. Holds no secrets. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("lock_config", Context.MODE_PRIVATE)

    /** Null until the setup has saved a configuration. A saved but unusable file counts as none. */
    fun load(): LockConfig? {
        val raw = prefs.getString(CONFIG, null) ?: return null
        val decoded = runCatching { ConfigCodec.decode(raw) }.getOrNull() ?: return null
        val migrated = migrate(decoded)
        if (migrated != decoded) {
            save(migrated)
        }
        return migrated
    }

    fun save(config: LockConfig) = prefs.edit().putString(CONFIG, ConfigCodec.encode(config)).apply()

    /** Migrates config: adds the five packages without dropping existing ones, and ensures windowApps is present. */
    fun migrate(config: LockConfig): LockConfig {
        val newBlocked = config.alwaysBlocked + ALWAYS_BLOCKED_DEFAULT
        val newWindow = if (config.windowApps.isEmpty()) WINDOW_APPS_DEFAULT else config.windowApps
        return if (newBlocked != config.alwaysBlocked || newWindow != config.windowApps) {
            config.copy(alwaysBlocked = newBlocked, windowApps = newWindow)
        } else {
            config
        }
    }

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

    private companion object {
        const val REDO = "redoing"
        const val COMPLETE = "setup_complete"
        const val CONFIG = "config_json"
        const val ACTIVE = "protection_active"
    }
}
