package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.service.RelayInfo
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard
import com.lxmf.messenger.util.DestinationHashValidator
import kotlinx.coroutines.delay

/**
 * Settings card for message delivery and retrieval options.
 * Allows users to configure:
 * - Default delivery method (Direct/Propagated)
 * - Retry via relay on failure toggle
 * - Auto-select nearest relay vs. manual selection
 * - View current relay info
 * - Auto-retrieve from relay toggle
 * - Retrieval interval selection
 * - Manual sync button
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Settings card requires many configuration options
@Composable
fun MessageDeliveryRetrievalCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    defaultMethod: String,
    tryPropagationOnFail: Boolean,
    currentRelayName: String?,
    currentRelayHops: Int?,
    currentRelayHash: String?,
    isAutoSelect: Boolean,
    availableRelays: List<RelayInfo>,
    onMethodChange: (String) -> Unit,
    onTryPropagationToggle: (Boolean) -> Unit,
    onAutoSelectToggle: (Boolean) -> Unit,
    onAddManualRelay: (destinationHash: String, nickname: String?) -> Unit,
    onSelectRelay: (destinationHash: String, displayName: String) -> Unit,
    // Retrieval settings
    autoRetrieveEnabled: Boolean,
    retrievalIntervalSeconds: Int,
    lastSyncTimestamp: Long?,
    isSyncing: Boolean,
    onAutoRetrieveToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onSyncNow: () -> Unit,
    onViewMoreRelays: () -> Unit = {},
    // Incoming message size limit
    incomingMessageSizeLimitKb: Int = 1024,
    onIncomingMessageSizeLimitChange: (Int) -> Unit = {},
    // Message sorting
    sortMessagesBySentTime: Boolean = false,
    onSortMessagesBySentTimeToggle: (Boolean) -> Unit = {},
) {
    var showMethodDropdown by remember { mutableStateOf(false) }
    var showCustomIntervalDialog by remember { mutableStateOf(false) }
    var showRelaySelectionDialog by remember { mutableStateOf(false) }
    var showCustomSizeLimitDialog by remember { mutableStateOf(false) }
    var customIntervalInput by remember { mutableStateOf("") }
    var customSizeLimitInput by remember { mutableStateOf("") }
    var manualHashInput by remember { mutableStateOf("") }
    var manualNicknameInput by remember { mutableStateOf("") }

    val presetIntervals = listOf(3600, 10800, 21600, 43200) // 1h, 3h, 6h, 12h

    CollapsibleSettingsCard(
        title = stringResource(R.string.message_delivery_title),
        icon = Icons.Default.Send,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = stringResource(R.string.message_delivery_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Default delivery method selector
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.message_delivery_default_method),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Box {
                OutlinedButton(
                    onClick = { showMethodDropdown = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = defaultDeliveryMethodLabel(defaultMethod),
                    )
                }
                DropdownMenu(
                    expanded = showMethodDropdown,
                    onDismissRequest = { showMethodDropdown = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.message_delivery_method_direct))
                                Text(
                                    text = stringResource(R.string.message_delivery_method_direct_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onMethodChange("direct")
                            showMethodDropdown = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.message_delivery_method_propagated))
                                Text(
                                    text = stringResource(R.string.message_delivery_method_propagated_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onMethodChange("propagated")
                            showMethodDropdown = false
                        },
                    )
                }
            }
        }

        // Retry via propagation toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_delivery_retry_via_relay),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.message_delivery_retry_via_relay_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = tryPropagationOnFail,
                onCheckedChange = onTryPropagationToggle,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Relay selection section
        Text(
            text = stringResource(R.string.message_delivery_my_relay),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        // Auto-select option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAutoSelectToggle(true) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isAutoSelect,
                onClick = { onAutoSelectToggle(true) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_delivery_auto_select_nearest),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isAutoSelect && currentRelayName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.message_delivery_currently, currentRelayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (currentRelayHops != null) {
                            Text(
                                text = stringResource(R.string.message_delivery_hops_parenthetical, formatRelayHops(currentRelayHops)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Manual selection option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAutoSelectToggle(false) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = !isAutoSelect,
                onClick = { onAutoSelectToggle(false) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_delivery_use_specific_relay),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isAutoSelect && currentRelayName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = currentRelayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (currentRelayHops != null) {
                            Text(
                                text = stringResource(R.string.message_delivery_hops_parenthetical, formatRelayHops(currentRelayHops)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (!isAutoSelect) {
                    Text(
                        text = stringResource(R.string.message_delivery_no_relay_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Current relay display
        if (currentRelayName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.message_delivery_select_different_relay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CurrentRelayInfo(
                relayName = currentRelayName,
                hops = currentRelayHops,
                isAutoSelected = isAutoSelect,
                onClick = { showRelaySelectionDialog = true },
            )
        } else if (isAutoSelect) {
            // Auto-select mode with no relay yet
            Text(
                text = stringResource(R.string.message_delivery_waiting_for_relays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Manual mode UI - show relay selection button and manual entry
        if (!isAutoSelect) {
            Spacer(modifier = Modifier.height(8.dp))

            // Show button to open relay selection dialog when no relay is selected
            if (currentRelayName == null) {
                OutlinedButton(
                    onClick = { showRelaySelectionDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.message_delivery_select_available_relays))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.message_delivery_enter_relay_hash_manually),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ManualRelayInput(
                hashInput = manualHashInput,
                onHashChange = { manualHashInput = it },
                nicknameInput = manualNicknameInput,
                onNicknameChange = { manualNicknameInput = it },
                onConfirm = { hash, nickname ->
                    onAddManualRelay(hash, nickname)
                    // Clear inputs after confirmation
                    manualHashInput = ""
                    manualNicknameInput = ""
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Message Retrieval Section
        Text(
            text = stringResource(R.string.message_delivery_retrieval_section),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        // Auto-retrieve toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_delivery_auto_retrieve),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.message_delivery_auto_retrieve_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoRetrieveEnabled,
                onCheckedChange = onAutoRetrieveToggle,
            )
        }

        // Retrieval interval chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.message_delivery_retrieval_interval, formatIntervalDisplay(retrievalIntervalSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntervalChip(
                    label = stringResource(R.string.message_delivery_hours_short, 1),
                    selected = retrievalIntervalSeconds == 3600,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(3600) },
                )
                IntervalChip(
                    label = stringResource(R.string.message_delivery_hours_short, 3),
                    selected = retrievalIntervalSeconds == 10800,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(10800) },
                )
                IntervalChip(
                    label = stringResource(R.string.message_delivery_hours_short, 6),
                    selected = retrievalIntervalSeconds == 21600,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(21600) },
                )
                IntervalChip(
                    label = stringResource(R.string.message_delivery_hours_short, 12),
                    selected = retrievalIntervalSeconds == 43200,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(43200) },
                )
                // Custom chip
                FilterChip(
                    selected = !presetIntervals.contains(retrievalIntervalSeconds),
                    onClick = {
                        customIntervalInput = retrievalIntervalSeconds.toString()
                        showCustomIntervalDialog = true
                    },
                    enabled = autoRetrieveEnabled,
                    label = {
                        Text(
                            if (presetIntervals.contains(retrievalIntervalSeconds)) {
                                stringResource(R.string.message_delivery_custom)
                            } else {
                                stringResource(R.string.message_delivery_custom_with_value, formatIntervalDisplay(retrievalIntervalSeconds))
                            },
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                )
            }
        }

        // Sync Now button
        Button(
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSyncing && currentRelayName != null,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.message_delivery_syncing))
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.message_delivery_sync_now))
            }
        }

        // Last sync timestamp with periodic refresh
        if (lastSyncTimestamp != null) {
            // Trigger recomposition every 5 seconds to update relative time
            var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5_000)
                    currentTime = System.currentTimeMillis()
                }
            }
            Text(
                text = stringResource(R.string.message_delivery_last_sync, formatRelativeTime(lastSyncTimestamp, currentTime)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Incoming Message Size Limit Section
        Text(
            text = stringResource(R.string.message_delivery_incoming_size_section),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(R.string.message_delivery_incoming_size_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Size limit chips
        val presetSizeLimitsKb = listOf(1024, 5120, 10240, 25600, 131072) // 1MB, 5MB, 10MB, 25MB, 128MB
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.message_delivery_size_limit, formatSizeLimit(incomingMessageSizeLimitKb)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SizeLimitChip(
                    label = stringResource(R.string.message_delivery_size_mb, 1),
                    selected = incomingMessageSizeLimitKb == 1024,
                    onClick = { onIncomingMessageSizeLimitChange(1024) },
                )
                SizeLimitChip(
                    label = stringResource(R.string.message_delivery_size_mb, 5),
                    selected = incomingMessageSizeLimitKb == 5120,
                    onClick = { onIncomingMessageSizeLimitChange(5120) },
                )
                SizeLimitChip(
                    label = stringResource(R.string.message_delivery_size_mb, 10),
                    selected = incomingMessageSizeLimitKb == 10240,
                    onClick = { onIncomingMessageSizeLimitChange(10240) },
                )
                SizeLimitChip(
                    label = stringResource(R.string.message_delivery_size_mb, 25),
                    selected = incomingMessageSizeLimitKb == 25600,
                    onClick = { onIncomingMessageSizeLimitChange(25600) },
                )
                SizeLimitChip(
                    label = stringResource(R.string.message_delivery_unlimited),
                    selected = incomingMessageSizeLimitKb == 131072,
                    onClick = { onIncomingMessageSizeLimitChange(131072) },
                )
                // Custom chip
                FilterChip(
                    selected = !presetSizeLimitsKb.contains(incomingMessageSizeLimitKb),
                    onClick = {
                        customSizeLimitInput = (incomingMessageSizeLimitKb / 1024).toString()
                        showCustomSizeLimitDialog = true
                    },
                    label = {
                        Text(
                            if (presetSizeLimitsKb.contains(incomingMessageSizeLimitKb)) {
                                stringResource(R.string.message_delivery_custom)
                            } else {
                                stringResource(R.string.message_delivery_custom_with_value, formatSizeLimit(incomingMessageSizeLimitKb))
                            },
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Message Sort Order Section
        Text(
            text = stringResource(R.string.message_delivery_sort_order_section),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_delivery_sort_by_sender_time),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        if (sortMessagesBySentTime) {
                            stringResource(R.string.message_delivery_sort_by_sender_time_description)
                        } else {
                            stringResource(R.string.message_delivery_sort_by_receive_time_description)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sortMessagesBySentTime,
                onCheckedChange = onSortMessagesBySentTimeToggle,
            )
        }
    }

    // Custom interval dialog
    if (showCustomIntervalDialog) {
        CustomRetrievalIntervalDialog(
            customIntervalInput = customIntervalInput,
            onInputChange = { customIntervalInput = it },
            onConfirm = { value ->
                onIntervalChange(value)
                showCustomIntervalDialog = false
            },
            onDismiss = { showCustomIntervalDialog = false },
        )
    }

    // Relay selection dialog
    if (showRelaySelectionDialog) {
        RelaySelectionDialog(
            availableRelays = availableRelays,
            currentRelayHash = currentRelayHash,
            onSelectRelay = { hash, name ->
                onSelectRelay(hash, name)
                showRelaySelectionDialog = false
            },
            onViewMoreRelays = onViewMoreRelays,
            onDismiss = { showRelaySelectionDialog = false },
        )
    }

    // Custom size limit dialog
    if (showCustomSizeLimitDialog) {
        CustomSizeLimitDialog(
            customSizeLimitInput = customSizeLimitInput,
            onInputChange = { customSizeLimitInput = it },
            onConfirm = { valueMb ->
                onIncomingMessageSizeLimitChange(valueMb * 1024)
                showCustomSizeLimitDialog = false
            },
            onDismiss = { showCustomSizeLimitDialog = false },
        )
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun defaultDeliveryMethodLabel(defaultMethod: String): String =
    when (defaultMethod) {
        "direct" -> stringResource(R.string.message_delivery_method_direct)
        "propagated" -> stringResource(R.string.message_delivery_method_propagated)
        else -> stringResource(R.string.message_delivery_method_direct)
    }

@Composable
private fun formatRelayHops(hops: Int): String =
    if (hops == 1) {
        stringResource(R.string.message_delivery_hop_one, hops)
    } else {
        stringResource(R.string.message_delivery_hop_other, hops)
    }

@Composable
private fun CurrentRelayInfo(
    relayName: String,
    hops: Int?,
    isAutoSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hub icon
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = stringResource(R.string.message_delivery_relay_content_description),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = relayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isAutoSelected) {
                        Text(
                            text = stringResource(R.string.message_delivery_auto_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hops != null) {
                    Text(
                        text = stringResource(R.string.message_delivery_hops_away, formatRelayHops(hops)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Format a timestamp as relative time (e.g., "2 minutes ago", "Just now").
 * @param timestamp The timestamp to format
 * @param now The current time (passed in to trigger recomposition on change)
 */
@Composable
private fun formatRelativeTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val diff = now - timestamp

    return when {
        diff < 5_000 -> stringResource(R.string.message_delivery_just_now)
        diff < 60_000 -> stringResource(R.string.message_delivery_seconds_ago, diff / 1000)
        diff < 120_000 -> stringResource(R.string.message_delivery_minute_ago)
        diff < 3600_000 -> stringResource(R.string.message_delivery_minutes_ago, diff / 60_000)
        diff < 7200_000 -> stringResource(R.string.message_delivery_hour_ago)
        diff < 86400_000 -> stringResource(R.string.message_delivery_hours_ago, diff / 3600_000)
        else -> stringResource(R.string.message_delivery_days_ago, diff / 86400_000)
    }
}

/**
 * Format interval in seconds to a readable string (e.g., "30s", "2min", "5min").
 */
@Composable
private fun formatIntervalDisplay(seconds: Int): String =
    when {
        seconds < 60 -> stringResource(R.string.message_delivery_seconds_short, seconds)
        seconds % 60 == 0 -> stringResource(R.string.message_delivery_minutes_short, seconds / 60)
        else -> stringResource(R.string.message_delivery_minutes_seconds_short, seconds / 60, seconds % 60)
    }

@Composable
private fun CustomRetrievalIntervalDialog(
    customIntervalInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_delivery_custom_interval_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.message_delivery_custom_interval_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customIntervalInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 5) {
                            onInputChange(it)
                        }
                    },
                    label = { Text(stringResource(R.string.message_delivery_seconds_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customIntervalInput.toIntOrNull()?.let { it < 3600 || it > 43200 } ?: false,
                    supportingText = {
                        val value = customIntervalInput.toIntOrNull()
                        when {
                            value == null && customIntervalInput.isNotEmpty() -> Text(stringResource(R.string.message_delivery_enter_valid_number))
                            value != null && value < 3600 -> Text(stringResource(R.string.message_delivery_interval_minimum))
                            value != null && value > 43200 -> Text(stringResource(R.string.message_delivery_interval_maximum))
                            value != null -> Text(stringResource(R.string.message_delivery_interval_equals, formatIntervalDisplay(value)))
                            else -> {}
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customIntervalInput.toIntOrNull()
                    if (value != null && value in 3600..43200) {
                        onConfirm(value)
                    }
                },
                enabled = customIntervalInput.toIntOrNull()?.let { it in 3600..43200 } ?: false,
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Input form for manually entering a propagation node destination hash.
 */
@Composable
private fun ManualRelayInput(
    hashInput: String,
    onHashChange: (String) -> Unit,
    nicknameInput: String,
    onNicknameChange: (String) -> Unit,
    onConfirm: (hash: String, nickname: String?) -> Unit,
) {
    val validationResult = DestinationHashValidator.validate(hashInput)
    val isValid = validationResult is DestinationHashValidator.ValidationResult.Valid
    val errorMessage = (validationResult as? DestinationHashValidator.ValidationResult.Error)?.message

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.message_delivery_enter_destination_hash),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = hashInput,
            onValueChange = { input ->
                // Only allow hex characters, up to 32 chars
                val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                if (filtered.length <= 32) {
                    onHashChange(filtered)
                }
            },
            label = { Text(stringResource(R.string.message_delivery_destination_hash)) },
            placeholder = { Text(stringResource(R.string.message_delivery_destination_hash_placeholder)) },
            singleLine = true,
            isError = hashInput.isNotEmpty() && !isValid,
            supportingText = {
                if (hashInput.isEmpty()) {
                    Text(DestinationHashValidator.getCharacterCount(hashInput))
                } else if (!isValid && errorMessage != null) {
                    Text(errorMessage)
                } else {
                    Text(DestinationHashValidator.getCharacterCount(hashInput))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = nicknameInput,
            onValueChange = onNicknameChange,
            label = { Text(stringResource(R.string.message_delivery_nickname_optional)) },
            placeholder = { Text(stringResource(R.string.message_delivery_nickname_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val normalizedHash =
                    (validationResult as DestinationHashValidator.ValidationResult.Valid).normalizedHash
                val nickname = nicknameInput.trim().takeIf { it.isNotEmpty() }
                onConfirm(normalizedHash, nickname)
            },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.announce_set_as_my_relay))
        }
    }
}

/**
 * Dialog for selecting a relay from the list of available propagation nodes.
 */
@Composable
private fun RelaySelectionDialog(
    availableRelays: List<RelayInfo>,
    currentRelayHash: String?,
    onSelectRelay: (hash: String, name: String) -> Unit,
    onViewMoreRelays: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_delivery_select_relay)) },
        text = {
            // Skip loading state - query is fast enough. Just show relays or empty message.
            if (availableRelays.isEmpty()) {
                Text(
                    text = stringResource(R.string.message_delivery_no_propagation_nodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(availableRelays, key = { it.destinationHash }) { relay ->
                        RelayListItem(
                            relay = relay,
                            isSelected = relay.destinationHash == currentRelayHash,
                            onClick = { onSelectRelay(relay.destinationHash, relay.displayName) },
                        )
                    }
                    // "More..." item to view all relays in the announces screen
                    item(key = "more_relays") {
                        MoreRelaysItem(
                            onClick = {
                                onViewMoreRelays()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * A single relay item in the selection list.
 */
@Composable
private fun RelayListItem(
    relay: RelayInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hub icon
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = stringResource(R.string.message_delivery_hops_away, formatRelayHops(relay.hops)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSelected) {
                Text(
                    text = stringResource(R.string.message_delivery_current_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * A "More..." item at the end of the relay list to view all relays.
 */
@Composable
private fun MoreRelaysItem(onClick: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.message_delivery_view_all_relays),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun SizeLimitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

/**
 * Format size limit in KB to a readable string (e.g., "1 MB", "5.5 MB", "128 MB").
 */
@Composable
private fun formatSizeLimit(limitKb: Int): String =
    when {
        limitKb >= 131072 -> stringResource(R.string.message_delivery_unlimited_128mb)
        limitKb >= 1024 -> {
            val mb = limitKb / 1024.0
            if (mb == mb.toInt().toDouble()) {
                stringResource(R.string.message_delivery_size_mb, mb.toInt())
            } else {
                stringResource(R.string.message_delivery_size_mb_decimal, mb)
            }
        }
        else -> stringResource(R.string.message_delivery_size_kb, limitKb)
    }

@Composable
private fun CustomSizeLimitDialog(
    customSizeLimitInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_delivery_custom_size_limit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.message_delivery_custom_size_limit_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customSizeLimitInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 3) {
                            onInputChange(it)
                        }
                    },
                    label = { Text(stringResource(R.string.message_delivery_mb_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customSizeLimitInput.toIntOrNull()?.let { it < 1 || it > 128 } ?: false,
                    supportingText = {
                        val value = customSizeLimitInput.toIntOrNull()
                        when {
                            value == null && customSizeLimitInput.isNotEmpty() -> Text(stringResource(R.string.message_delivery_enter_valid_number))
                            value != null && value < 1 -> Text(stringResource(R.string.message_delivery_size_limit_minimum))
                            value != null && value > 128 -> Text(stringResource(R.string.message_delivery_size_limit_maximum))
                            value != null -> Text(stringResource(R.string.message_delivery_size_limit_equals, value * 1024))
                            else -> {}
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customSizeLimitInput.toIntOrNull()
                    if (value != null && value in 1..128) {
                        onConfirm(value)
                    }
                },
                enabled = customSizeLimitInput.toIntOrNull()?.let { it in 1..128 } ?: false,
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
