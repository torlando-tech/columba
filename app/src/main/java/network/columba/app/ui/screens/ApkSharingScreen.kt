package network.columba.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.R
import network.columba.app.service.LocalHotspotManager
import network.columba.app.ui.components.QrCodeImage
import network.columba.app.viewmodel.ApkSharingViewModel
import network.columba.app.viewmodel.SharingMode
import network.columba.app.viewmodel.UiMessage

/**
 * Screen for sharing the Columba APK with another device.
 *
 * Provides three sharing methods:
 * 1. QR code + local HTTP server over existing WiFi network.
 * 2. QR code + local HTTP server over a device-created WiFi hotspot (no network needed).
 * 3. Android share sheet: Share the APK via Bluetooth, Nearby Share, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkSharingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ApkSharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.settings_share_columba_apk)

    // Permission launcher for hotspot permissions
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (grants.values.all { it }) {
                viewModel.onHotspotPermissionGranted()
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_share_columba_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Main sharing section (WiFi or Hotspot)
            SharingSection(
                isServerRunning = state.isServerRunning,
                downloadUrl = state.downloadUrl,
                errorMessage = state.errorMessage,
                apkSizeBytes = state.apkSizeBytes,
                sharingMode = state.sharingMode,
                hotspotSsid = state.hotspotSsid,
                hotspotPassword = state.hotspotPassword,
                needsHotspotPermission = state.needsHotspotPermission,
                isHotspotStarting = state.isHotspotStarting,
                onStartHotspot = {
                    val perms = viewModel.getRequiredHotspotPermissions()
                    if (perms.isNotEmpty()) {
                        permissionLauncher.launch(perms)
                    } else {
                        viewModel.startHotspotSharing()
                    }
                },
                onRequestPermissions = {
                    permissionLauncher.launch(viewModel.getRequiredHotspotPermissions())
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Alternative sharing section
            AlternativeSharingSection(
                onShareViaIntent = {
                    val intent = viewModel.createShareIntent()
                    if (intent != null) {
                        context.startActivity(
                            Intent.createChooser(intent, chooserTitle),
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SharingSection(
    isServerRunning: Boolean,
    downloadUrl: String?,
    errorMessage: UiMessage?,
    apkSizeBytes: Long,
    sharingMode: SharingMode?,
    hotspotSsid: String?,
    hotspotPassword: String?,
    needsHotspotPermission: Boolean,
    isHotspotStarting: Boolean,
    onStartHotspot: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Status icon
        Icon(
            imageVector =
                when {
                    sharingMode == SharingMode.HOTSPOT -> Icons.Default.WifiTethering
                    isServerRunning -> Icons.Default.Wifi
                    else -> Icons.Default.WifiOff
                },
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint =
                if (isServerRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        Text(
            text =
                stringResource(
                    when (sharingMode) {
                        SharingMode.HOTSPOT -> R.string.apk_sharing_via_hotspot
                        SharingMode.WIFI, null -> R.string.apk_sharing_via_wifi
                    },
                ),
        )

        when {
            isHotspotStarting -> {
                // Hotspot is starting up
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(
                    text = stringResource(R.string.apk_sharing_starting_hotspot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMessage != null && !needsHotspotPermission -> {
                // Error state with optional hotspot button
                ErrorCard(errorMessage.resolve(context))

                // Offer hotspot as fallback when WiFi isn't available
                if (LocalHotspotManager.isSupported() && sharingMode == null) {
                    HotspotFallbackSection(onStartHotspot = onStartHotspot)
                }
            }

            needsHotspotPermission -> {
                // Need permissions before starting hotspot
                PermissionRequestSection(onRequestPermissions = onRequestPermissions)
            }

            isServerRunning && downloadUrl != null -> {
                if (sharingMode == SharingMode.HOTSPOT && hotspotSsid != null) {
                    // Hotspot mode: two-step QR flow
                    val password = hotspotPassword ?: ""

                    // Step 1: WiFi credentials QR code
                    Text(
                        text = stringResource(R.string.apk_sharing_step, 1, stringResource(R.string.apk_sharing_step_connect_hotspot)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    QrCodeImage(
                        data = buildWifiQrData(hotspotSsid, password),
                        size = 280.dp,
                    )

                    // Fallback: show credentials as text
                    HotspotCredentialsFallback(
                        ssid = hotspotSsid,
                        password = password,
                    )

                    // Step 2: Download URL QR code
                    Text(
                        text = stringResource(R.string.apk_sharing_step, 2, stringResource(R.string.apk_sharing_step_download)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    QrCodeImage(
                        data = downloadUrl,
                        size = 280.dp,
                    )
                } else {
                    // WiFi mode: single QR code
                    Text(
                        text = stringResource(R.string.apk_sharing_scan_qr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    QrCodeImage(
                        data = downloadUrl,
                        size = 280.dp,
                    )
                }

                // URL display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = downloadUrl,
                        modifier =
                            Modifier
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
                        text = stringResource(R.string.apk_sharing_apk_size, sizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Instructions
                InstructionsCard(sharingMode = sharingMode ?: SharingMode.WIFI)
            }

            else -> {
                // Loading / starting
                Text(
                    text = stringResource(R.string.apk_sharing_starting_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(errorMessage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
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
}

@Composable
private fun HotspotFallbackSection(onStartHotspot: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.apk_sharing_no_wifi_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Button(onClick = onStartHotspot) {
            Icon(
                imageVector = Icons.Default.WifiTethering,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.apk_sharing_start_hotspot))
        }
    }
}

@Composable
private fun PermissionRequestSection(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.apk_sharing_permission_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequestPermissions) {
                Text(stringResource(R.string.apk_sharing_grant_permission))
            }
        }
    }
}

/**
 * Build a WIFI QR code URI from SSID and password.
 *
 * Uses the standard `WIFI:T:WPA;S:<ssid>;P:<password>;;` format
 * that Android and iOS camera apps recognise for auto-connecting.
 * Special characters (`\`, `;`, `,`, `"`, `:`) are backslash-escaped
 * per the spec.
 */
private fun buildWifiQrData(
    ssid: String,
    password: String,
): String {
    fun escape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\"", "\\\"")
            .replace(":", "\\:")

    return if (password.isEmpty()) {
        "WIFI:T:nopass;S:${escape(ssid)};;"
    } else {
        "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
    }
}

/** Small fallback text showing hotspot credentials for manual entry. */
@Composable
private fun HotspotCredentialsFallback(
    ssid: String,
    password: String,
) {
    Text(
        text =
            if (password.isNotEmpty()) {
                stringResource(R.string.apk_sharing_network_password, ssid, password)
            } else {
                stringResource(R.string.apk_sharing_network_open, ssid)
            },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun InstructionsCard(sharingMode: SharingMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.apk_sharing_instructions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (sharingMode == SharingMode.HOTSPOT) {
                InstructionStep(1, R.string.apk_sharing_instr_hotspot_1)
                InstructionStep(2, R.string.apk_sharing_instr_hotspot_2)
                InstructionStep(3, R.string.apk_sharing_instr_download_install)
            } else {
                InstructionStep(1, R.string.apk_sharing_instr_wifi_1)
                InstructionStep(2, R.string.apk_sharing_instr_wifi_2)
                InstructionStep(3, R.string.apk_sharing_instr_wifi_3)
                InstructionStep(4, R.string.apk_sharing_instr_download_install)
            }
        }
    }
}

@Composable
private fun InstructionStep(
    number: Int,
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(R.string.apk_sharing_instruction_step, number, stringResource(textRes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun AlternativeSharingSection(onShareViaIntent: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.apk_sharing_or_share),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.apk_sharing_alt_share_desc),
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
            Text(stringResource(R.string.apk_sharing_share_via))
        }
    }
}
