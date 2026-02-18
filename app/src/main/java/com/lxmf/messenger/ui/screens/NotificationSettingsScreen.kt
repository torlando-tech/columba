package com.lxmf.messenger.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.viewmodel.NotificationSettingsViewModel

/**
 * Screen for managing notification preferences.
 * Allows users to enable/disable different types of notifications.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Track permission state
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PermissionChecker.PERMISSION_GRANTED
            } else {
                true // No permission needed for Android 12 and below
            },
        )
    }

    // Permission launcher for Android 13+
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                // Permission granted, enable notifications
                viewModel.toggleNotificationsEnabled(true)
            } else {
                // Permission denied, keep notifications disabled
                viewModel.toggleNotificationsEnabled(false)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
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
                // Master toggle card
                MasterNotificationToggleCard(
                    enabled = state.notificationsEnabled,
                    onToggle = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            // Request permission before enabling
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Permission already granted or not needed, just toggle
                            viewModel.toggleNotificationsEnabled(enabled)
                        }
                    },
                )

                // Notification types card
                NotificationTypesCard(
                    masterEnabled = state.notificationsEnabled,
                    receivedMessage = state.receivedMessage,
                    onReceivedMessageToggle = { viewModel.toggleReceivedMessage(it) },
                    receivedMessageFavorite = state.receivedMessageFavorite,
                    onReceivedMessageFavoriteToggle = { viewModel.toggleReceivedMessageFavorite(it) },
                    heardAnnounce = state.heardAnnounce,
                    onHeardAnnounceToggle = { viewModel.toggleHeardAnnounce(it) },
                    announceDirectOnly = state.announceDirectOnly,
                    onAnnounceDirectOnlyToggle = { viewModel.toggleAnnounceDirectOnly(it) },
                    announceExcludeTcp = state.announceExcludeTcp,
                    onAnnounceExcludeTcpToggle = { viewModel.toggleAnnounceExcludeTcp(it) },
                    bleConnected = state.bleConnected,
                    onBleConnectedToggle = { viewModel.toggleBleConnected(it) },
                    bleDisconnected = state.bleDisconnected,
                    onBleDisconnectedToggle = { viewModel.toggleBleDisconnected(it) },
                )

                // Bottom spacing for navigation bar
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
fun MasterNotificationToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (enabled) {
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
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (enabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Enable Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text =
                        if (enabled) {
                            "You will receive notifications based on your preferences below"
                        } else {
                            "All notifications are disabled"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
fun NotificationTypesCard(
    masterEnabled: Boolean,
    receivedMessage: Boolean,
    onReceivedMessageToggle: (Boolean) -> Unit,
    receivedMessageFavorite: Boolean,
    onReceivedMessageFavoriteToggle: (Boolean) -> Unit,
    heardAnnounce: Boolean,
    onHeardAnnounceToggle: (Boolean) -> Unit,
    announceDirectOnly: Boolean,
    onAnnounceDirectOnlyToggle: (Boolean) -> Unit,
    announceExcludeTcp: Boolean,
    onAnnounceExcludeTcpToggle: (Boolean) -> Unit,
    bleConnected: Boolean,
    onBleConnectedToggle: (Boolean) -> Unit,
    bleDisconnected: Boolean,
    onBleDisconnectedToggle: (Boolean) -> Unit,
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
            Text(
                text = "Notification Types",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Select which events you want to be notified about",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // Individual notification type switches
            NotificationTypeItem(
                icon = Icons.Default.Mail,
                title = "Received Message",
                description = "Notify when you receive a new message",
                enabled = masterEnabled,
                checked = receivedMessage,
                onCheckedChange = onReceivedMessageToggle,
            )

            NotificationTypeItem(
                icon = Icons.Default.Star,
                title = "Message from Saved Peer",
                description = "Notify when you receive a message from a saved peer",
                enabled = masterEnabled,
                checked = receivedMessageFavorite,
                onCheckedChange = onReceivedMessageFavoriteToggle,
            )

            NotificationTypeItem(
                icon = Icons.Default.Sensors,
                title = "Heard Announce",
                description = "Notify when you hear a new announce from a peer",
                enabled = masterEnabled,
                checked = heardAnnounce,
                onCheckedChange = onHeardAnnounceToggle,
            )

            // Announce sub-options (only shown when heard announce is enabled)
            if (heardAnnounce) {
                NotificationTypeItem(
                    icon = Icons.Default.NearMe,
                    title = "Direct Only",
                    description = "Only notify for direct (1-hop) announces from nearby peers",
                    enabled = masterEnabled,
                    checked = announceDirectOnly,
                    onCheckedChange = onAnnounceDirectOnlyToggle,
                    indented = true,
                )

                NotificationTypeItem(
                    icon = Icons.Default.WifiOff,
                    title = "Exclude TCP",
                    description = "Skip announces received via TCP interfaces",
                    enabled = masterEnabled,
                    checked = announceExcludeTcp,
                    onCheckedChange = onAnnounceExcludeTcpToggle,
                    indented = true,
                )
            }

            NotificationTypeItem(
                icon = Icons.Default.Bluetooth,
                title = "BLE Peer Connected",
                description = "Notify when a Bluetooth LE peer connects",
                enabled = masterEnabled,
                checked = bleConnected,
                onCheckedChange = onBleConnectedToggle,
            )

            NotificationTypeItem(
                icon = Icons.Default.BluetoothDisabled,
                title = "BLE Peer Disconnected",
                description = "Notify when a Bluetooth LE peer disconnects",
                enabled = masterEnabled,
                checked = bleDisconnected,
                onCheckedChange = onBleDisconnectedToggle,
            )
        }
    }
}

@Composable
fun NotificationTypeItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indented: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .padding(start = if (indented) 36.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 0.7f else 0.38f,
                        ),
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
