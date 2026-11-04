package app.lockmein.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.Release
import app.lockmein.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This version, the newest one on GitHub, and a button to install it. [onlyIfNewer]: the home screen's banner, shown only when
 * there is something to install.
 */
@Composable
fun UpdateCard(onlyIfNewer: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<Release?>(null) }
    var checked by remember { mutableStateOf(false) }
    var checks by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var note by remember { mutableStateOf(if (onlyIfNewer) null else Updater.lastResult(context)) }
    LaunchedEffect(checks) {
        checked = false
        latest = withContext(Dispatchers.IO) { Updater.latest() }
        checked = true
    }
    val newer = Updater.isNewer(latest)
    if (onlyIfNewer && !newer) return

    InfoCard {
        if (onlyIfNewer) IconCircle(Icons.Filled.SystemUpdate, Palette.Bar, 40.dp)
        Text(if (newer) "Version ${latest!!.name} is ready" else "Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Note(
            when {
                !checked -> "Checking GitHub for a newer version..."
                latest == null -> "No release found on GitHub (none published yet, or you are offline). This is version ${BuildConfig.VERSION_NAME} (${Updater.kind} build)."
                newer -> latest!!.notes.ifBlank { "A newer build of the ${Updater.kind} kind." } + "  You have ${BuildConfig.VERSION_NAME}."
                else -> "This is the newest version (${Updater.kind} build)."
            },
        )
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Palette.Bar, trackColor = Palette.Line) }
        if (newer) MenuButton(progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Update now", enabled = progress == null) {
            progress = 0f
            note = null
            scope.launch {
                val error = withContext(Dispatchers.IO) { Updater.install(context) { progress = it } }
                note = error ?: "Installing. The app restarts on the new version; protection keeps running."
                if (error != null) progress = null
            }
        } else if (!onlyIfNewer) SecondaryButton("Check again", { checks++ }, enabled = checked)
        note?.let { Note(it) }
    }
}

