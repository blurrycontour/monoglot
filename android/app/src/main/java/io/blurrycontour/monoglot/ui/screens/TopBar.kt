package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One app bar for every tab. Previously the library used a compact bar while
 * the other tabs used the large variant, so switching tabs visibly changed the
 * header height. Transparent so the theme's background art shows through.
 */
/**
 * Remembers the scroll behaviour every tab shares: the bar retreats as content
 * moves up and comes back on the first upward scroll.
 *
 * Without one, the transparent bar sat over the scrolling content and sliced
 * whatever passed beneath it — visibly, since the bar paints nothing of its own
 * for the text to disappear behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberTabBarBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonoglotTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(Modifier.size(21.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        actions = actions,
        // Both ends of the scroll transition, not just one.
        //
        // The bar lerps containerColor -> scrolledContainerColor as it
        // collapses, and Color.Transparent is black at zero alpha: leaving the
        // scrolled end at its default (an opaque light surfaceContainer) meant
        // every collapse and expand ran that interpolation through
        // part-opaque black, which flashed once in each direction. Equal ends
        // means no transition at all, which is what a transparent bar over the
        // theme's own background wants.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}
