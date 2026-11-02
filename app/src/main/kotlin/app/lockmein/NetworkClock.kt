package app.lockmein

import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Time as a web server says it, to check the phone's own clock before anything is loosened. Blocks: call off the main thread. */
object NetworkClock {
    private const val PROBE = "https://www.google.com/generate_204"

    /** Null when no server answered, which the change gate treats as "cannot confirm". */
    fun now(): Instant? = runCatching {
        val connection = (URL(PROBE).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            connection.getHeaderField("Date")?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
