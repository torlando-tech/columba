package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.NodeTypeBadge
import com.lxmf.messenger.ui.components.ProfileIcon
import com.lxmf.messenger.ui.util.getReceivingInterfaceInfo
import com.lxmf.messenger.util.formatTimeSince
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AnnounceDetailScreen(
    destinationHash: String,
    onBackClick: () -> Unit,
    onStartChat: (destinationHash: String, peerName: String) -> Unit,
    viewModel: AnnounceStreamViewModel = hiltViewModel(),
) {
    val clipboardManager = LocalClipboardManager.current

    // Observe specific announce reactively (not search in list)
    val announce by viewModel.getAnnounceFlow(destinationHash).collectAsState(initial = null)

    // Separately observe contact status for star button
    val isContact by viewModel.isContactFlow(destinationHash).collectAsState(initial = false)

    // Observe if this contact is the current relay
    val isMyRelay by viewModel.isMyRelayFlow(destinationHash).collectAsState(initial = false)

    // Dialog state for remove confirmation
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Dialog state for relay unset confirmation
    var showUnsetRelayDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Node Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Favorite toggle button (show even if announce is null but is a contact)
                    if (announce != null || isContact) {
                        IconButton(
                            onClick = {
                                if (isContact) {
                                    // Show appropriate confirmation dialog when removing
                                    if (isMyRelay) {
                                        showUnsetRelayDialog = true
                                    } else {
                                        showRemoveDialog = true
                                    }
                                } else {
                                    // Add directly without confirmation
                                    viewModel.toggleContact(destinationHash)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (isContact) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isContact) "Remove from saved" else "Save peer",
                                tint =
                                    if (isContact) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        if (announce == null) {
            // Show error state if announce not found
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Node not found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onBackClick) {
                        Text("Go Back")
                    }
                }
            }
        } else {
            // Bind to local variable for smart-casting (delegated properties don't smart-cast)
            // Safe to use !! here because we're in the else block of announce == null check
            val announceNonNull = announce!!
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Identicon header
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProfileIcon(
                            iconName = announceNonNull.iconName,
                            foregroundColor = announceNonNull.iconForegroundColor,
                            backgroundColor = announceNonNull.iconBackgroundColor,
                            size = 96.dp,
                            fallbackHash = announceNonNull.publicKey,
                        )

                        Text(
                            text = announceNonNull.peerName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        NodeTypeBadge(nodeType = announceNonNull.nodeType)
                    }
                }

                // Only show "Start Chat" button for LXMF delivery peers (not audio calls)
                if (announceNonNull.aspect == "lxmf.delivery") {
                    Button(
                        onClick = {
                            onStartChat(announceNonNull.destinationHash, announceNonNull.peerName)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Show "Set as My Relay" button for propagation nodes
                if (announceNonNull.nodeType == "PROPAGATION_NODE") {
                    val isCurrentRelay by viewModel.isMyRelayFlow(destinationHash).collectAsState(initial = false)

                    Button(
                        onClick = {
                            viewModel.setAsMyRelay(announceNonNull.destinationHash)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (isCurrentRelay) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    },
                            ),
                        enabled = !isCurrentRelay,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCurrentRelay) "Current Relay" else "Set as My Relay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Information cards
                InfoCard(
                    icon = Icons.Default.Fingerprint,
                    title = "Destination Hash",
                    content = announceNonNull.destinationHash,
                    isMonospace = true,
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(announceNonNull.destinationHash))
                    },
                )

                InfoCard(
                    icon = Icons.Default.Router,
                    title = "Network Distance",
                    content = "${announceNonNull.hops} ${if (announceNonNull.hops == 1) "hop" else "hops"}",
                    subtitle =
                        when {
                            announceNonNull.hops <= 1 -> "Excellent signal strength"
                            announceNonNull.hops <= 3 -> "Good signal strength"
                            else -> "Weak signal strength"
                        },
                )

                // Show transfer size limit for propagation nodes
                val transferLimit = announceNonNull.propagationTransferLimitKb
                if (announceNonNull.nodeType == "PROPAGATION_NODE" && transferLimit != null) {
                    InfoCard(
                        icon = Icons.Default.Storage,
                        title = "Transfer Size Limit",
                        content = formatSizeLimit(transferLimit),
                        subtitle = "Maximum message size accepted by this relay",
                    )
                }

                // Show interface information if available
                // The Python layer now provides the full interface name including user-configured names
                // (e.g., "TCPInterface[Sideband Server/192.168.1.100:4965]")
                // getReceivingInterfaceInfo() extracts the friendly name from this string
                announceNonNull.receivingInterface?.let { interfaceName ->
                    val interfaceInfo = getReceivingInterfaceInfo(interfaceName)
                    InfoCard(
                        icon = interfaceInfo.icon,
                        title = "Received Via",
                        content = interfaceInfo.text,
                        subtitle = interfaceInfo.subtitle,
                    )
                }

                // Show aspect information
                InfoCard(
                    icon = Icons.Default.Label,
                    title = "Destination Aspect",
                    content = announceNonNull.aspect ?: "unknown",
                    subtitle =
                        when (announceNonNull.aspect) {
                            "lxmf.delivery" -> "LXMF messaging peer"
                            "call.audio" -> "Audio call destination"
                            "lxmf.propagation" -> "Message relay/propagation node"
                            "nomadnetwork.node" -> "NomadNet content node"
                            null -> "No aspect information available"
                            else -> "Unknown service type"
                        },
                    isMonospace = true,
                )

                InfoCard(
                    icon = Icons.Default.AccessTime,
                    title = "Last Seen",
                    content = formatTimeSince(announceNonNull.lastSeenTimestamp),
                    subtitle = formatFullTimestamp(announceNonNull.lastSeenTimestamp),
                )

                // Display stamp cost for propagation nodes (with flexibility)
                if (announceNonNull.nodeType == "PROPAGATION_NODE") {
                    announceNonNull.stampCost?.let { cost ->
                        val flexText = announceNonNull.stampCostFlexibility?.let { " (\u00B1$it)" }.orEmpty()
                        InfoCard(
                            icon = Icons.Default.Lock,
                            title = "Stamp Cost",
                            content = "$cost$flexText",
                            subtitle = "Required proof-of-work difficulty",
                        )
                    }
                    announceNonNull.peeringCost?.let { cost ->
                        InfoCard(
                            icon = Icons.Default.Share,
                            title = "Peering Cost",
                            content = cost.toString(),
                            subtitle = "Cost for node peering",
                        )
                    }
                } else {
                    // Display stamp cost for regular peers
                    announceNonNull.stampCost?.let { cost ->
                        InfoCard(
                            icon = Icons.Default.Lock,
                            title = "Stamp Cost",
                            content = cost.toString(),
                            subtitle = "Required proof-of-work difficulty",
                        )
                    }
                }
            }
        }
    }

    // Show remove confirmation dialog
    if (showRemoveDialog) {
        RemoveContactConfirmationDialog(
            contactName = announce?.peerName ?: "this contact",
            onConfirm = {
                viewModel.toggleContact(destinationHash)
                showRemoveDialog = false
            },
            onDismiss = {
                showRemoveDialog = false
            },
        )
    }

    // Show unset relay confirmation dialog
    if (showUnsetRelayDialog) {
        UnsetRelayConfirmationDialog(
            relayName = announce?.peerName ?: "this relay",
            onAutoSelect = {
                viewModel.unsetRelayAndDelete(destinationHash, autoSelectNew = true)
                showUnsetRelayDialog = false
            },
            onRemoveOnly = {
                viewModel.unsetRelayAndDelete(destinationHash, autoSelectNew = false)
                showUnsetRelayDialog = false
            },
            onDismiss = {
                showUnsetRelayDialog = false
            },
        )
    }
}

@Composable
private fun RemoveContactConfirmationDialog(
    contactName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PersonRemove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text("Remove Contact?")
        },
        text = {
            Text("Remove $contactName from your contacts? You can re-add them later from the Announce Stream.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun UnsetRelayConfirmationDialog(
    relayName: String,
    onAutoSelect: () -> Unit,
    onRemoveOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = {
            Text("Unset as Your Relay?")
        },
        text = {
            Text("\"$relayName\" will be removed from contacts.")
        },
        confirmButton = {
            TextButton(onClick = onAutoSelect) {
                Text("Remove & Auto-Select New")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onRemoveOnly) {
                    Text("Remove Only")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    content: String,
    subtitle: String? = null,
    isMonospace: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Icon
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm:ss", java.util.Locale.getDefault())
    return format.format(date)
}

@Suppress("MagicNumber")
private fun formatSizeLimit(sizeKb: Int): String =
    when {
        sizeKb >= 1024 -> {
            val sizeMb = sizeKb / 1024.0
            if (sizeMb == sizeMb.toLong().toDouble()) {
                "${sizeMb.toLong()} MB"
            } else {
                "%.1f MB".format(sizeMb)
            }
        }
        else -> "$sizeKb KB"
    }
