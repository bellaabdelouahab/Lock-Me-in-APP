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
import app.lockmein.core.Place
import app.lockmein.core.Places
import app.lockmein.PhoneLocation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch
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
        shape = FieldShape,
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
            supportingText = { Text(if (typed.isBlank()) "Like abc123.dns.nextdns.io, or just the 6-character NextDNS id." else DnsHost.problem(DnsHost.normalise(typed) ?: typed) ?: "Will use ${DnsHost.normalise(typed)}") },
            isError = typed.isNotBlank() && DnsHost.normalise(typed) == null, // nothing typed yet is not a mistake
            singleLine = true,
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Note("A NextDNS profile is free: sign in at nextdns.io, open Setup, and copy the host under Endpoints (like abc123.dns.nextdns.io) or just the 6-character id. Any host that supports DNS over TLS works.")
    }
}

@Composable
fun RhythmFields(config: LockConfig, onChange: (LockConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ValidatedField("Night starts (everything but the night list stops)", config.nightStart, "a time like 22:00", ::time) { onChange(config.copy(nightStart = it.toString())) }
        Note("The weekly open window: the only hour when a change you asked for a week ago can be carried out. Anything that opens the phone up waits 7 days first.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DAYS.chunked(4).forEach { row ->
                ButtonRow {
                    row.forEach { name ->
                        val number = DAYS.indexOf(name) + 1
                        val shortName = name.take(3)
                        if (number == config.windowDay) MenuButtonSmall(shortName) {} else SecondaryButtonSmall(shortName) { onChange(config.copy(windowDay = number)) }
                    }
                }
            }
        }
        ValidatedField("Window opens", config.windowStart, "a time like 09:00", ::time) { onChange(config.copy(windowStart = it.toString())) }
        ValidatedField("Window closes", config.windowEnd, "a time like 10:00, after it opens", ::time) { onChange(config.copy(windowEnd = it.toString())) }
    }
}

/** Where the night ends: the phone's location when given, else a city picked from the list; the coordinates stay one tap away. */
@Composable
fun LocationFields(config: LockConfig, onChange: (LockConfig) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    fun useLocation() {
        status = "Finding where you are..."
        scope.launch {
            val loc = PhoneLocation.current(context)
            if (loc == null) {
                status = "Location is not available. Turn it on, or pick your city."
            } else {
                val (near, km) = Places.nearest(loc.first, loc.second)
                val angle = if (km <= 300) Places.angleFor(near.country) else config.sunAngleDegrees
                onChange(config.copy(latitude = round4(loc.first), longitude = round4(loc.second), sunAngleDegrees = angle))
                status = if (km <= 30) "Set to your location, near ${near.name}." else "Set to your location."
            }
        }
    }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) useLocation() else status = "No location permission. Pick your city instead." }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Note("Fajr, where the night ends, is worked out from where you are.")
        Text(
            Places.nameOf(config.latitude, config.longitude) ?: "${config.latitude}, ${config.longitude}",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        Text(
            "Sun angle ${config.sunAngleDegrees} deg" + if (config.fajrOffsetMinutes != 0) ", adjusted ${config.fajrOffsetMinutes} min" else "",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonRow {
            SecondaryButtonSmall("Use my location") {
                val has = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (has) useLocation() else askPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            SecondaryButtonSmall("Pick a city") { picking = true }
        }
        status?.let { Note(it) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Hide coordinates and angle" else "Coordinates and angle")
        }
        if (advanced) {
            ValidatedField("Latitude", config.latitude.toString(), "-66 to 66", { it.trim().toDoubleOrNull()?.takeIf { v -> v in -66.0..66.0 } }) { onChange(config.copy(latitude = it)) }
            ValidatedField("Longitude", config.longitude.toString(), "-180 to 180", { it.trim().toDoubleOrNull()?.takeIf { v -> v in -180.0..180.0 } }) { onChange(config.copy(longitude = it)) }
            ValidatedField("Sun angle for fajr (degrees)", config.sunAngleDegrees.toString(), "10 to 25 (19 in Morocco, 18 in much of Europe)", { it.trim().toDoubleOrNull()?.takeIf { v -> v in 10.0..25.0 } }) { onChange(config.copy(sunAngleDegrees = it)) }
            ValidatedField("Fajr adjustment in minutes", config.fajrOffsetMinutes.toString(), "-60 to 60; compare with your prayer app", { it.trim().toIntOrNull()?.takeIf { n -> n in -60..60 } }) { onChange(config.copy(fajrOffsetMinutes = it)) }
        }
    }

    if (picking) {
        CityPicker(onDismiss = { picking = false }) { place ->
            picking = false
            status = null
            onChange(config.copy(latitude = place.latitude, longitude = place.longitude, sunAngleDegrees = Places.angleFor(place.country)))
        }
    }
}

private fun round4(v: Double) = Math.round(v * 10_000) / 10_000.0

/** Search by city or country; the list is grouped by country. */
@Composable
private fun CityPicker(onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = remember(query) { Places.search(query) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = CardShape,
        title = { Text("Pick your city") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("City or country") }, singleLine = true, shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (found.isEmpty()) item { Note("Not in the list: close this and use \"Coordinates and angle\".") }
                    found.groupBy { it.country }.forEach { (country, cities) ->
                        item(key = "h-$country") {
                            Text(
                                country,
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        cities.forEach { place ->
                            item(key = place.name) {
                                Text(
                                    place.city,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { onPick(place) }.padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

