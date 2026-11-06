package network.columba.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.data.config.InterfaceConfigCodec
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.viewmodel.InterfaceImportViewModel

/**
 * Paste/import dialog for Reticulum interface config text.
 *
 * Backed by [InterfaceImportViewModel]: parsing, duplicate detection, and name
 * de-collision happen there. The dialog is shared by the interface-management
 * screen and the in-chat config suggestion.
 */
@Composable
fun InterfaceConfigImportDialog(
    state: InterfaceImportViewModel.State,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onToggle: (Int) -> Unit,
    onImport: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val canPaste = clipboardManager.getText()?.text?.isNotBlank() == true

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.SettingsInputAntenna,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.interface_config_import_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.interface_config_import_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.interface_config_import_field_label)) },
                    placeholder = {
                        Text(
                            "[[My Node]]\n  type = TCPClientInterface\n  target_host = example.com\n  target_port = 4242",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    minLines = 5,
                    maxLines = 8,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let(onTextChange)
                            },
                            enabled = canPaste,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.interface_config_import_paste_clipboard),
                            )
                        }
                    },
                )

                if (canPaste && state.text.isBlank()) {
                    TextButton(onClick = { clipboardManager.getText()?.text?.let(onTextChange) }) {
                        Text(stringResource(R.string.interface_config_import_paste_clipboard))
                    }
                }

                if (state.isLoadingExisting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).padding(end = 8.dp))
                        Text(
                            text = stringResource(R.string.interface_config_import_checking),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                for (error in state.errors) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                for (warning in state.warnings) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.items.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text =
                            if (state.items.size == 1) {
                                stringResource(R.string.interface_config_import_found_one)
                            } else {
                                stringResource(R.string.interface_config_import_found_many, state.items.size)
                            },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.items.forEachIndexed { index, item ->
                        ImportItemRow(
                            item = item,
                            onToggle = { onToggle(index) },
                        )
                    }
                } else if (state.text.isNotBlank() && !state.isLoadingExisting) {
                    Text(
                        text = stringResource(R.string.interface_config_import_none_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onImport,
                enabled = state.canImport,
            ) {
                if (state.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text =
                        if (state.selectedCount <= 1) {
                            stringResource(R.string.interface_config_import_add_one)
                        } else {
                            stringResource(R.string.interface_config_import_add_many, state.selectedCount)
                        },
                )
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
private fun ImportItemRow(
    item: InterfaceImportViewModel.ImportItem,
    onToggle: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (item.duplicate) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = { onToggle() },
            )
            Column(modifier = Modifier.padding(top = 10.dp, end = 8.dp)) {
                Text(
                    text = item.config.name,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.duplicate) TextDecoration.LineThrough else null,
                )
                Text(
                    text = "${item.config.importTypeLabel()} · ${item.config.endpointSummary()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.duplicate) {
                    Text(
                        text = stringResource(R.string.interface_config_import_duplicate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (item.wasRenamed) {
                    Text(
                        text = stringResource(R.string.interface_config_import_renamed, item.originalName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (warning in item.warnings) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Small card rendered under a received message that contains interface config
 * text. Tapping "Add" opens the import dialog prefilled with the message body.
 */
@Composable
fun InterfaceConfigSuggestionCard(
    parsed: List<InterfaceConfigCodec.ParsedInterface>,
    onAdd: () -> Unit,
) {
    if (parsed.isEmpty()) return
    val first = parsed.first().config
    val more = parsed.size - 1

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.padding(top = 6.dp).width(280.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.interface_config_detected_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = first.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text =
                    buildString {
                        append(first.importTypeLabel())
                        append(" · ")
                        append(first.endpointSummary())
                        if (more > 0) append(" · +$more")
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
            TextButton(
                onClick = onAdd,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.interface_config_add_to_interfaces))
            }
        }
    }
}

internal fun InterfaceConfig.importTypeLabel(): String =
    when (this) {
        is InterfaceConfig.TCPClient -> "TCP client"
        is InterfaceConfig.TCPServer -> "TCP server"
        is InterfaceConfig.UDP -> "UDP"
        is InterfaceConfig.AutoInterface -> "Auto Discovery"
        is InterfaceConfig.RNode -> if (connectionMode == "tcp") "RNode · WiFi" else "RNode"
        is InterfaceConfig.AndroidBLE -> "Bluetooth LE"
    }

internal fun InterfaceConfig.endpointSummary(): String =
    when (this) {
        is InterfaceConfig.TCPClient -> "$targetHost:$targetPort"
        is InterfaceConfig.TCPServer -> "listen $listenIp:$listenPort"
        is InterfaceConfig.UDP -> "listen $listenIp:$listenPort → $forwardIp:$forwardPort"
        is InterfaceConfig.AutoInterface -> groupId.ifBlank { "default group" }
        is InterfaceConfig.RNode -> tcpHost?.let { "$it:$tcpPort" } ?: targetDeviceName.ifBlank { "device" }
        is InterfaceConfig.AndroidBLE -> deviceName.ifBlank { "local radio" }
    }
