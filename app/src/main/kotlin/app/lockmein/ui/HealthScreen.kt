package app.lockmein.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.ConfigStore
import app.lockmein.Health
import app.lockmein.HealthItem
import app.lockmein.PolicyRunner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AT = DateTimeFormatter.ofPattern("HH:mm:ss")

/** The verdict as a solid block: green VERIFIED, red with the count of problems, or still asking Android. Tapping it opens the full check. */
@Composable
fun HealthBadge(items: List<HealthItem>?, onClick: (() -> Unit)?) {
    val problems = items?.count { !it.ok } ?: 0
    val (color, icon, title, detail) = when {
        items == null -> Quad(Palette.Muted, Icons.Filled.GppMaybe, "Checking", "Reading back what Android really enforces.")
        BuildConfig.PREVIEW -> Quad(Palette.Open, Icons.Filled.GppMaybe, "Not protecting", "Preview build: it shows the screens only. Nothing is enforced.")
        problems == 0 -> Quad(Palette.Day, Icons.Filled.GppGood, "Verified", "${items.size}/${items.size} checks read back from Android pass.")
        else -> Quad(Palette.Locked, Icons.Filled.GppBad, "$problems problem${if (problems > 1) "s" else ""}", items.first { !it.ok }.let { "${it.title}: ${it.detail}" })
    }
    val text = if (color == Palette.Open) Palette.Ink else Palette.Paper
    Row(
        Modifier.fillMaxWidth().background(color).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (items == null) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = text)
        else Icon(icon, null, tint = text, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = text.copy(alpha = 0.85f), maxLines = 2)
        }
        if (onClick != null) Text("\u2192", style = MaterialTheme.typography.headlineSmall, color = text)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Every check, line by line, with a repair button when one fails. */
@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HealthItem>?>(null) }
    var checkedAt by remember { mutableStateOf<Instant?>(null) }
    var repairing by remember { mutableStateOf(false) }
    var repairNote by remember { mutableStateOf<String?>(null) }
    fun recheck() {
        items = null
        scope.background({ Health(context).check() }) { items = it; checkedAt = Instant.now() }
    }
    LaunchedEffect(Unit) { recheck() }

    Page("Is it working?", onBack) {
        HealthBadge(items, null)
        Note("Each line is read back from Android itself, not from the app's own notes, so a rule that was undone shows here.")
        val list = items
        if (list == null) CheckRow("Asking Android", CheckState.CHECKING, "Reading what is really in force...")
        else list.forEach { CheckRow(it.title, if (it.ok) CheckState.PASS else CheckState.FAIL, it.detail) }
        checkedAt?.let { Text("Checked at ${AT.format(it.atZone(ZoneId.systemDefault()))}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, fontWeight = FontWeight.Medium) }
        repairNote?.let { Problem(it) }
        if (list != null && list.any { !it.ok } && !BuildConfig.PREVIEW && ConfigStore(context).protectionActive) {
            MenuButton(if (repairing) "Repairing..." else "Repair now", enabled = !repairing) {
                repairing = true
                repairNote = null
                scope.background({
                    runCatching { ConfigStore(context).load()?.let { PolicyRunner(context).run(it) } }.exceptionOrNull()?.message
                }) { error ->
                    repairing = false
                    if (error != null) repairNote = "Repair failed: $error"
                    recheck()
                }
            }
        }
        SecondaryButton("Check again", { recheck() }, enabled = list != null)
    }
}
