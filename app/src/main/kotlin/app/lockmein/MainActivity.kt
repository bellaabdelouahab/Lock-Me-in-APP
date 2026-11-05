package app.lockmein

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.ui.AboutScreen
import app.lockmein.ui.BrowsersScreen
import app.lockmein.ui.CardShape
import app.lockmein.ui.EmergencyScreen
import app.lockmein.ui.HealthScreen
import app.lockmein.ui.HomeScreen
import app.lockmein.ui.ListsScreen
import app.lockmein.ui.LocalOpenDrawer
import app.lockmein.ui.LockTheme
import app.lockmein.ui.Navigation
import app.lockmein.ui.Palette
import app.lockmein.ui.RemoveScreen
import app.lockmein.ui.ReportScreen
import app.lockmein.ui.Screen
import app.lockmein.ui.SettingsScreen
import app.lockmein.ui.SetupScreen
import app.lockmein.ui.WindowScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Recents.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LockApp(intent?.getStringExtra("screen"))
                }
            }
        }
    }
}

private fun intent(context: android.content.Context): android.content.Intent? =
    (context as? android.app.Activity)?.intent

private data class DrawerItem(
    val title: String,
    val screen: Screen,
    val icon: ImageVector,
)

@Composable
private fun LockApp(requested: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 840

    val first = remember {
        if (BuildConfig.PREVIEW && intent(context)?.getBooleanExtra("demo", false) == true) {
            PreviewDemo.seed(context)
        }
        requested?.let { name ->
            when (name.uppercase()) {
                "HOME" -> Screen.HOME
                "HEALTH" -> Screen.HEALTH
                "WINDOW", "REQUESTS" -> Screen.WINDOW
                "LISTS", "APPS" -> Screen.LISTS
                "BROWSERS" -> Screen.BROWSERS
                "EMERGENCY", "FRIEND_CODE" -> Screen.EMERGENCY
                "REPORT" -> Screen.REPORT
                "SETTINGS" -> Screen.SETTINGS
                "REMOVE" -> Screen.REMOVE
                "SETUP" -> Screen.SETUP
                "ABOUT" -> Screen.ABOUT
                else -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        } ?: if (ConfigStore(context).protectionActive) Screen.HOME else Screen.SETUP
    }

    var screen by rememberSaveable { mutableStateOf(first) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val home = { screen = Screen.HOME }
    val selectScreen: (Screen) -> Unit = { dest ->
        screen = dest
        scope.launch { drawerState.close() }
    }

    val destinations = listOf(
        DrawerItem("Home", Screen.HOME, Icons.Filled.Home),
        DrawerItem("Apps", Screen.LISTS, Icons.Filled.Apps),
        DrawerItem("Requests", Screen.WINDOW, Icons.Filled.EventAvailable),
        DrawerItem("Friend's code", Screen.EMERGENCY, Icons.Filled.Key),
        DrawerItem("Is it working?", Screen.HEALTH, Icons.Filled.CheckCircle),
        DrawerItem("Settings", Screen.SETTINGS, Icons.Filled.Settings),
    )

    @Composable
    fun DrawerHeader() {
        val store = remember { ConfigStore(context) }
        val isProtected = store.protectionActive && store.setupComplete
        val dotColor = if (isProtected) Palette.Ok else if (store.protectionActive) Palette.Attention else MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Lock Me In",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                if (isProtected) "Protection active" else if (store.protectionActive) "Setup in progress" else "Protection inactive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    fun DrawerItems() {
        destinations.forEach { item ->
            val isSelected = screen == item.screen
            NavigationDrawerItem(
                label = { Text(item.title, style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(item.icon, contentDescription = null) },
                selected = isSelected,
                onClick = { selectScreen(item.screen) },
                shape = CardShape,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContainerColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

        val isAboutSelected = screen == Screen.ABOUT
        NavigationDrawerItem(
            label = {
                Column {
                    Text("About", style = MaterialTheme.typography.labelLarge)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            selected = isAboutSelected,
            onClick = { selectScreen(Screen.ABOUT) },
            shape = CardShape,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                unselectedContainerColor = Color.Transparent,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }

    @Composable
    fun MainContent() {
        CompositionLocalProvider(
            LocalOpenDrawer provides { scope.launch { drawerState.open() } },
        ) {
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
                    Screen.HEALTH -> HealthScreen(onBack = home)
                    Screen.ABOUT -> AboutScreen(onBack = home)
                }
            }
        }
    }

    if (isWideScreen) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    DrawerHeader()
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DrawerItems()
                }
            },
        ) {
            MainContent()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    DrawerHeader()
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DrawerItems()
                }
            },
        ) {
            MainContent()
        }
    }
}
