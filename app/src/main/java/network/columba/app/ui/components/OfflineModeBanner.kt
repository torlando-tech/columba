package network.columba.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.reticulum.model.NetworkStatus

/**
 * Pure visibility function — determines if the offline banner should be shown.
 * Shown for SHUTDOWN (user intentionally stopped) and ERROR (service crashed/failed).
 */
internal fun shouldShowOfflineBanner(
    networkStatus: NetworkStatus,
    hasCompletedOnboarding: Boolean = true,
): Boolean = hasCompletedOnboarding && (networkStatus is NetworkStatus.SHUTDOWN || networkStatus is NetworkStatus.ERROR)

/**
 * Persistent thin banner shown when the Reticulum service is offline.
 * Displays across all screens at the top of the app, above the NavHost.
 */
@Composable
fun OfflineModeBanner(
    networkStatus: NetworkStatus,
    isRestarting: Boolean,
    onReconnect: () -> Unit,
    hasCompletedOnboarding: Boolean = true,
) {
    AnimatedVisibility(
        visible = shouldShowOfflineBanner(networkStatus, hasCompletedOnboarding) || isRestarting,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val reconnectingDescription = stringResource(R.string.offline_banner_reconnecting_description)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when {
                            isRestarting -> stringResource(R.string.offline_banner_reconnecting)
                            networkStatus is NetworkStatus.ERROR -> stringResource(R.string.offline_banner_error)
                            else -> stringResource(R.string.offline_banner_shutdown)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(16.dp)
                                .semantics { contentDescription = reconnectingDescription },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onReconnect) {
                        Text(stringResource(R.string.offline_banner_reconnect))
                    }
                }
            }
        }
    }
}
