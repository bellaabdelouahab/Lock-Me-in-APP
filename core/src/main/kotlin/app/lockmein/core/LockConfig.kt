package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime

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
    val alwaysBlocked: Set<String> = emptySet(),
    /** Start of the night and end of social-app time, "HH:mm". */
    val nightStart: String = "22:00",
    /** The weekly open window, when waiting requests can be carried out: ISO day (1 = Monday .. 7 = Sunday) and its hours. */
    val windowDay: Int = 1,
    val windowStart: String = "09:00",
    val windowEnd: String = "10:00",
    /** How long anything that opens the phone up waits before it can be carried out in the window. */
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
        if (config.allowedBrowser != null && config.allowedBrowser in config.alwaysBlocked) add("The allowed browser cannot also be blocked.")
    }
}
