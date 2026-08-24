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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonoglotTopBar(
    title: String,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
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
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}
