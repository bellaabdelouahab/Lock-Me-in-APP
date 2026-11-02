package app.lockmein.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where the owner is and how fajr is counted. Defaults: Agadir, and the Moroccan ministry's 19 degrees. */
data class FajrConfig(
    val latitude: Double = 30.4278,
    val longitude: Double = -9.5981,
    val sunAngleDegrees: Double = 19.0,
    /** Added to the result to match the timetable the owner follows (checked against his prayer app). */
    val offsetMinutes: Long = 0,
)

/**
 * Fajr from the position of the sun: the moment it is [FajrConfig.sunAngleDegrees] below the horizon before sunrise.
 * Uses the NOAA solar formulas, good to about a minute at this latitude.
 */
class AstronomicalFajr(private val config: FajrConfig, private val zone: ZoneId) : FajrProvider {
    override fun fajr(date: LocalDate): LocalTime {
        val sun = SunPosition.at(date)
        val lat = Math.toRadians(config.latitude)
        val below = Math.toRadians(config.sunAngleDegrees)
        val cosHourAngle = (-sin(below) - sin(lat) * sin(sun.declination)) / (cos(lat) * cos(sun.declination))
        val hourAngleHours = Math.toDegrees(acos(cosHourAngle.coerceIn(-1.0, 1.0))) / 15.0
        val transitUtcHours = 12.0 - config.longitude / 15.0 - sun.equationOfTimeMinutes / 60.0
        val utcMidnight = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val fajr = utcMidnight
            .plus(Duration.ofSeconds(((transitUtcHours - hourAngleHours) * 3600).toLong()))
            .plus(Duration.ofMinutes(config.offsetMinutes))
        return fajr.atZone(zone).toLocalTime().withSecond(0).withNano(0)
    }
}

/** Declination of the sun and the equation of time at noon UTC of a date. */
internal data class SunPosition(val declination: Double, val equationOfTimeMinutes: Double) {
    companion object {
        fun at(date: LocalDate): SunPosition {
            val julianDay = date.toEpochDay() + 2440587.5 + 0.5
            val t = (julianDay - 2451545.0) / 36525.0
            val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
            val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
            val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
            val m = Math.toRadians(meanAnomaly)
            val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
            val omega = Math.toRadians(125.04 - 1934.136 * t)
            val apparentLongitude = Math.toRadians(meanLongitude + center - 0.00569 - 0.00478 * sin(omega))
            val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
            val obliquity = Math.toRadians(meanObliquity + 0.00256 * cos(omega))
            val declination = asin(sin(obliquity) * sin(apparentLongitude))

            val l0 = Math.toRadians(meanLongitude)
            val y = tan(obliquity / 2).let { it * it }
            val equation = y * sin(2 * l0) - 2 * eccentricity * sin(m) + 4 * eccentricity * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * m)
            return SunPosition(declination, 4.0 * Math.toDegrees(equation))
        }
    }
}
