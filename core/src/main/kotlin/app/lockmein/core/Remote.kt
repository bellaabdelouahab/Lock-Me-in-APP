package app.lockmein.core

/**
 * What the MCP may change, mapped onto the same settings the app's screens change. The result goes through the app's own
 * ChangeManager.saveSettings, so a looser value becomes a request and a stricter filter value applies at once, exactly as on the
 * phone. Nothing outside this list can be set from the MCP.
 */
object RemoteSettings {
    val FIELDS = listOf(
        "dns_host", "allowed_browser", "night_start", "window_day", "window_start", "window_end", "loosen_wait_days",
        "latitude", "longitude", "sun_angle", "fajr_offset_minutes",
        "filter_porn_hentai", "filter_sexy", "filter_anime", "filter_seductive", "filter_seductive_live",
    )

    /** @throws IllegalArgumentException with a plain message for an unknown field or a value that is not one. */
    fun apply(c: LockConfig, field: String, value: String): LockConfig {
        val v = value.trim()
        fun f() = requireNotNull(v.toFloatOrNull()) { "$field needs a number" }
        fun d() = requireNotNull(v.toDoubleOrNull()) { "$field needs a number" }
        fun i() = requireNotNull(v.toIntOrNull()) { "$field needs a whole number" }
        val updated = when (field) {
            "dns_host" -> c.copy(dnsHost = v)
            "allowed_browser" -> c.copy(allowedBrowser = v.takeUnless { it.isEmpty() || it.equals("none", true) })
            "night_start" -> c.copy(nightStart = v)
            "window_day" -> c.copy(windowDay = i())
            "window_start" -> c.copy(windowStart = v)
            "window_end" -> c.copy(windowEnd = v)
            "loosen_wait_days" -> c.copy(loosenWaitDays = i())
            "latitude" -> c.copy(latitude = d())
            "longitude" -> c.copy(longitude = d())
            "sun_angle" -> c.copy(sunAngleDegrees = d())
            "fajr_offset_minutes" -> c.copy(fajrOffsetMinutes = i())
            "filter_porn_hentai" -> c.copy(filterPornHentaiThreshold = f())
            "filter_sexy" -> c.copy(filterSexyThreshold = f())
            "filter_anime" -> c.copy(filterAnimeThreshold = f())
            "filter_seductive" -> c.copy(filterSeductiveThreshold = f())
            "filter_seductive_live" -> c.copy(filterSeductiveLive = requireNotNull(v.lowercase().toBooleanStrictOrNull()) { "$field needs true or false" })
            else -> throw IllegalArgumentException("Unknown setting \"$field\". Settings: ${FIELDS.joinToString()}.")
        }
        ConfigRules.problems(updated).firstOrNull()?.let { throw IllegalArgumentException(it) }
        return updated
    }

    /** STRICTER, DEFAULT or LOOSER as the per-app offset (the Screen filter page's three steps). */
    fun strictness(level: String): Float = when (level.trim().uppercase()) {
        "STRICTER" -> -STEP
        "DEFAULT" -> 0f
        "LOOSER" -> STEP
        else -> throw IllegalArgumentException("level must be STRICTER, DEFAULT or LOOSER")
    }

    const val STEP = 0.10f
}
