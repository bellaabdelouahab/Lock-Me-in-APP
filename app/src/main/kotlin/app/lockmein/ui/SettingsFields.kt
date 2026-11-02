package app.lockmein.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lockmein.core.DnsHost
import app.lockmein.core.LockConfig
import java.time.LocalTime

/** A text field that keeps what was typed, and passes a value on only when it parses; [hint] says what is expected. */
@Composable
private fun <T> ValidatedField(label: String, initial: String, hint: String, parse: (String) -> T?, onValid: (T) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val parsed = parse(text)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parse(it)?.let(onValid)
        },
        label = { Text(label) },
        supportingText = { Text(if (parsed == null) "Not valid: $hint" else hint) },
        isError = parsed == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private fun time(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

@Composable
fun DnsFields(config: LockConfig, onChange: (LockConfig) -> Unit) {
    var typed by remember { mutableStateOf(config.dnsHost) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it
                DnsHost.normalise(it)?.let { host -> onChange(config.copy(dnsHost = host)) }
            },
            label = { Text("Private DNS host") },
            supportingText = { Text(DnsHost.problem(DnsHost.normalise(typed) ?: typed) ?: "Will use ${DnsHost.normalise(typed)}") },
            isError = DnsHost.normalise(typed) == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Note("A NextDNS profile is free: sign in at nextdns.io, open Setup, and copy the host under Endpoints (like abc123.dns.nextdns.io) or just the 6-character id. Any host that supports DNS over TLS works.")
    }
}

@Composable
fun RhythmFields(config: LockConfig, onChange: (LockConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ValidatedField("Night starts (everything but the night list stops)", config.nightStart, "a time like 22:00", ::time) { onChange(config.copy(nightStart = it.toString())) }
        Note("The weekly open window, the one time you can change things:")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DAYS.chunked(4).forEach { row ->
                ButtonRow {
                    row.forEach { name ->
                        val number = DAYS.indexOf(name) + 1
                        if (number == config.windowDay) MenuButtonSmall(name) {} else SecondaryButtonSmall(name) { onChange(config.copy(windowDay = number)) }
                    }
                }
            }
        }
        ValidatedField("Window opens", config.windowStart, "a time like 20:00", ::time) { onChange(config.copy(windowStart = it.toString())) }
        ValidatedField("Window closes", config.windowEnd, "a time like 22:00, after it opens", ::time) { onChange(config.copy(windowEnd = it.toString())) }
        ValidatedField("A requested browser opens only before", config.daytimeEnd, "a time like 20:00", ::time) { onChange(config.copy(daytimeEnd = it.toString())) }
        ValidatedField("Browser wait in minutes", config.browserWaitMinutes.toString(), "1 to 1440", { it.trim().toIntOrNull()?.takeIf { n -> n in 1..1440 } }) { onChange(config.copy(browserWaitMinutes = it)) }
    }
}

private data class Place(val name: String, val latitude: Double, val longitude: Double, val angle: Double)

private val PLACES = listOf(
    Place("Agadir", 30.4278, -9.5981, 19.0),
    Place("Casablanca", 33.5731, -7.5898, 19.0),
    Place("Marrakech", 31.6295, -7.9811, 19.0),
    Place("Paris", 48.8566, 2.3522, 18.0),
    Place("Brussels", 50.8503, 4.3517, 18.0),
    Place("Geneva", 46.2044, 6.1432, 18.0),
)

@Composable
fun LocationFields(config: LockConfig, onChange: (LockConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Note("Fajr, where the night ends, is worked out from where you are. Pick a place or type your own.")
        PLACES.chunked(3).forEach { row ->
            ButtonRow {
                row.forEach { place ->
                    SecondaryButtonSmall(place.name) { onChange(config.copy(latitude = place.latitude, longitude = place.longitude, sunAngleDegrees = place.angle)) }
                }
            }
        }
        ValidatedField("Latitude", config.latitude.toString(), "-66 to 66", { it.trim().toDoubleOrNull()?.takeIf { v -> v in -66.0..66.0 } }) { onChange(config.copy(latitude = it)) }
        ValidatedField("Longitude", config.longitude.toString(), "-180 to 180", { it.trim().toDoubleOrNull()?.takeIf { v -> v in -180.0..180.0 } }) { onChange(config.copy(longitude = it)) }
        ValidatedField("Sun angle for fajr (degrees)", config.sunAngleDegrees.toString(), "10 to 25 (19 in Morocco, 18 in much of Europe)", { it.trim().toDoubleOrNull()?.takeIf { v -> v in 10.0..25.0 } }) { onChange(config.copy(sunAngleDegrees = it)) }
        ValidatedField("Fajr adjustment in minutes", config.fajrOffsetMinutes.toString(), "-60 to 60; compare with your prayer app", { it.trim().toIntOrNull()?.takeIf { n -> n in -60..60 } }) { onChange(config.copy(fajrOffsetMinutes = it)) }
    }
}
