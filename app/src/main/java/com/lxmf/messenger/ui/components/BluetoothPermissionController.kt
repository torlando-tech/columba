package com.lxmf.messenger.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager

/**
 * Small helper to centralize Bluetooth permission and onboarding flow for
 * actions like "Turn ON" and "Bluetooth Settings".
 */

private enum class BluetoothPendingAction {
    ENABLE,
    OPEN_SETTINGS,
}

class BluetoothPermissionController(
    val onEnableClick: () -> Unit,
    val onOpenSettingsClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBluetoothPermissionController(
    onEnableRequested: (Context) -> Unit,
    onOpenSettingsRequested: (Context) -> Unit,
): BluetoothPermissionController {
    val context = LocalContext.current
    val prefs =
        remember(context) {
            context.getSharedPreferences("bluetooth_permission_prefs", Context.MODE_PRIVATE)
        }

    var showPermissionBottomSheet by remember { mutableStateOf(false) }
    var permissionStatus by remember { mutableStateOf<BlePermissionManager.PermissionStatus?>(null) }
    var hasRequestedOnce by remember {
        mutableStateOf(
            prefs.getBoolean("hasRequestedBluetoothPermissions", false),
        )
    }
    var pendingAction by remember { mutableStateOf<BluetoothPendingAction?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            // Re-check permission status after result
            permissionStatus = BlePermissionManager.checkPermissionStatus(context)

            val status = permissionStatus
            if (status is BlePermissionManager.PermissionStatus.Granted) {
                when (pendingAction) {
                    BluetoothPendingAction.ENABLE -> onEnableRequested(context)
                    BluetoothPendingAction.OPEN_SETTINGS -> onOpenSettingsRequested(context)
                    null -> Unit
                }
                pendingAction = null
            } else {
                // User denied again (or dialog not shown) → from now on we route to app settings.
                hasRequestedOnce = true
                prefs.edit().putBoolean("hasRequestedBluetoothPermissions", true).apply()
            }
        }

    // Lazily initialize permission status on first use via controller lambdas.

    val controller =
        remember {
            BluetoothPermissionController(
                onEnableClick = {
                    // Always re-check permissions, in case the user changed them in system settings.
                    val status =
                        BlePermissionManager.checkPermissionStatus(context).also {
                            permissionStatus = it
                        }
                    if (status is BlePermissionManager.PermissionStatus.Granted) {
                        onEnableRequested(context)
                    } else {
                        pendingAction = BluetoothPendingAction.ENABLE
                        showPermissionBottomSheet = true
                    }
                },
                onOpenSettingsClick = {
                    // Always re-check permissions, in case the user changed them in system settings.
                    val status =
                        BlePermissionManager.checkPermissionStatus(context).also {
                            permissionStatus = it
                        }
                    if (status is BlePermissionManager.PermissionStatus.Granted) {
                        onOpenSettingsRequested(context)
                    } else {
                        pendingAction = BluetoothPendingAction.OPEN_SETTINGS
                        showPermissionBottomSheet = true
                    }
                },
            )
        }

    // Render bottom sheet UI when needed
    if (showPermissionBottomSheet) {
        // Refresh permission status when showing the sheet (handles app restarts
        // and cases where the user changed permissions in system settings).
        val currentStatus =
            BlePermissionManager.checkPermissionStatus(context).also {
                permissionStatus = it
            }
        val useAppSettings =
            hasRequestedOnce &&
                currentStatus is BlePermissionManager.PermissionStatus.Denied

        BlePermissionBottomSheet(
            onDismiss = { showPermissionBottomSheet = false },
            onRequestPermissions = {
                showPermissionBottomSheet = false
                if (useAppSettings) {
                    // Open app settings so the user can manually enable permissions.
                    val intent =
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                } else {
                    val permissions = BlePermissionManager.getRequiredPermissions()
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            sheetState = sheetState,
            primaryActionLabel = if (useAppSettings) "Open Settings" else "Grant Permissions",
        )
    }

    return controller
}
