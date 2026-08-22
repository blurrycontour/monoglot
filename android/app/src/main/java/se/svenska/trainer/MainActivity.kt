package se.svenska.trainer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.svenska.trainer.data.Graph
import se.svenska.trainer.ui.screens.LibraryScreen
import se.svenska.trainer.ui.screens.PlayerScreen
import se.svenska.trainer.ui.screens.SettingsScreen
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

@Composable
fun App() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpen = { nav.navigate("player/$it") },
                onWords = { nav.navigate("words") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "player/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.IntType }),
        ) { entry ->
            PlayerScreen(
                itemId = entry.arguments?.getInt("itemId") ?: 0,
                onBack = { nav.popBackStack() },
            )
        }
        composable("words") { WordsScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
