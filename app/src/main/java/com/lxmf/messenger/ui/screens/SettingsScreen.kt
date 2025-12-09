package com.lxmf.messenger.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.screens.settings.cards.AutoAnnounceCard
import com.lxmf.messenger.ui.screens.settings.cards.BatteryOptimizationCard
import com.lxmf.messenger.ui.screens.settings.cards.DataMigrationCard
import com.lxmf.messenger.ui.screens.settings.cards.IdentityCard
import com.lxmf.messenger.ui.screens.settings.cards.NetworkCard
import com.lxmf.messenger.ui.screens.settings.cards.NotificationSettingsCard
import com.lxmf.messenger.ui.screens.settings.cards.SharedInstanceBannerCard
import com.lxmf.messenger.ui.screens.settings.cards.ThemeSelectionCard
import com.lxmf.messenger.ui.screens.settings.dialogs.IdentityQrCodeDialog
import com.lxmf.messenger.viewmodel.DebugViewModel
import com.lxmf.messenger.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    debugViewModel: DebugViewModel = hiltViewModel(),
    onNavigateToInterfaces: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    onNavigateToNetworkStatus: () -> Unit = {},
    onNavigateToIdentityManager: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToCustomThemes: () -> Unit = {},
    onNavigateToMigration: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val qrCodeData by debugViewModel.qrCodeData.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show Snackbar when shared instance becomes available (ephemeral notification)
    LaunchedEffect(state.sharedInstanceAvailable) {
        if (state.sharedInstanceAvailable && !state.preferOwnInstance) {
            val result = snackbarHostState.showSnackbar(
                message = "Shared instance available",
                actionLabel = "Switch",
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.switchToSharedInstance()
                SnackbarResult.Dismissed -> viewModel.dismissSharedInstanceAvailable()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                // Shared instance banner - show when shared instance is relevant:
                // 1. Connected to shared instance
                // 2. Shared instance available (detected while running own)
                // 3. Shared instance was lost (show warning)
                // 4. Service is restarting (keep visible during transition)
                // Hidden when: running own instance with no shared instance detected
                if (state.isSharedInstance || state.sharedInstanceAvailable ||
                    state.sharedInstanceLost || state.isRestarting
                ) {
                    SharedInstanceBannerCard(
                        isExpanded = state.isSharedInstanceBannerExpanded,
                        preferOwnInstance = state.preferOwnInstance,
                        isUsingSharedInstance = state.isSharedInstance,
                        rpcKey = state.rpcKey,
                        sharedInstanceLost = state.sharedInstanceLost,
                        sharedInstanceAvailable = state.sharedInstanceAvailable,
                        onExpandToggle = { viewModel.toggleSharedInstanceBannerExpanded(it) },
                        onTogglePreferOwnInstance = { viewModel.togglePreferOwnInstance(it) },
                        onRpcKeyChange = { viewModel.saveRpcKey(it) },
                        onSwitchToOwnInstance = { viewModel.switchToOwnInstanceAfterLoss() },
                        onDismissLostWarning = { viewModel.dismissSharedInstanceLostWarning() },
                    )
                }

                NetworkCard(
                    onViewStatus = onNavigateToNetworkStatus,
                    onManageInterfaces = onNavigateToInterfaces,
                    isSharedInstance = state.isSharedInstance,
                )

                IdentityCard(
                    onViewIdentity = onNavigateToIdentity,
                    onManageIdentities = onNavigateToIdentityManager,
                )

                NotificationSettingsCard(
                    onManageClick = onNavigateToNotifications,
                )

                AutoAnnounceCard(
                    enabled = state.autoAnnounceEnabled,
                    intervalMinutes = state.autoAnnounceIntervalMinutes,
                    lastAnnounceTime = state.lastAutoAnnounceTime,
                    isManualAnnouncing = state.isManualAnnouncing,
                    showManualAnnounceSuccess = state.showManualAnnounceSuccess,
                    manualAnnounceError = state.manualAnnounceError,
                    onToggle = { viewModel.toggleAutoAnnounce(it) },
                    onIntervalChange = { viewModel.setAnnounceInterval(it) },
                    onManualAnnounce = { viewModel.triggerManualAnnounce() },
                )

                ThemeSelectionCard(
                    selectedTheme = state.selectedTheme,
                    customThemes = state.customThemes,
                    onThemeChange = { viewModel.setTheme(it) },
                    onNavigateToCustomThemes = onNavigateToCustomThemes,
                )

                BatteryOptimizationCard()

                DataMigrationCard(
                    onNavigateToMigration = onNavigateToMigration,
                )

                // Bottom spacing for navigation bar
                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        // QR Code Dialog
        if (state.showQrDialog && state.identityHash != null) {
            val context = LocalContext.current

            IdentityQrCodeDialog(
                displayName = state.displayName,
                identityHash = state.identityHash,
                destinationHash = state.destinationHash,
                qrCodeData = qrCodeData,
                onDismiss = { viewModel.toggleQrDialog(false) },
                onShareClick = {
                    val shareText = debugViewModel.generateShareText(state.displayName)
                    if (shareText != null) {
                        val sendIntent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Identity")
                        context.startActivity(shareIntent)
                    }
                },
            )
        }

        // Service Restart Dialog
        if (state.isRestarting) {
            ServiceRestartDialog()
        }
    }
}

@Composable
private fun ServiceRestartDialog() {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss - blocking */ },
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
            )
        },
        title = { Text("Restarting Service") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Restarting Reticulum network...",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This may take a few seconds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { /* No button - auto dismisses when done */ },
    )
}
