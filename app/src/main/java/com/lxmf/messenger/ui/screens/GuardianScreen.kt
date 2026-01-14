package com.lxmf.messenger.ui.screens

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.db.entity.PairedChildEntity
import com.lxmf.messenger.viewmodel.GuardianViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianScreen(
    onBackClick: () -> Unit = {},
    onScanQrCode: () -> Unit = {},
    viewModel: GuardianViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dialog states
    var showUnpairDialog by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showRemoveContactDialog by remember { mutableStateOf<String?>(null) }
    var newContactHash by remember { mutableStateOf("") }

    // Load state on first composition
    LaunchedEffect(Unit) {
        Log.d("GuardianScreen", "LaunchedEffect - calling loadGuardianState()")
        viewModel.loadGuardianState()
    }

    // Debug log state changes
    LaunchedEffect(state) {
        Log.d("GuardianScreen", "State: isLoading=${state.isLoading}, hasGuardian=${state.hasGuardian}, isGeneratingQr=${state.isGeneratingQr}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parental Controls") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.hasGuardian) {
                    // ============ CHILD DEVICE VIEW ============
                    ChildDeviceSection(
                        guardianName = state.guardianName,
                        isLocked = state.isLocked,
                        lockedTimestamp = state.lockedTimestamp,
                        allowedContacts = state.allowedContacts,
                        onUnpairClick = { showUnpairDialog = true },
                    )
                } else {
                    // ============ PARENT/UNCONFIGURED DEVICE VIEW ============
                    ParentDeviceSection(
                        qrCodeBitmap = state.qrCodeBitmap,
                        isGeneratingQr = state.isGeneratingQr,
                        isSendingCommand = state.isSendingCommand,
                        pairedChildren = state.pairedChildren,
                        onGenerateQr = {
                            Log.d("GuardianScreen", "onGenerateQr clicked!")
                            viewModel.generatePairingQr()
                        },
                        onScanChildQr = onScanQrCode,
                        onLockChild = { childHash -> viewModel.lockChild(childHash) },
                        onUnlockChild = { childHash -> viewModel.unlockChild(childHash) },
                        onRemoveChild = { childHash -> viewModel.removePairedChild(childHash) },
                    )
                }

                // Help/info section
                HelpCard()
            }
        }

        // Unpair confirmation dialog
        if (showUnpairDialog) {
            AlertDialog(
                onDismissRequest = { showUnpairDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text("Remove Parental Controls?") },
                text = {
                    Text(
                        "This will remove all parental control restrictions from this device. " +
                            "The guardian will no longer be able to control messaging on this device.\n\n" +
                            "Note: Factory reset will also clear parental controls.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.unpairFromGuardian()
                                showUnpairDialog = false
                                Toast.makeText(context, "Parental controls removed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnpairDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        // Add contact dialog (for when guardian sends ALLOW_ADD but we also want manual option)
        if (showAddContactDialog) {
            AlertDialog(
                onDismissRequest = { showAddContactDialog = false },
                title = { Text("Add Allowed Contact") },
                text = {
                    Column {
                        Text(
                            "Enter the destination hash of a contact to allow messaging with them.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = newContactHash,
                            onValueChange = { newContactHash = it },
                            label = { Text("Destination Hash") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newContactHash.isNotBlank()) {
                                scope.launch {
                                    viewModel.addAllowedContact(newContactHash.trim(), null)
                                    newContactHash = ""
                                    showAddContactDialog = false
                                    Toast.makeText(context, "Contact added to allow list", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddContactDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        // Remove contact dialog
        showRemoveContactDialog?.let { contactHash ->
            AlertDialog(
                onDismissRequest = { showRemoveContactDialog = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text("Remove Contact?") },
                text = {
                    Text("Remove this contact from the allowed list? They will no longer be able to send or receive messages when parental controls are locked.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.removeAllowedContact(contactHash)
                                showRemoveContactDialog = null
                                Toast.makeText(context, "Contact removed from allow list", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveContactDialog = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun ChildDeviceSection(
    guardianName: String?,
    isLocked: Boolean,
    lockedTimestamp: Long,
    allowedContacts: List<Pair<String, String?>>,
    onUnpairClick: () -> Unit,
) {
    // Status card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isLocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isLocked) "Parental Controls Active" else "Parental Controls Inactive",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Guardian: ${guardianName ?: "Unknown"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isLocked) {
                HorizontalDivider()
                Text(
                    text = "Messaging is restricted to ${allowedContacts.size} allowed contact${if (allowedContacts.size != 1) "s" else ""} plus your guardian.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Allowed contacts list
    if (allowedContacts.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Allowed Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                allowedContacts.forEach { (hash, name) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = hash.take(16) + "...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Unpair button
    OutlinedButton(
        onClick = onUnpairClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Remove Parental Controls")
    }
}

@Composable
private fun ParentDeviceSection(
    qrCodeBitmap: Bitmap?,
    isGeneratingQr: Boolean,
    isSendingCommand: Boolean,
    pairedChildren: List<PairedChildEntity>,
    onGenerateQr: () -> Unit,
    onScanChildQr: () -> Unit,
    onLockChild: (String) -> Unit,
    onUnlockChild: (String) -> Unit,
    onRemoveChild: (String) -> Unit,
) {
    // Show paired children first (if any)
    if (pairedChildren.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Managed Devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${pairedChildren.size} child device(s) paired",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                // List each paired child
                pairedChildren.forEach { child ->
                    PairedChildItem(
                        child = child,
                        isSendingCommand = isSendingCommand,
                        onLock = { onLockChild(child.childDestinationHash) },
                        onUnlock = { onUnlockChild(child.childDestinationHash) },
                        onRemove = { onRemoveChild(child.childDestinationHash) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // No guardian configured - show pairing options
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Parental Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Not configured on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Parent device options
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Set Up as Parent Device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Generate a QR code for a child to scan to establish parental control.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // QR Code display or generate button
            if (qrCodeBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qrCodeBitmap.asImageBitmap(),
                        contentDescription = "Guardian pairing QR code",
                        modifier = Modifier.size(200.dp),
                    )
                }
                Text(
                    text = "Have the child scan this QR code to pair",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onGenerateQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Regenerate QR Code")
                }
            } else {
                Button(
                    onClick = onGenerateQr,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGeneratingQr,
                ) {
                    if (isGeneratingQr) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate Pairing QR Code")
                }
            }
        }
    }

    // Child device options
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Set Up as Child Device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Scan a QR code from a parent device to enable parental controls.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onScanChildQr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Guardian QR Code")
            }
        }
    }
}

@Composable
private fun HelpCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "How It Works",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "1. Parent generates a QR code on their device\n" +
                    "2. Child scans the QR code to pair\n" +
                    "3. Parent can then send LOCK/UNLOCK commands\n" +
                    "4. When locked, child can only message allowed contacts\n" +
                    "5. Parent can add/remove allowed contacts remotely",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Security Notes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "• All commands are cryptographically signed\n" +
                    "• Only the paired guardian can control this device\n" +
                    "• Factory reset will clear all parental controls",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Bottom spacing
    Spacer(modifier = Modifier.height(80.dp))
}

@Composable
private fun PairedChildItem(
    child: PairedChildEntity,
    isSendingCommand: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onRemove: () -> Unit,
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (child.isLocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (child.isLocked) Icons.Default.Lock else Icons.Default.Person,
                        contentDescription = null,
                        tint = if (child.isLocked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = child.displayName ?: "Child Device",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = child.childDestinationHash.take(12) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Status badge
                Text(
                    text = if (child.isLocked) "LOCKED" else "UNLOCKED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (child.isLocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Bold,
                )
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (child.isLocked) {
                    Button(
                        onClick = onUnlock,
                        modifier = Modifier.weight(1f),
                        enabled = !isSendingCommand,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (isSendingCommand) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unlock")
                    }
                } else {
                    Button(
                        onClick = onLock,
                        modifier = Modifier.weight(1f),
                        enabled = !isSendingCommand,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        if (isSendingCommand) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Lock")
                    }
                }

                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    enabled = !isSendingCommand,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    // Remove confirmation dialog
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Remove Paired Device?") },
            text = {
                Text("This will remove this device from your managed devices. " +
                    "You will need to re-pair if you want to manage it again.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove()
                        showRemoveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
