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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import se.svenska.trainer.player.PlaybackHolder
import se.svenska.trainer.ui.screens.LibraryScreen
import se.svenska.trainer.ui.screens.MiniPlayerHost
import se.svenska.trainer.ui.screens.UpdateGate
import se.svenska.trainer.ui.screens.PlayerScreen
import se.svenska.trainer.ui.screens.SettingsScreen
import se.svenska.trainer.ui.screens.SystemScreen
import se.svenska.trainer.ui.screens.WordsScreen
import se.svenska.trainer.ui.theme.MonoglotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        requestNotificationPermission()
        rescheduleReminders()
        enableEdgeToEdge()
        setContent {
            val themeId by Graph.repository.settings.themeFlow
                .collectAsState(initial = "black")
            val accentId by Graph.repository.settings.accentFlow
                .collectAsState(initial = "default")
            MonoglotTheme(themeId = themeId, accentId = accentId) { App() }
        }
    }

    /** Alarms are dropped on app update as well as on reboot. */
    private fun rescheduleReminders() {
        val ctx = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val store = se.svenska.trainer.reminders.ReminderStore(ctx)
            se.svenska.trainer.reminders.ReminderScheduler.rescheduleAll(ctx, store.all())
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
    val now by PlaybackHolder.now.collectAsState()
    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    // The player is immersive: it takes the whole screen, with no bottom bar
    // competing with the transport controls.
    val showBar = route == "tabs"

    // Reconnect on launch so the mini player reappears if playback survived
    // the activity, which it does: the service outlives the UI.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PlaybackHolder.connect(context)
        // The service may have been torn down between launches, in which case
        // there is nothing to show unless the last item is restored explicitly.
        PlaybackHolder.restoreLastIfIdle(context)
    }

    UpdateGate()

    Scaffold(
        // contentColorFor(Transparent) is Unspecified, which leaves
        // LocalContentColor at its black default. Every piece of unstyled text
        // on the screen would otherwise be black regardless of theme.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                // The bar owns its own background; without this the container
                // behind the mini player painted an opaque strip around it.
                Column(Modifier.background(Color.Transparent)) {
                    MiniPlayerHost(
                        visible = true,
                        now = now,
                        onExpand = { nav.navigate("player/${now.itemId}") },
                    )
                    Spacer(Modifier.height(4.dp))
                NavigationBar {
                    TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "tabs",
            modifier = Modifier.padding(
                bottom = if (showBar) padding.calculateBottomPadding() else 0.dp,
            ),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable("tabs") {
                TabPager(
                    pagerState = pagerState,
                    onOpenItem = { nav.navigate("player/$it") },
                )
            }
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

/**
 * The four tabs as swipeable pages. Reaching Settings previously meant three
 * separate taps with no gesture at all; a pager is what the bottom bar
 * implies.
 */
@Composable
private fun TabPager(pagerState: PagerState, onOpenItem: (Int) -> Unit) {
    HorizontalPager(
        state = pagerState,
        // Each page keeps its own scroll and view model, so swiping back and
        // forth does not reset what you were looking at.
        beyondViewportPageCount = 1,
    ) { page ->
        when (TABS[page].route) {
            "library" -> LibraryScreen(onOpen = onOpenItem)
            "words" -> WordsScreen()
            "system" -> SystemScreen()
            else -> SettingsScreen()
        }
    }
}
