package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R

/**
 * Determines if the shared instance banner should be shown.
 * Banner is shown when:
 * - Currently using a shared instance
 * - A shared instance is currently available (can switch to it)
 * - Was using shared instance but it went offline (informational state)
 * - Service is restarting
 */
internal fun shouldShowSharedInstanceBanner(
    isSharedInstance: Boolean,
    sharedInstanceOnline: Boolean,
    wasUsingSharedInstance: Boolean,
    isRestarting: Boolean,
): Boolean {
    return isSharedInstance || sharedInstanceOnline || wasUsingSharedInstance || isRestarting
}

/**
 * Determines if the shared instance toggle should be enabled.
 * - When using shared (toggle OFF): can always switch to own (toggle ON)
 * - When using own (toggle ON): can only switch to shared (toggle OFF) if shared is available
 */
internal fun isSharedInstanceToggleEnabled(
    isUsingSharedInstance: Boolean,
    sharedInstanceOnline: Boolean,
): Boolean {
    return isUsingSharedInstance || sharedInstanceOnline
}

/**
 * Computes the checked state of the shared instance toggle.
 * Toggle ON means using own instance (not shared).
 */
internal fun computeSharedInstanceToggleChecked(isSharedInstance: Boolean): Boolean {
    return !isSharedInstance
}

/**
 * Banner card displayed for shared Reticulum instance management.
 *
 * Shown when:
 * - Connected to a shared instance (e.g., from Sideband)
 * - A shared instance is available to switch to
 * - Was using shared instance but it went offline (informational state)
 *
 * The banner is collapsible:
 * - Collapsed: Shows current instance status with expand chevron
 * - Expanded: Shows full explanation and toggle (or informational message if shared went offline)
 */
@Composable
fun SharedInstanceBannerCard(
    isExpanded: Boolean,
    isUsingSharedInstance: Boolean,
    rpcKey: String?,
    wasUsingSharedInstance: Boolean = false,
    /** Current availability from service query */
    sharedInstanceOnline: Boolean = false,
    onExpandToggle: (Boolean) -> Unit,
    onTogglePreferOwnInstance: (Boolean) -> Unit,
    onRpcKeyChange: (String?) -> Unit,
) {
    val toggleEnabled = isSharedInstanceToggleEnabled(isUsingSharedInstance, sharedInstanceOnline)

    // Determine if this is the informational state (was using shared, now offline)
    // Note: wasUsingSharedInstance is only set when shared went offline while we were using it,
    // so we just need to check that flag and that shared is still offline
    val isInformationalState = wasUsingSharedInstance && !sharedInstanceOnline

    // Use subtle color for informational state (not error), primary otherwise
    val containerColor =
        if (isInformationalState) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val contentColor =
        if (isInformationalState) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onExpandToggle(!isExpanded) },
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row (always visible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector =
                            if (isInformationalState) {
                                Icons.Default.LinkOff
                            } else {
                                Icons.Default.Link
                            },
                        contentDescription = stringResource(R.string.shared_instance_mode_content_description),
                        tint = contentColor,
                    )
                    Text(
                        text =
                            when {
                                isInformationalState -> stringResource(R.string.shared_instance_offline_title)
                                isUsingSharedInstance -> stringResource(R.string.shared_instance_connected_title)
                                else -> stringResource(R.string.shared_instance_own_title)
                            },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                }
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = if (isExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = contentColor,
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Show different content based on state
                    if (isInformationalState) {
                        // Informational state - shared instance went offline, Columba restarted
                        Text(
                            text = stringResource(R.string.shared_instance_offline_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        Text(
                            text = stringResource(R.string.shared_instance_offline_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    } else {
                        // Normal state
                        Text(
                            text =
                                if (isUsingSharedInstance) {
                                    stringResource(R.string.shared_instance_using_shared_message)
                                } else {
                                    stringResource(R.string.shared_instance_using_own_message)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        // Bullet points (only when using shared instance)
                        if (isUsingSharedInstance) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.shared_instance_bullet_network_interfaces),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                Text(
                                    text = stringResource(R.string.shared_instance_bullet_privacy),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                Text(
                                    text = stringResource(R.string.shared_instance_bullet_ble),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                            }
                        }

                        // Toggle for using own instance
                        // Shows actual state (!isUsingSharedInstance), not preference
                        // Enabled when: using shared (can always switch to own) OR shared is online (can switch to it)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.shared_instance_use_own_instance),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                            )
                            Switch(
                                // Show actual state, not preference - toggle ON when using own instance
                                checked = !isUsingSharedInstance,
                                onCheckedChange = onTogglePreferOwnInstance,
                                enabled = toggleEnabled,
                            )
                        }

                        // Hint text - show different message based on toggle state
                        Text(
                            text =
                                if (!isUsingSharedInstance && !sharedInstanceOnline) {
                                    stringResource(R.string.shared_instance_unavailable)
                                } else {
                                    stringResource(R.string.shared_instance_restart_hint)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )

                        // RPC Key input (only when using shared instance)
                        if (isUsingSharedInstance) {
                            var rpcKeyInput by remember { mutableStateOf(rpcKey.orEmpty()) }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.shared_instance_rpc_key_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                OutlinedTextField(
                                    value = rpcKeyInput,
                                    onValueChange = { rpcKeyInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            stringResource(R.string.shared_instance_rpc_key_placeholder),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    singleLine = false,
                                    maxLines = 3,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    onClick = {
                                        onRpcKeyChange(rpcKeyInput.ifEmpty { null })
                                    },
                                    enabled = rpcKeyInput != rpcKey.orEmpty(),
                                ) {
                                    Text(stringResource(R.string.common_save))
                                }
                            }
                            Text(
                                text = stringResource(R.string.shared_instance_rpc_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}
