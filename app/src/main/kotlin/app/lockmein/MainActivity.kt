package app.lockmein

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lockmein.ui.BrowsersScreen
import app.lockmein.ui.EmergencyScreen
import app.lockmein.ui.HomeScreen
import app.lockmein.ui.ListsScreen
import app.lockmein.ui.Navigation
import app.lockmein.ui.RemoveScreen
import app.lockmein.ui.ReportScreen
import app.lockmein.ui.Screen
import app.lockmein.ui.SettingsScreen
import app.lockmein.ui.SetupScreen
import app.lockmein.ui.WindowScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { LockApp() }
            }
        }
    }
}

@Composable
private fun LockApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // A first launch goes straight into the guided setup; after that the home screen.
    var screen by rememberSaveable { mutableStateOf(if (ConfigStore(context).protectionActive) Screen.HOME else Screen.SETUP) }
    val home = { screen = Screen.HOME }
    Navigation(navigate = { screen = it }) {
        when (screen) {
            Screen.HOME -> HomeScreen()
            Screen.SETUP -> SetupScreen(onExit = home)
            Screen.LISTS -> ListsScreen(onBack = home)
            Screen.BROWSERS -> BrowsersScreen(onBack = home)
            Screen.WINDOW -> WindowScreen(onBack = home)
            Screen.EMERGENCY -> EmergencyScreen(onBack = home)
            Screen.REPORT -> ReportScreen(onBack = home)
            Screen.SETTINGS -> SettingsScreen(onBack = home)
            Screen.REMOVE -> RemoveScreen(onBack = home, onRemoved = home)
        }
    }
}
