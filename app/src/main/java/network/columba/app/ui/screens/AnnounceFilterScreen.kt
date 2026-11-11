package network.columba.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.viewmodel.SettingsViewModel

/**
 * Announce Filtering detail screen (Settings → Advanced → Filter Options).
 *
 * Exposes the RNS 1.6.0 `[filter]` ingress gate: the master `required_only`
 * switch, the per-aspect allow toggles (messaging/propagation are locked —
 * unchecking them would break the app itself), the advanced `hop_limiter`,
 * and the hosting section (filter-while-sharing preference + the custom
 * aspect list that is only emitted while hosting).
 *
 * Every control is persist-only; `[filter]` is read at Reticulum()
 * construction, so the bottom bar offers the same inline Restart the
 * Advanced card shows while `announceFilterPending`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnounceFilterScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showHopDialog by rememberSaveable { mutableStateOf(false) }
    var showAddAspectDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_announce_filter_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ---- master switch ------------------------------------------------
            SettingToggleRow(
                title = stringResource(R.string.settings_advanced_filter_title),
                description = stringResource(R.string.settings_announce_filter_master_description),
                checked = state.announceFilterEnabled,
                onCheckedChange = { viewModel.setAnnounceFilterEnabled(it) },
                toggleTestTag = "announce_filter_master_toggle",
            )

            Spacer(Modifier.height(16.dp))

            // ---- always received ----------------------------------------------
            SectionHeader(stringResource(R.string.settings_announce_filter_always_header))
            LockedRow(
                title = stringResource(R.string.settings_announce_filter_contacts),
            )
            LockedRow(
                title = stringResource(R.string.settings_announce_filter_relays),
            )

            Spacer(Modifier.height(16.dp))

            // ---- optional aspects ----------------------------------------------
            SectionHeader(stringResource(R.string.settings_announce_filter_optional_header))
            SettingToggleRow(
                title = stringResource(R.string.settings_announce_filter_nodes),
                description = stringResource(R.string.settings_announce_filter_nodes_description),
                checked = state.announceFilterAllowNodes,
                onCheckedChange = { viewModel.setAnnounceFilterAllowNodes(it) },
                toggleTestTag = "announce_filter_nodes_toggle",
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_announce_filter_calls),
                description = stringResource(R.string.settings_announce_filter_calls_description),
                checked = state.announceFilterAllowCalls,
                onCheckedChange = { viewModel.setAnnounceFilterAllowCalls(it) },
                toggleTestTag = "announce_filter_calls_toggle",
            )
            // While Auto Discovery is on the config builders force this aspect
            // through (dropping it breaks AutoInterface peering), so the toggle
            // is disabled rather than silently lying about its effect.
            SettingToggleRow(
                title = stringResource(R.string.settings_announce_filter_discovery),
                description = stringResource(
                    if (state.discoverInterfacesEnabled) {
                        R.string.settings_announce_filter_discovery_locked
                    } else {
                        R.string.settings_announce_filter_discovery_description
                    },
                ),
                checked = state.announceFilterAllowDiscovery || state.discoverInterfacesEnabled,
                onCheckedChange = { viewModel.setAnnounceFilterAllowDiscovery(it) },
                enabled = !state.discoverInterfacesEnabled,
                toggleTestTag = "announce_filter_discovery_toggle",
            )

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // ---- hop limiter ----------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHopDialog = true }
                    .testTag("announce_filter_hops_row")
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_announce_filter_hops),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (state.announceFilterMaxHops == 0) {
                        stringResource(R.string.settings_announce_filter_hops_unlimited)
                    } else {
                        stringResource(R.string.settings_announce_filter_hops_value, state.announceFilterMaxHops)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // ---- shared instance section -----------------------------------------
            SectionHeader(stringResource(R.string.settings_announce_filter_hosting_header))
            if (state.isHostingSharedInstance && !state.announceFilterWhileHosting) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.settings_announce_filter_hosting_paused),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            SettingToggleRow(
                title = stringResource(R.string.settings_announce_filter_while_hosting_title),
                description = stringResource(R.string.settings_announce_filter_while_hosting_description),
                checked = state.announceFilterWhileHosting,
                onCheckedChange = { viewModel.setAnnounceFilterWhileHosting(it) },
                toggleTestTag = "announce_filter_while_hosting_toggle",
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_announce_filter_custom_header))
            state.announceFilterCustomAspects.forEach { aspect ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("announce_filter_aspect_row")
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = aspect,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { viewModel.removeAnnounceFilterAspect(aspect) },
                        modifier = Modifier.testTag("announce_filter_aspect_remove"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(
                                R.string.settings_announce_filter_custom_remove,
                                aspect,
                            ),
                        )
                    }
                }
            }
            TextButton(
                onClick = { showAddAspectDialog = true },
                modifier = Modifier.testTag("announce_filter_add_aspect"),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_announce_filter_custom_add))
            }
            Text(
                text = stringResource(R.string.settings_announce_filter_custom_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ---- restart bar -------------------------------------------------------
            if (state.announceFilterPending) {
                RestartBar(
                    isRestarting = state.isRestarting,
                    onRestart = { viewModel.restartService() },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHopDialog) {
        HopLimitDialog(
            currentHops = state.announceFilterMaxHops,
            onDismiss = { showHopDialog = false },
            onConfirm = { hops ->
                viewModel.setAnnounceFilterMaxHops(hops)
                showHopDialog = false
            },
        )
    }

    if (showAddAspectDialog) {
        AddAspectDialog(
            onDismiss = { showAddAspectDialog = false },
            onAdd = { aspect, result -> viewModel.addAnnounceFilterAspect(aspect, result) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleTestTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(toggleTestTag),
        )
    }
}

@Composable
private fun LockedRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun RestartBar(
    isRestarting: Boolean,
    onRestart: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_announce_filter_restart_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onRestart,
                enabled = !isRestarting,
                modifier = Modifier.testTag("announce_filter_restart"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
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

private const val HOP_LIMIT_MAX = 7

@Composable
private fun HopLimitDialog(
    currentHops: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hops by remember { mutableStateOf(currentHops.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_announce_filter_hops_dialog_title)) },
        text = {
            Column {
                Text(
                    text = if (hops.toInt() == 0) {
                        stringResource(R.string.settings_announce_filter_hops_unlimited_value)
                    } else {
                        stringResource(R.string.settings_announce_filter_hops_value, hops.toInt())
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = hops,
                    onValueChange = { hops = it },
                    valueRange = 0f..HOP_LIMIT_MAX.toFloat(),
                    steps = HOP_LIMIT_MAX - 1,
                    modifier = Modifier.testTag("announce_filter_hops_slider"),
                )
                Text(
                    text = stringResource(R.string.settings_announce_filter_hops_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(hops.toInt()) },
                modifier = Modifier.testTag("announce_filter_hops_confirm"),
            ) {
                Text(stringResource(R.string.settings_announce_filter_action_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddAspectDialog(
    onDismiss: () -> Unit,
    /** Receives the typed aspect and the add-result callback from the ViewModel. */
    onAdd: (String, (Boolean) -> Unit) -> Unit,
) {
    var aspect by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_announce_filter_custom_add)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_announce_filter_add_scope_chip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aspect,
                    onValueChange = {
                        aspect = it
                        rejected = false
                    },
                    label = { Text(stringResource(R.string.settings_announce_filter_add_label)) },
                    singleLine = true,
                    isError = rejected,
                    supportingText = {
                        Text(
                            text = if (rejected) {
                                stringResource(R.string.settings_announce_filter_add_invalid)
                            } else {
                                stringResource(R.string.settings_announce_filter_add_helper)
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("announce_filter_aspect_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(aspect) { added ->
                        if (added) {
                            onDismiss()
                        } else {
                            rejected = true
                        }
                    }
                },
                modifier = Modifier.testTag("announce_filter_aspect_confirm"),
            ) {
                Text(stringResource(R.string.settings_announce_filter_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
