package com.lxmf.messenger.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.QrCodeImage
import com.lxmf.messenger.viewmodel.ApkSharingViewModel

/**
 * Screen for sharing the Columba APK with another device.
 *
 * Provides two sharing methods:
 * 1. QR code + local HTTP server: Both devices must be on the same WiFi network.
 *    The receiver scans the QR code, which opens a download page in their browser.
 * 2. Android share sheet: Share the APK via Bluetooth, Nearby Share, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkSharingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ApkSharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share Columba") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // WiFi sharing section
            WifiSharingSection(
                isServerRunning = state.isServerRunning,
                downloadUrl = state.downloadUrl,
                errorMessage = state.errorMessage,
                apkSizeBytes = state.apkSizeBytes,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Alternative sharing section
            AlternativeSharingSection(
                onShareViaIntent = {
                    val intent = viewModel.createShareIntent()
                    if (intent != null) {
                        context.startActivity(
                            Intent.createChooser(intent, "Share Columba APK"),
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun WifiSharingSection(
    isServerRunning: Boolean,
    downloadUrl: String?,
    errorMessage: String?,
    apkSizeBytes: Long,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Status icon
        Icon(
            imageVector = if (isServerRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (isServerRunning) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Text(
            text = "Share via WiFi",
            style = MaterialTheme.typography.titleLarge,
        )

        if (errorMessage != null) {
            // Error state
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (isServerRunning && downloadUrl != null) {
            // QR Code
            Text(
                text = "Have the other person scan this QR code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            QrCodeImage(
                data = downloadUrl,
                size = 280.dp,
            )

            // URL display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = downloadUrl,
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // APK size info
            if (apkSizeBytes > 0) {
                val sizeMb = apkSizeBytes / (1024.0 * 1024.0)
                Text(
                    text = "APK size: ${"%.1f".format(sizeMb)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Instructions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    InstructionStep("1.", "Both phones must be on the same WiFi network")
                    InstructionStep("2.", "Open the camera app on the other phone and scan the QR code above")
                    InstructionStep("3.", "Tap the link to open it in a browser")
                    InstructionStep("4.", "Download and install the APK")
                }
            }
        } else {
            // Loading / starting
            Text(
                text = "Starting sharing server...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Text(
        text = "$number $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun AlternativeSharingSection(
    onShareViaIntent: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Or share another way",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Use Bluetooth, Nearby Share, or any other installed sharing app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        OutlinedButton(
            onClick = onShareViaIntent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share APK via...")
        }
    }
}
