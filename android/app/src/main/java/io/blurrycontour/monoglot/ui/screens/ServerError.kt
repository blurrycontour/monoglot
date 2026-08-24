package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Jumps to the server fields in Settings.
 *
 * Provided by the activity, because only it owns the pager. An unreachable
 * server is nearly always a wrong address or a stale token, and making the
 * reader find their own way to the one screen that can fix it is the whole
 * difference between a dead end and a prompt.
 */
val LocalOpenServerSettings = compositionLocalOf<() -> Unit> { {} }

/**
 * The one way this app reports that the server is unreachable.
 *
 * Every screen used to do it differently — a full-page state here, a card
 * there, a bare line of text somewhere else — and only one of them offered a
 * way out. Retry and Configure are the only two things that ever help.
 */
@Composable
fun ServerErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val openSettings = LocalOpenServerSettings.current

    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = if (compact) 20.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
            Icon(
                Icons.Default.CloudOff, null, Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
        }
        Text("Cannot reach the server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
            OutlinedButton(onClick = openSettings) {
                Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Configure")
            }
        }
    }
}
