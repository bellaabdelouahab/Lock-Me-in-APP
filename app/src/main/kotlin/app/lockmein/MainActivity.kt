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
import androidx.compose.runtime.remember
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
    override fun onResume() {
        super.onResume()
        Recents.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The look follows the schedule: paper by day, dimmed from the night's start. Checked every minute.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    app.lockmein.ui.Palette.night = isNight()
                    kotlinx.coroutines.delay(60_000)
                }
            }
            app.lockmein.ui.LockTheme {
                Surface(Modifier.fillMaxSize()) { LockApp(intent?.getStringExtra("screen")) }
            }
        }
    }
}

private fun android.content.Context.isNight(): Boolean {
    val config = ConfigStore(this).load()
    if (config != null) return runCatching { Rules.schedule(config).phaseAt(TrustedClock(this).now()) == app.lockmein.core.Phase.NIGHT }.getOrDefault(false)
    val hour = java.time.LocalTime.now().hour
    return hour >= 22 || hour < 6
}

private fun intent(context: android.content.Context): android.content.Intent? = (context as? android.app.Activity)?.intent

@Composable
private fun LockApp(requested: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // A first launch goes straight into the guided setup; after that the home screen.
    // The preview build can be opened on a screen with sample data (adb shell am start --es screen WINDOW --ez demo true), which is how the screens are photographed.
    val first = remember {
        if (BuildConfig.PREVIEW && intent(context)?.getBooleanExtra("demo", false) == true) PreviewDemo.seed(context)
        requested?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: if (ConfigStore(context).protectionActive) Screen.HOME else Screen.SETUP
    }
    var screen by rememberSaveable { mutableStateOf(first) }
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
            Screen.HEALTH -> app.lockmein.ui.HealthScreen(onBack = home)
        }
    }
}
