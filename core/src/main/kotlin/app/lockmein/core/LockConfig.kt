package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime

val ALWAYS_BLOCKED_DEFAULT = setOf(
    "com.instagram.android",
    "com.facebook.katana",
    "com.facebook.lite",
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
)

val WINDOW_APPS_DEFAULT = setOf(
    "com.google.android.youtube",
)

val WATCHED_APPS_DEFAULT = setOf(
    "com.brave.browser",
    "com.android.chrome",
    "com.facebook.katana",
    "com.instagram.android",
)

/**
 * Everything a person chooses in the setup. Plain values only, so it can be saved, exported as text and imported on another phone.
 * It holds no secrets: the friend's code and the accounts never go in here.
 */
@Serializable
data class LockConfig(
    /** The private DNS host, e.g. `<profile>.dns.nextdns.io`. */
    val dnsHost: String,
    /** The one browser that stays open; every other browser is closed until requested. Null closes them all. */
    val allowedBrowser: String? = null,
    /** Apps closed from the night start until fajr. Everything else keeps running. (The old `nightAllowlist`, the reverse, is ignored on load.) */
    val nightClosed: Set<String> = emptySet(),
    val alwaysBlocked: Set<String> = ALWAYS_BLOCKED_DEFAULT,
    /** Apps suspended all day except during the daily open window (20:00 to 21:30). */
    val windowApps: Set<String> = WINDOW_APPS_DEFAULT,
    /** Apps monitored by the on-device AI screen filter. */
    val watchedApps: Set<String> = WATCHED_APPS_DEFAULT,
    /** Filter threshold for porn + hentai combined score. */
    val filterPornHentaiThreshold: Float = 0.70f,
    /** Filter threshold for sexy score. */
    val filterSexyThreshold: Float = 0.60f,
    /**
     * Anime model threshold (r15 + r18). Thresholds tuned on simulated phone screens scanned in tiles: 5% false hits on normal
     * photos of women and on plainly safe anime (fights, magic, swords), 62% of revealing photos caught per frame.
     */
    val filterAnimeThreshold: Float = 0.85f,
    /**
     * Seductive-looks model threshold (best of the screen tiles). On simulated phone screens: 98% of seductive photos caught,
     * 1% of everyday photos of women flagged; 67% of seductive anime caught, 10% of ordinary anime flagged.
     */
    val filterSeductiveThreshold: Float = 0.80f,
    /** False while the seductive model only shows its score in filter test mode; true once it closes apps too. */
    val filterSeductiveLive: Boolean = false,
    /** Start of the night and end of social-app time, "HH:mm". */
    val nightStart: String = "22:00",
    /** The weekly settings window, when settings can be changed and removals scheduled: ISO day (7 = Sunday) and its hours. */
    val windowDay: Int = 7,
    val windowStart: String = "20:00",
    val windowEnd: String = "21:00",
    /** How long anything that opens the phone up waits before it takes effect. */
    val loosenWaitDays: Int = 7,
    val latitude: Double = 30.4278,
    val longitude: Double = -9.5981,
    val sunAngleDegrees: Double = 19.0,
    val fajrOffsetMinutes: Int = 0,
) {
    fun scheduleConfig() = ScheduleConfig(
        nightStart = LocalTime.parse(nightStart),
        windowDay = DayOfWeek.of(windowDay),
        windowStart = LocalTime.parse(windowStart),
        windowEnd = LocalTime.parse(windowEnd),
    )

    fun fajrConfig() = FajrConfig(latitude, longitude, sunAngleDegrees, fajrOffsetMinutes.toLong())

    companion object {
        val ALWAYS_BLOCKED_DEFAULT: Set<String> = app.lockmein.core.ALWAYS_BLOCKED_DEFAULT
        val WINDOW_APPS_DEFAULT: Set<String> = app.lockmein.core.WINDOW_APPS_DEFAULT
        val WATCHED_APPS_DEFAULT: Set<String> = app.lockmein.core.WATCHED_APPS_DEFAULT
    }
}

/** Saves and loads [LockConfig] as text, and refuses text that would make a broken lock. */
object ConfigCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(config: LockConfig): String = json.encodeToString(LockConfig.serializer(), config)

    /** @throws IllegalArgumentException with a plain message when the text is not a usable configuration. */
    fun decode(text: String): LockConfig {
        val config = try {
            json.decodeFromString(LockConfig.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This is not a Lock Me In configuration.")
        }
        val problems = ConfigRules.problems(config)
        require(problems.isEmpty()) { problems.first() }
        return config
    }
}

/** What makes a configuration safe to apply. */
object ConfigRules {
    private fun time(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    fun problems(config: LockConfig): List<String> = buildList {
        DnsHost.problem(config.dnsHost)?.let(::add)
        for ((label, value) in listOf("night start" to config.nightStart, "window start" to config.windowStart, "window end" to config.windowEnd)) {
            if (time(value) == null) add("The $label is not a time like 22:00.")
        }
        if (config.windowDay !in 1..7) add("The window day must be 1 (Monday) to 7 (Sunday).")
        val start = time(config.windowStart)
        val end = time(config.windowEnd)
        if (start != null && end != null && end <= start) add("The window must end after it starts.")
        if (config.loosenWaitDays !in 1..30) add("The wait before a change must be 1 to 30 days.")
        if (config.latitude !in -66.0..66.0) add("Latitude must be between -66 and 66 (fajr cannot be calculated near the poles).")
        if (config.longitude !in -180.0..180.0) add("Longitude must be between -180 and 180.")
        if (config.sunAngleDegrees !in 10.0..25.0) add("The sun angle for fajr must be between 10 and 25 degrees.")
        if (config.fajrOffsetMinutes !in -60..60) add("The fajr offset must be within an hour.")
        if (config.filterPornHentaiThreshold !in 0.05f..1.0f || config.filterSexyThreshold !in 0.05f..1.0f || config.filterAnimeThreshold !in 0.05f..1.0f || config.filterSeductiveThreshold !in 0.05f..1.0f) add("The filter thresholds must be between 0.05 and 1.0.")
        if (config.allowedBrowser != null && config.allowedBrowser in config.alwaysBlocked) add("The allowed browser cannot also be blocked.")
        if (config.allowedBrowser != null && config.allowedBrowser in config.windowApps) add("The allowed browser cannot also be a window app.")
    }
}
