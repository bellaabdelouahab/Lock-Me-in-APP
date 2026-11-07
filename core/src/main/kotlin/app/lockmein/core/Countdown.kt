package app.lockmein.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class HomeCountdown(
    val bigText: String,
    val settingsWindowLine: String,
    val uninstallWindowLine: String,
    val appsCountLine: String,
)

object CountdownFormatter {
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    fun formatDurationHM(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            else -> "$minutes min"
        }
    }

    fun formatDurationDH(duration: Duration): String {
        val totalHours = duration.toHours().coerceAtLeast(0)
        val days = totalHours / 24
        val hours = totalHours % 24
        return when {
            days > 1L && hours > 0 -> "$days days $hours h"
            days == 1L && hours > 0 -> "1 day $hours h"
            days > 1L -> "$days days"
            days == 1L -> "1 day"
            hours > 0 -> "$hours h"
            else -> "${duration.toMinutes().coerceAtLeast(0)} min"
        }
    }

    fun compute(schedule: Schedule, config: LockConfig, now: Instant, zone: ZoneId = ZoneId.systemDefault()): HomeCountdown {
        val local = now.atZone(zone)
        val time = local.toLocalTime()

        // 1. Big countdown
        val bigText = when {
            schedule.inDailyWindow(now) -> {
                val rem = Duration.between(now, schedule.nextDailyWindowEnd(now))
                "Window closes in ${formatDurationHM(rem)}"
            }
            time < Schedule.DAILY_WINDOW_START -> {
                val todayStart = ZonedDateTime.of(local.toLocalDate(), Schedule.DAILY_WINDOW_START, zone).toInstant()
                val rem = Duration.between(now, todayStart)
                "Window opens in ${formatDurationHM(rem)}"
            }
            else -> {
                val tomorrowStart = ZonedDateTime.of(local.toLocalDate().plusDays(1), Schedule.DAILY_WINDOW_START, zone).toInstant()
                val rem = Duration.between(now, tomorrowStart)
                "Next window tomorrow 20:00 (in ${formatDurationHM(rem)})"
            }
        }

        // 2. Settings window line
        val settingsWindowLine = if (schedule.inSettingsWindow(now)) {
            val rem = Duration.between(now, schedule.nextSettingsWindowEnd(now))
            "Settings window: open now (closes in ${formatDurationHM(rem)})"
        } else {
            val nextStart = schedule.nextSettingsWindowStart(now)
            val nextLocal = nextStart.atZone(zone)
            val dayName = if (nextLocal.toLocalDate() == local.toLocalDate()) "today"
            else nextLocal.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val rem = Duration.between(now, nextStart)
            "Settings window: $dayName 20:00 (in ${formatDurationDH(rem)})"
        }

        // 3. Uninstall window line
        val uninstallWindowLine = if (schedule.inUninstallWindow(now)) {
            val rem = Duration.between(now, schedule.nextUninstallWindowEnd(now))
            "Uninstall window: open now (closes in ${formatDurationHM(rem)})"
        } else {
            val nextStart = schedule.nextUninstallWindowStart(now)
            val nextLocal = nextStart.atZone(zone)
            val monthName = nextLocal.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val days = ChronoUnit.DAYS.between(local.toLocalDate(), nextLocal.toLocalDate()).coerceAtLeast(1)
            val daysStr = if (days == 1L) "1 day" else "$days days"
            "Uninstall window: ${nextLocal.dayOfMonth} $monthName 20:00 (in $daysStr)"
        }

        // 4. Apps count line
        val blockedCount = config.alwaysBlocked.size
        val windowCount = config.windowApps.size
        val blockedWord = if (blockedCount == 1) "app" else "apps"
        val windowWord = if (windowCount == 1) "app" else "apps"
        val appsCountLine = "Always blocked: $blockedCount $blockedWord. Window apps: $windowCount $windowWord."

        return HomeCountdown(bigText, settingsWindowLine, uninstallWindowLine, appsCountLine)
    }
}
