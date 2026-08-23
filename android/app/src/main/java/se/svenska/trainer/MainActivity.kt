package se.svenska.trainer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Insights
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.svenska.trainer.data.Graph
import se.svenska.trainer.ui.screens.LibraryScreen
import se.svenska.trainer.ui.screens.PlayerScreen
import se.svenska.trainer.ui.screens.SettingsScreen
import se.svenska.trainer.ui.screens.SystemScreen
import se.svenska.trainer.ui.screens.WordsScreen
import se.svenska.trainer.ui.theme.SvenskaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        requestNotificationPermission()
        enableEdgeToEdge()
        setContent {
            SvenskaTheme { App() }
        }
    }

    /**
     * Android 13+ gates the media notification behind a runtime permission.
     * Without it there are no lock-screen controls, which is most of the point
     * of background playback.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("library", "Listen", Icons.Default.Headphones),
    Tab("words", "Words", Icons.Default.Style),
    Tab("system", "System", Icons.Default.Insights),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun App() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // The player is immersive: it takes the whole screen, with no bottom bar
    // competing with the transport controls.
    val showBar = route in TABS.map { it.route }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.padding(
                bottom = if (showBar) padding.calculateBottomPadding() else 0.dp,
            ),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable("library") {
                LibraryScreen(onOpen = { nav.navigate("player/$it") })
            }
            composable("words") { WordsScreen() }
            composable("system") { SystemScreen() }
            composable("settings") { SettingsScreen() }
            composable(
                "player/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.IntType }),
                enterTransition = { slideInVertically(tween(260)) { it / 6 } + fadeIn(tween(260)) },
                popExitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
            ) { entry ->
                PlayerScreen(
                    itemId = entry.arguments?.getInt("itemId") ?: 0,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
