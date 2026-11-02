package app.lockmein.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lockmein.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, SETUP, LISTS, BROWSERS, WINDOW, EMERGENCY, REPORT, SETTINGS, REMOVE }

/** Where a screen can send the person. */
val LocalNavigate = compositionLocalOf<(Screen) -> Unit> { {} }

@Composable
fun Navigation(navigate: (Screen) -> Unit, content: @Composable () -> Unit) = CompositionLocalProvider(LocalNavigate provides navigate, content = content)

/** A page with a title, an optional back arrow, and scrolling content. */
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) TextButton(onClick = onBack) { Text("< Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
fun Problem(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** The sentence an outcome came to, in the colour that fits. */
@Composable
fun OutcomeText(outcome: Outcome?) = when (outcome) {
    is Outcome.Done -> Text(outcome.message, color = MaterialTheme.colorScheme.primary)
    is Outcome.Refused -> Text(outcome.reason, color = MaterialTheme.colorScheme.error)
    null -> Unit
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun MenuButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }

/** Small buttons for rows: the chosen one is filled, the others outlined. */
@Composable
fun androidx.compose.foundation.layout.RowScope.MenuButtonSmall(label: String, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }

@Composable
fun androidx.compose.foundation.layout.RowScope.SecondaryButtonSmall(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }

@Composable
fun ButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Runs slow work (the network, the DNS check, the policy pass) off the main thread and hands the result back. */
fun <T> CoroutineScope.background(work: () -> T, done: (T) -> Unit) {
    launch {
        val result = withContext(Dispatchers.IO) { work() }
        done(result)
    }
}
