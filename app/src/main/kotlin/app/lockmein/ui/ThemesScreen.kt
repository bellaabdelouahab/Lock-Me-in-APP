package app.lockmein.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lockmein.Outcome
import app.lockmein.Personalize

/** Themes and wallpaper: always his to change. Shows why the Themes app may be missing and sets a wallpaper even when it is. */
@Composable
fun ThemesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { Personalize(context) }
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val state = remember(version) { p.state() }
    var target by remember { mutableStateOf(Target.BOTH) }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.background({ runCatching { p.setWallpaper(uri, home = target != Target.LOCK, lock = target != Target.HOME) } }) { r ->
            outcome = r.fold({ Outcome.Done("Wallpaper set.") }, { Outcome.Refused("Could not set it: ${it.message}") })
        }
    }

    Page("Themes & wallpaper", onBack) {
        InfoCard {
            Text("Themes app", style = MaterialTheme.typography.titleSmall)
            Text(p.describe(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutcomeText(outcome)
        if (state != Personalize.State.MISSING) {
            SecondaryButton("Open Themes", {
                val i = p.themesIntent()
                outcome = if (i == null) Outcome.Refused("HyperOS gives the Themes app no screen to open.")
                else runCatching { context.startActivity(i); null }.getOrElse { Outcome.Refused("HyperOS refused to open Themes: ${it.message}") }
            })
            SecondaryButton("Show and switch on Themes now", {
                scope.background({ p.restore() }) { changed ->
                    version++
                    outcome = Outcome.Done(if (changed.isEmpty()) "Nothing to undo from here." else changed.joinToString())
                }
            })
        }

        Heading("Wallpaper")
        InfoCard {
            Note("Works without the Themes app: pick a photo and where it goes.")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Target.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = target == t,
                        onClick = { target = t },
                        shape = SegmentedButtonDefaults.itemShape(i, Target.entries.size),
                    ) { Text(t.label) }
                }
            }
            PrimaryButton("Choose a photo") { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            p.wallpaperPickerIntent()?.let { i -> SecondaryButton("Phone's wallpaper picker", { runCatching { context.startActivity(i) } }) }
        }
        Note("Lock Me In never closes Themes, wallpapers or the always-on display. HyperOS hides Themes and the Mi Account page on any phone with a device owner (other device-owner apps hit the same); the pass undoes the hiding where Android allows it.")
    }
}

private enum class Target(val label: String) { BOTH("Both screens"), HOME("Home"), LOCK("Lock") }
