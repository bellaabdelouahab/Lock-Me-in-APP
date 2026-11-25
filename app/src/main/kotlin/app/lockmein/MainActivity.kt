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
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Language
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import app.lockmein.ui.PillShape
import app.lockmein.ui.StatusBadge
import app.lockmein.ui.StatusKind
import app.lockmein.ui.EmergencyScreen
import app.lockmein.ui.HealthScreen
import app.lockmein.ui.HomeScreen
import app.lockmein.ui.ListsScreen
import app.lockmein.ui.LocalOpenDrawer
import app.lockmein.ui.LocalTopLevel
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

/** The side bar in groups, each opened by a quiet label. Names say what the page does, the same on the page's own title. */
private data class DrawerGroup(val label: String?, val items: List<DrawerItem>)

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
                "EMERGENCY", "FRIEND_CODE", "HOLDER_CODE", "CODE" -> Screen.EMERGENCY
                "REPORT" -> Screen.REPORT
                "SETTINGS" -> Screen.SETTINGS
                "REMOVE" -> Screen.REMOVE
                "SETUP" -> Screen.SETUP
                "ABOUT" -> Screen.ABOUT
                "PHONE_USE", "USE" -> Screen.PHONE_USE
                "FILTER", "REVIEW" -> Screen.FILTER
                "THEMES", "WALLPAPER" -> Screen.THEMES
                "REMOTE", "MCP" -> Screen.REMOTE
                else -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        } ?: if (ConfigStore(context).protectionActive) Screen.HOME else Screen.SETUP
    }

    var screen by rememberSaveable { mutableStateOf(first) }
    // Asked to start with the side bar open (the screenshots do): it starts open, without the slide.
    val shouldOpenDrawer = remember { intent(context)?.getBooleanExtra("openDrawer", false) == true }
    val drawerState = rememberDrawerState(initialValue = if (shouldOpenDrawer) DrawerValue.Open else DrawerValue.Closed)

    val home = { screen = Screen.HOME }
    val selectScreen: (Screen) -> Unit = { dest ->
        screen = dest
        scope.launch { drawerState.close() }
    }

    // Six entries; a page with one job sits inside the entry it belongs to (Browsers in App rules, Friend's code in Requests,
    // Look, Connect Claude and About in Settings) and the three ways of looking back share Reports as tabs.
    val groups = listOf(
        DrawerGroup(null, listOf(
            DrawerItem("Home", Screen.HOME, Icons.Filled.Home),
            DrawerItem("App rules", Screen.LISTS, Icons.Filled.Apps),
            DrawerItem("Screen filter", Screen.FILTER, Icons.Filled.Shield),
            DrawerItem("Requests", Screen.WINDOW, Icons.Filled.EventAvailable),
            DrawerItem("Reports", Screen.HEALTH, Icons.Filled.Insights),
            DrawerItem("Settings", Screen.SETTINGS, Icons.Filled.Settings),
        )),
    )
    val reportTabs = listOf(Screen.HEALTH, Screen.REPORT, Screen.PHONE_USE)
    val topLevel = groups.flatMap { g -> g.items.map { it.screen } }.toSet() + reportTabs
    // Where a page inside an entry goes back to.
    val parent = mapOf(
        Screen.BROWSERS to Screen.LISTS,
        Screen.EMERGENCY to Screen.WINDOW,
        Screen.THEMES to Screen.SETTINGS,
        Screen.REMOTE to Screen.SETTINGS,
        Screen.ABOUT to Screen.SETTINGS,
        Screen.REMOVE to Screen.SETTINGS,
    )
    val up = { screen = parent[screen] ?: Screen.HOME }

    @Composable
    fun DrawerHeader() {
        val store = remember { ConfigStore(context) }
        val isProtected = store.protectionActive && store.setupComplete
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Lock Me In",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusBadge(
                kind = if (isProtected) StatusKind.OK else StatusKind.ATTENTION,
                label = if (isProtected) "Protection active" else if (store.protectionActive) "Setup in progress" else "Protection inactive",
            )
        }
    }

    @Composable
    fun DrawerItems() {
        groups.forEach { group ->
        if (group.label != null) {
            Text(
                group.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
            )
        }
        group.items.forEach { item ->
            val isSelected = item.screen == screen || item.screen == parent[screen] || (item.screen == Screen.HEALTH && screen in reportTabs)
            NavigationDrawerItem(
                label = { Text(item.title, style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(item.icon, contentDescription = null) },
                selected = isSelected,
                onClick = { selectScreen(item.screen) },
                shape = PillShape,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContainerColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
        }

    }

    /** Always scrolls a little past the last item, even when everything fits, so the bottom items can be pulled up to the thumb. */
    @Composable
    fun DrawerBody() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxHeight
            // Every time the side bar opens it starts at the top.
            val scroll = rememberScrollState()
            LaunchedEffect(drawerState.targetValue) { if (drawerState.targetValue == DrawerValue.Open) scroll.scrollTo(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding()) {
                Column(Modifier.heightIn(min = room + 96.dp)) {
                    DrawerHeader()
                    DrawerItems()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    fun MainContent() {
        CompositionLocalProvider(
            LocalOpenDrawer provides { scope.launch { drawerState.open() } },
            LocalTopLevel provides (screen in topLevel),
            app.lockmein.ui.LocalPageTabs provides (if (screen in reportTabs) app.lockmein.ui.PageTabs(
                title = "Reports",
                labels = listOf("Health", "Week", "App usage"),
                selected = reportTabs.indexOf(screen),
                onSelect = { screen = reportTabs[it] },
            ) else null),
        ) {
            Navigation(navigate = { screen = it }) {
                when (screen) {
                    Screen.HOME -> HomeScreen()
                    Screen.SETUP -> SetupScreen(onExit = home)
                    Screen.LISTS -> ListsScreen(onBack = home)
                    Screen.BROWSERS -> BrowsersScreen(onBack = up)
                    Screen.WINDOW -> WindowScreen(onBack = home)
                    Screen.EMERGENCY -> EmergencyScreen(onBack = up)
                    Screen.REPORT -> ReportScreen(onBack = home)
                    Screen.SETTINGS -> SettingsScreen(onBack = home)
                    Screen.REMOVE -> RemoveScreen(onBack = up, onRemoved = home)
                    Screen.HEALTH -> HealthScreen(onBack = home)
                    Screen.ABOUT -> AboutScreen(onBack = up)
                    Screen.PHONE_USE -> app.lockmein.ui.PhoneUseScreen(onBack = home)
                    Screen.FILTER -> app.lockmein.ui.FilterScreen(onBack = home)
                    Screen.THEMES -> app.lockmein.ui.ThemesScreen(onBack = up)
                    Screen.REMOTE -> app.lockmein.ui.RemoteScreen(onBack = up)
                }
            }
        }
    }

    if (isWideScreen) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    DrawerBody()
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
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    DrawerBody()
                }
            },
        ) {
            MainContent()
        }
    }
}
