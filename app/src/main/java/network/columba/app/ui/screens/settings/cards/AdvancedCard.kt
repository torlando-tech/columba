package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.BuildConfig
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
 * @param shareInstanceHostingEnabled Persisted "Share Instance" toggle state
 *   (python backend only; capability-gated). True means the daemon will publish itself
 *   as an RNS shared instance on TCP 37428 after the next restart.
 * @param onShareInstanceHostingToggle Callback when Share Instance toggle is changed.
 *   Persists the preference but does NOT trigger a service restart — the user must
 *   explicitly Apply & Restart from elsewhere to avoid surprise outages.
 * @param shareInstanceHostingPending True when the persisted preference differs from
 *   the value the running daemon was constructed with. Drives the "(pending restart)"
 *   hint and inline Restart button under the toggle.
 * @param onRestartReticulum Invoked when the user taps the inline Restart button next
 *   to the pending-changes hint. Plumbs through to `SettingsViewModel.restartService()`.
 * @param isRestarting True while a service restart is in flight; disables the
 *   inline Restart button and shows a small progress spinner instead of the icon.
 * @param persistentWakeLockEnabled Whether the service holds a persistent wake lock
 *   (true = current behavior: CPU never suspends, zero-latency packet handling;
 *   false = duty-cycle mode: CPU sleeps between mesh traffic, short timed locks
 *   cover active bursts). Applies to the running service immediately.
 * @param onPersistentWakeLockToggle Callback when the Persistent Wake Lock toggle
 *   is changed.
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
    isRestarting: Boolean = false,
    crashReportingEnabled: Boolean = false,
    onCrashReportingToggle: (Boolean) -> Unit = {},
    persistentWakeLockEnabled: Boolean = true,
    onPersistentWakeLockToggle: (Boolean) -> Unit = {},
) {
    val canHostShareInstance = LocalCapabilities.current.performance.shareInstanceHosting
    CollapsibleSettingsCard(
        title = "Advanced",
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
                    text = "Transport Node",
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
            text =
                "Forward traffic for the mesh network. When disabled, this device will only " +
                    "handle its own traffic and won't relay messages for other peers. " +
                    "It's generally not recommended for mobile devices to be transport nodes. " +
                    "They are less likely to maintain a fixed position in the network, and thus " +
                    "can negatively impact multihop routing. Enabling this will increase data " +
                    "usage and battery drain. However, in a BLE-only mesh, it's required for " +
                    "multi-hop messaging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Persistent Wake Lock toggle
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
                    imageVector = Icons.Default.BatterySaver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Persistent Wake Lock",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Switch(
                checked = persistentWakeLockEnabled,
                onCheckedChange = onPersistentWakeLockToggle,
                modifier = Modifier.testTag("persistent_wake_lock_toggle"),
            )
        }
        Text(
            text =
                "Keep the CPU awake so incoming mesh packets are processed the " +
                    "instant they arrive. On (default) is the most responsive but " +
                    "drains battery while backgrounded all day. Off lets the CPU " +
                    "sleep between traffic; a short lock still covers active " +
                    "transfers, and delivery resumes within seconds of each wake. " +
                    "Changes apply immediately.",
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
                        text = "Share Instance",
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
                text =
                    "Make Columba available as a shared RNS instance so other apps on this device " +
                        "(Sideband, rnsd, …) can route through Columba's transport. " +
                        "Requires a service restart to take effect. " +
                        "If another app is already hosting a shared instance on this device, " +
                        "Columba will join it as a client instead — see the Shared Instance banner.",
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
                        text = "Change pending — restart Reticulum to apply.",
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
                            Text("Restarting…")
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Restart")
                        }
                    }
                }
            }
        }

        // Anonymous crash reporting toggle (sentry flavor only — the SDK is stripped from
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
                        text = "Anonymous Crash Reports",
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
                text =
                    "Send anonymous crash and error reports to help the developer fix bugs. " +
                        "No message content, contacts, or identity information is ever included. " +
                        "Off by default; you can change this at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
