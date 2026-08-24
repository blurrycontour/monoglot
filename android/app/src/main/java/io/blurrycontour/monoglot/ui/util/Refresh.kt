package io.blurrycontour.monoglot.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Reload a tab's data whenever it becomes the thing being looked at.
 *
 * The four tabs are pages of one pager, so they are all composed at once and
 * their view models load exactly once, at construction. Anything that happened
 * since — an episode finished, a word looked up — stayed invisible until the
 * app was killed and reopened, which is not a refresh mechanism.
 *
 * Two triggers, because there are two ways a screen comes back into view:
 * swiping to its page, and the app returning to the foreground.
 */
@Composable
fun RefreshWhenVisible(visible: Boolean, onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)

    LaunchedEffect(visible) { if (visible) refresh() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, visible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && visible) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
