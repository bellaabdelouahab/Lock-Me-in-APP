package app.lockmein

import android.content.Context
import app.lockmein.core.ConfigCodec
import app.lockmein.core.LockConfig

/** The person's settings, saved as text, and whether protection has been started. Holds no secrets. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("lock_config", Context.MODE_PRIVATE)

    /** Null until the setup has saved a configuration. A saved but unusable file counts as none. */
    fun load(): LockConfig? = prefs.getString(CONFIG, null)?.let { runCatching { ConfigCodec.decode(it) }.getOrNull() }

    fun save(config: LockConfig) = prefs.edit().putString(CONFIG, ConfigCodec.encode(config)).apply()

    /** True from "Start protection" until the protection is removed. Before that nothing is applied and everything can be changed freely. */
    var protectionActive: Boolean
        get() = prefs.getBoolean(ACTIVE, false)
        set(value) = prefs.edit().putBoolean(ACTIVE, value).apply()

    private companion object {
        const val CONFIG = "config_json"
        const val ACTIVE = "protection_active"
    }
}
