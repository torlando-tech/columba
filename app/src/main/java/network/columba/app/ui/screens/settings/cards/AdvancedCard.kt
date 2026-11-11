package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import network.columba.app.BuildConfig
import network.columba.app.R
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.ui.components.LocalCapabilities

/**
 * Advanced settings card — houses power-user toggles and options that most users should
 * leave alone. Sits between the RNode Flasher and About cards so it's findable but
 * out of the way.
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param transportNodeEnabled Whether transport node mode is enabled (forwards mesh traffic)
 * @param onTransportNodeToggle Callback when transport node toggle is changed
 * @param threadsEnabled Threads P2 feature flag (`threads_enabled`, default off).
 * @param onThreadsToggle Callback when the Threads toggle is changed.
 * @param shareInstanceHostingEnabled Persisted "Share Instance" toggle state
 *   (python backend only; capability-gated). True means the daemon will publish itself
 *   as an RNS shared instance on TCP 37428 after the next restart.
 * @param onShareInstanceHostingToggle Callback when Share Instance toggle is changed.
 *   Persists the preference but does NOT trigger a service restart — the user must
 *   explicitly Apply & Restart from elsewhere to avoid surprise outages.
 * @param shareInstanceHostingPending True when the persisted preference differs from
 *   the value the running daemon was constructed with. Drives the "(pending restart)"
 *   hint and inline Restart button under the toggle.
 * @param announceFilterEnabled Persisted "Filter Announces" master toggle
 *   (RNS 1.6.0 `[filter]`; python backend only, capability-gated).
 * @param announceFilterPending True when any announce-filter preference
 *   diverges from the config the running daemon was built with. Drives the
 *   pending-restart hint + inline Restart button under the filter toggle.
 * @param onAnnounceFilterToggle Callback when the Filter Announces toggle changes.
 *   Persist-only — applying needs a restart, like Share Instance.
 * @param onOpenAnnounceFilter Callback when the Filter Options button is tapped;
 *   navigates to the Announce Filtering screen.
 * @param onRestartReticulum Invoked when the user taps the inline Restart button next
 *   to the pending-changes hint. Plumbs through to `SettingsViewModel.restartService()`.
 * @param isRestarting True while a service restart is in flight; disables the
 *   inline Restart button and shows a small progress spinner instead of the icon.
 */
@Composable
fun AdvancedCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    transportNodeEnabled: Boolean = true,
    onTransportNodeToggle: (Boolean) -> Unit = {},
    shareInstanceHostingEnabled: Boolean = false,
    onShareInstanceHostingToggle: (Boolean) -> Unit = {},
    shareInstanceHostingPending: Boolean = false,
    onRestartReticulum: () -> Unit = {},
    announceFilterEnabled: Boolean = true,
    announceFilterPending: Boolean = false,
    onAnnounceFilterToggle: (Boolean) -> Unit = {},
    onOpenAnnounceFilter: () -> Unit = {},
    isRestarting: Boolean = false,
    crashReportingEnabled: Boolean = false,
    onCrashReportingToggle: (Boolean) -> Unit = {},
    threadsEnabled: Boolean = false,
    onThreadsToggle: (Boolean) -> Unit = {},
) {
    val canHostShareInstance = LocalCapabilities.current.performance.shareInstanceHosting
    val canFilterAnnounces = LocalCapabilities.current.performance.announceIngressFilter
    CollapsibleSettingsCard(
        title = stringResource(R.string.settings_advanced_title),
        icon = Icons.Default.Tune,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Transport Node toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.settings_advanced_transport_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Switch(
                checked = transportNodeEnabled,
                onCheckedChange = onTransportNodeToggle,
                modifier = Modifier.testTag("transport_node_toggle"),
            )
        }
        Text(
            text = stringResource(R.string.settings_advanced_transport_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Share Instance toggle (python backend only; capability-gated)
        if (canHostShareInstance) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_advanced_share_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = shareInstanceHostingEnabled,
                    onCheckedChange = onShareInstanceHostingToggle,
                )
            }
            Text(
                text = stringResource(R.string.settings_advanced_share_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shareInstanceHostingPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_advanced_restart_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = onRestartReticulum,
                        enabled = !isRestarting,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        if (isRestarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_advanced_restarting))
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_advanced_restart))
                        }
                    }
                }
            }
        }

        // Announce ingress filter (RNS 1.6.0 [filter]; python backend only;
        // capability-gated). Persist-only like Share Instance — [filter] is
        // read at Reticulum() construction, so changes surface through the
        // same pending-restart affordance and the options screen.
        if (canFilterAnnounces) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_advanced_filter_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = announceFilterEnabled,
                    onCheckedChange = onAnnounceFilterToggle,
                    modifier = Modifier.testTag("announce_filter_toggle"),
                )
            }
            Text(
                text = stringResource(R.string.settings_advanced_filter_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (announceFilterPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_advanced_restart_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = onRestartReticulum,
                        enabled = !isRestarting,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        if (isRestarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_advanced_restarting))
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_advanced_restart))
                        }
                    }
                }
            }
            if (announceFilterEnabled) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onOpenAnnounceFilter,
                    modifier = Modifier.testTag("announce_filter_options"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_advanced_filter_options))
                }
            }
        }

        // Opt-in crash reporting toggle (sentry flavor only — the SDK is stripped from
        // the noSentry flavor, so the toggle would do nothing there).
        if (BuildConfig.CRASH_REPORTING_AVAILABLE) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.crash_reporting_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = crashReportingEnabled,
                    onCheckedChange = onCrashReportingToggle,
                )
            }
            Text(
                text = stringResource(R.string.crash_reporting_privacy_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Threads P2 feature flag (threads-spec rollout: ships behind
        // threads_enabled through one beta cycle; default off).
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.settings_threads_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Switch(
                checked = threadsEnabled,
                onCheckedChange = onThreadsToggle,
                modifier = Modifier.testTag("threads_enabled_toggle"),
            )
        }
        Text(
            text = stringResource(R.string.settings_threads_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
