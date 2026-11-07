package network.columba.app.ui.screens

import android.content.Intent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.R
import network.columba.app.ui.components.IconPickerDialog
import network.columba.app.ui.components.Identicon
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.ui.components.QrCodeImage
import network.columba.app.ui.components.findActivity
import network.columba.app.viewmodel.DebugViewModel
import network.columba.app.viewmodel.SettingsViewModel

/**
 * Consolidated Identity Screen following Material Design 3 best practices.
 *
 * Consolidates identity features from multiple entry points into a single, cohesive screen:
 * - Display name management
 * - QR code sharing
 * - Identity management (if multiple identities exist)
 * - Advanced identity information (collapsible)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdentityScreen(
    onNavigateBack: () -> Unit,
    settingsViewModel: SettingsViewModel,
    onNavigateToIdentityManager: () -> Unit = {},
    debugViewModel: DebugViewModel = hiltViewModel(),
) {
    val settingsState by settingsViewModel.state.collectAsState()

    // Identity data from DebugViewModel
    val identityHash by debugViewModel.identityHash.collectAsState()
    val destinationHash by debugViewModel.destinationHash.collectAsState()
    val publicKey by debugViewModel.publicKey.collectAsState()
    val qrCodeData by debugViewModel.qrCodeData.collectAsState()

    // Display name state
    var displayNameInput by remember { mutableStateOf(settingsState.displayName) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    // Dialog state
    var showQrDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    // Update input when settings state changes
    LaunchedEffect(settingsState.displayName) {
        displayNameInput = settingsState.displayName
    }

    // Auto-dismiss save success message
    LaunchedEffect(showSaveSuccess) {
        if (showSaveSuccess) {
            kotlinx.coroutines.delay(3000)
            showSaveSuccess = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_my_identity_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Display Name & Identity Card
            DisplayNameIdentityCard(
                displayNameInput = displayNameInput,
                onDisplayNameChange = { displayNameInput = it },
                onSave = {
                    settingsViewModel.updateDisplayName(displayNameInput)
                    showSaveSuccess = true
                },
                defaultDisplayName = settingsState.defaultDisplayName,
                currentDisplayName = settingsState.displayName,
                showSaveSuccess = showSaveSuccess,
                identityHash = identityHash,
                onViewQrCode = { showQrDialog = true },
            )

            // 2. Profile Icon Card
            ProfileIconCard(
                iconName = settingsState.iconName,
                foregroundColor = settingsState.iconForegroundColor,
                backgroundColor = settingsState.iconBackgroundColor,
                fallbackHash = publicKey ?: ByteArray(0),
                onEditIcon = { showIconPicker = true },
            )

            // 3. QR Code Quick View Card
            QrCodeQuickCard(
                qrCodeData = qrCodeData,
                displayName = settingsState.displayName,
                onViewFullScreen = { showQrDialog = true },
            )

            // 4. Identity Management Card (TODO: conditionally show if multiple identities exist)
            // IdentityManagementCard(
            //     onManageClick = onNavigateToIdentityManager
            // )

            // 5. Advanced Identity Card (Collapsible)
            AdvancedIdentityCard(
                showAdvanced = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                identityHash = identityHash,
                destinationHash = destinationHash,
                publicKey = publicKey,
            )

            // Bottom spacing for navigation bar
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Full-screen QR Code Dialog
    if (showQrDialog) {
        IdentityQrCodeDialog(
            displayName = settingsState.displayName,
            identityHash = identityHash,
            destinationHash = destinationHash,
            qrCodeData = qrCodeData,
            publicKey = publicKey,
            onDismiss = { showQrDialog = false },
        )
    }

    // Icon Picker Dialog
    if (showIconPicker) {
        IconPickerDialog(
            currentIconName = settingsState.iconName,
            currentForegroundColor = settingsState.iconForegroundColor,
            currentBackgroundColor = settingsState.iconBackgroundColor,
            onConfirm = { iconName, foregroundColor, backgroundColor ->
                settingsViewModel.updateIconAppearance(iconName, foregroundColor, backgroundColor)
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false },
        )
    }
}

/**
 * Display Name & Identity Card
 * Allows users to edit their display name and view basic identity information.
 */
@Composable
private fun DisplayNameIdentityCard(
    displayNameInput: String,
    onDisplayNameChange: (String) -> Unit,
    onSave: () -> Unit,
    defaultDisplayName: String,
    currentDisplayName: String,
    showSaveSuccess: Boolean,
    identityHash: String?,
    onViewQrCode: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = stringResource(R.string.identity_icon_cd),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.identity_display_name_identity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = stringResource(R.string.identity_display_name_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Current effective display name
            Text(
                text = stringResource(R.string.identity_current_name, currentDisplayName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )

            // Default display name info
            Text(
                text = stringResource(R.string.identity_default_name, defaultDisplayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Input field
            OutlinedTextField(
                value = displayNameInput,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.identity_custom_display_name)) },
                placeholder = { Text(stringResource(R.string.identity_leave_empty_default)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
            )

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Save button - only enabled when there are actual changes
                val hasChanges = displayNameInput.trim() != currentDisplayName
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = hasChanges,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.identity_save))
                }

                // View QR Code button
                OutlinedButton(
                    onClick = onViewQrCode,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.identity_view_qr))
                }
            }

            // Success message
            AnimatedVisibility(
                visible = showSaveSuccess,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.identity_display_name_saved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Identity hash preview
            if (identityHash != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = stringResource(R.string.identity_hash),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = identityHash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Profile Icon Card
 * Shows the current profile icon with an option to customize it.
 */
@Composable
internal fun ProfileIconCard(
    iconName: String?,
    foregroundColor: String?,
    backgroundColor: String?,
    fallbackHash: ByteArray,
    onEditIcon: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = stringResource(R.string.identity_profile_icon),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.identity_profile_icon),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = stringResource(R.string.identity_profile_icon_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Icon preview and edit button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon preview
                if (iconName != null && foregroundColor != null && backgroundColor != null) {
                    ProfileIcon(
                        iconName = iconName,
                        foregroundColor = foregroundColor,
                        backgroundColor = backgroundColor,
                        size = 64.dp,
                        fallbackHash = fallbackHash,
                    )
                } else {
                    // Show identicon as fallback
                    Identicon(
                        hash = fallbackHash,
                        size = 64.dp,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (iconName != null) stringResource(R.string.identity_custom_icon, iconName) else stringResource(R.string.identity_using_identicon),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text =
                            if (iconName != null) {
                                stringResource(R.string.identity_change_icon_hint)
                            } else {
                                stringResource(R.string.identity_identicon_auto)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Edit button
            OutlinedButton(
                onClick = onEditIcon,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(if (iconName != null) R.string.identity_change_icon else R.string.identity_choose_custom_icon))
            }
        }
    }
}

/**
 * QR Code Quick View Card
 * Shows QR code inline or provides button to view full-screen.
 */
@Composable
private fun QrCodeQuickCard(
    qrCodeData: String?,
    displayName: String,
    onViewFullScreen: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = stringResource(R.string.identity_qr_code_cd),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.identity_share_your_identity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // QR Code Display
            if (qrCodeData != null) {
                QrCodeImage(
                    data = qrCodeData,
                    size = 200.dp,
                )

                Text(
                    text = stringResource(R.string.identity_scan_to_add, displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Loading state
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.identity_generating_qr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // View full-screen button
                OutlinedButton(
                    onClick = onViewFullScreen,
                    modifier = Modifier.weight(1f),
                    enabled = qrCodeData != null,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.identity_full_screen))
                }

                // Share button
                Button(
                    onClick = {
                        qrCodeData?.let { data ->
                            val shareText = context.getString(R.string.identity_share_text, displayName, data)
                            val sendIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                            context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.identity_share_chooser)))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = qrCodeData != null,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.identity_share))
                }
            }
        }
    }
}

/**
 * Advanced Identity Card (Collapsible)
 * Shows detailed identity information: hash, destination hash, public key.
 */
@Composable
private fun AdvancedIdentityCard(
    showAdvanced: Boolean,
    onToggle: () -> Unit,
    identityHash: String?,
    destinationHash: String?,
    publicKey: ByteArray?,
) {
    val clipboardManager = LocalClipboardManager.current

    // Help dialog state
    var showIdentityHashHelp by remember { mutableStateOf(false) }
    var showDestinationHashHelp by remember { mutableStateOf(false) }
    var showPublicKeyHelp by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Collapsible header
            TextButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (showAdvanced) R.string.identity_hide_advanced else R.string.identity_show_advanced),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (showAdvanced) R.string.identity_hide_advanced else R.string.identity_show_advanced),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // Advanced content
            AnimatedVisibility(
                visible = showAdvanced,
                enter = fadeIn(animationSpec = tween(200)) + expandVertically(),
                exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Identity Hash
                    if (identityHash != null) {
                        IdentityHashRow(
                            label = stringResource(R.string.identity_hash),
                            value = identityHash,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(identityHash))
                            },
                            onShowHelp = { showIdentityHashHelp = true },
                        )
                    }

                    // Destination Hash
                    if (destinationHash != null) {
                        IdentityHashRow(
                            label = stringResource(R.string.identity_destination_hash_lxmf),
                            value = destinationHash,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(destinationHash))
                            },
                            onShowHelp = { showDestinationHashHelp = true },
                        )
                    }

                    // Public Key
                    if (publicKey != null) {
                        val publicKeyHex = publicKey.joinToString("") { "%02x".format(it) }
                        IdentityHashRow(
                            label = stringResource(R.string.identity_public_key),
                            value = publicKeyHex,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(publicKeyHex))
                            },
                            onShowHelp = { showPublicKeyHelp = true },
                        )
                    }
                }
            }
        }
    }

    // Help Dialogs
    if (showIdentityHashHelp) {
        AlertDialog(
            onDismissRequest = { showIdentityHashHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(R.string.identity_what_is_identity_hash)) },
            text = {
                Text(
                    stringResource(R.string.identity_identity_hash_help),
                )
            },
            confirmButton = {
                TextButton(onClick = { showIdentityHashHelp = false }) {
                    Text(stringResource(R.string.identity_got_it))
                }
            },
        )
    }

    if (showDestinationHashHelp) {
        AlertDialog(
            onDismissRequest = { showDestinationHashHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(R.string.identity_what_is_destination_hash)) },
            text = {
                Text(
                    stringResource(R.string.identity_destination_hash_help),
                )
            },
            confirmButton = {
                TextButton(onClick = { showDestinationHashHelp = false }) {
                    Text(stringResource(R.string.identity_got_it))
                }
            },
        )
    }

    if (showPublicKeyHelp) {
        AlertDialog(
            onDismissRequest = { showPublicKeyHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(R.string.identity_what_is_public_key)) },
            text = {
                Text(
                    stringResource(R.string.identity_public_key_help),
                )
            },
            confirmButton = {
                TextButton(onClick = { showPublicKeyHelp = false }) {
                    Text(stringResource(R.string.identity_got_it))
                }
            },
        )
    }
}

/**
 * Reusable row for displaying identity hashes with copy button.
 */
@Composable
private fun IdentityHashRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    onShowHelp: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Info icon - shows help dialog
            IconButton(
                onClick = onShowHelp,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.identity_info_about, label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.identity_copy_about, label),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Full-screen QR Code Dialog
 * Shows large QR code with share and copy functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityQrCodeDialog(
    displayName: String,
    identityHash: String?,
    destinationHash: String?,
    qrCodeData: String?,
    publicKey: ByteArray?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
    ) {
        // Max brightness + keep screen on while showing QR code
        DisposableEffect(Unit) {
            val window = context.findActivity().window
            val originalBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.also { it.screenBrightness = 1f }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window.attributes =
                    window.attributes.also {
                        it.screenBrightness = originalBrightness
                    }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.identity_share_your_identity)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.identity_qr_close),
                                )
                            }
                        },
                        actions = {
                            // Share action
                            IconButton(
                                onClick = {
                                    qrCodeData?.let { data ->
                                        val shareText =
                                            buildString {
                                                appendLine(context.getString(R.string.identity_share_header))
                                                appendLine()
                                                appendLine(context.getString(R.string.identity_share_name, displayName))
                                                if (destinationHash != null) {
                                                    appendLine(context.getString(R.string.identity_share_destination, destinationHash))
                                                }
                                                appendLine()
                                                appendLine(context.getString(R.string.identity_share_scan_use))
                                                append(data)
                                            }
                                        val sendIntent =
                                            Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                                type = "text/plain"
                                            }
                                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.identity_share_chooser)))
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.identity_share),
                                )
                            }
                        },
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // Display name
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    // QR Code
                    if (qrCodeData != null) {
                        QrCodeImage(
                            data = qrCodeData,
                            size = 280.dp,
                        )

                        Text(
                            text = stringResource(R.string.identity_qr_scan_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.identity_generating_qr),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Copy link button
                    if (qrCodeData != null) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(qrCodeData))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.identity_copy_link))
                        }
                    }

                    HorizontalDivider()

                    // Identity details
                    if (identityHash != null) {
                        DetailRow(
                            label = stringResource(R.string.identity_hash),
                            value = identityHash,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(identityHash))
                            },
                        )
                    }

                    if (destinationHash != null) {
                        DetailRow(
                            label = stringResource(R.string.identity_destination_hash),
                            value = destinationHash,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(destinationHash))
                            },
                        )
                    }

                    // Bottom spacing for navigation bar
                    Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.identity_copy),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
