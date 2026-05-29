package network.columba.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import network.columba.app.util.LocationPermissionManager

/**
 * Prompts the user to grant *precise* (fine) location when they've chosen
 * precise location sharing but the OS has only granted approximate access (or
 * none at all).
 *
 * The trigger is [locationPrecisionRadius] being "Precise"
 * ([LocationPermissionManager.PRECISE_PRECISION_RADIUS]) while fine location is
 * missing. Because the persisted precision setting is the single value that
 * changes on app start, on settings import, and when the user edits the
 * precision picker, observing it here covers all three cases (issue #855)
 * without a persistent map banner.
 *
 * Dismissals persist: tapping "Not Now" calls [onDismiss] to set [dismissed],
 * so the prompt does not reappear on every cold start. It re-arms when the user
 * next (re)selects Precise — `SettingsRepository.saveLocationPrecisionRadius`
 * clears the persisted flag for radius 0.
 *
 * Tapping "Enable Precise Location" re-requests `FINE`+`COARSE`. If the request
 * returns without `FINE` and Android will no longer show the in-app dialog
 * ([ActivityCompat.shouldShowRequestPermissionRationale] is false — the user
 * already settled on Approximate, or permanently denied), it falls back to the
 * app's settings page. If the rationale is still true (a plain decline that can
 * be re-prompted next time), it just closes.
 *
 * @param locationPrecisionRadius persisted precision radius (0 = precise)
 * @param enabled gate so the prompt only fires once the main UI is up
 *   (onboarding complete, settings loaded) — never during splash/onboarding
 * @param dismissed persisted "Not Now" state; suppresses the prompt until the
 *   user next selects Precise
 * @param onDismiss persist a dismissal (called when the user taps "Not Now" or
 *   swipes the sheet away)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreciseLocationPermissionPrompt(
    locationPrecisionRadius: Int,
    enabled: Boolean,
    dismissed: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            showSheet = false
            val fineGranted =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    LocationPermissionManager.hasFineLocationPermission(context)
            // Only route to app settings when Android will NOT show the in-app
            // dialog again: shouldShowRequestPermissionRationale == false with FINE
            // still missing means the dialog is suppressed (user settled on
            // Approximate, or permanently denied), so settings is the only
            // recourse. When it's true, this was a plain decline that can be
            // re-prompted next time — just close the sheet (issue #855).
            if (!fineGranted &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    context.findActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            ) {
                context.openPreciseLocationSettings()
            }
        }

    LaunchedEffect(locationPrecisionRadius, enabled, dismissed) {
        showSheet =
            enabled &&
            !dismissed &&
            LocationPermissionManager.needsPreciseLocationUpgrade(
                precisionRadiusMeters = locationPrecisionRadius,
                hasFineLocation = LocationPermissionManager.hasFineLocationPermission(context),
            )
    }

    if (showSheet) {
        LocationPermissionBottomSheet(
            onDismiss = {
                showSheet = false
                // Persist so the prompt doesn't reappear on every cold start;
                // it re-arms when the user next selects Precise (issue #855).
                onDismiss()
            },
            onRequestPermissions = {
                showSheet = false
                permissionLauncher.launch(
                    LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                )
            },
            sheetState = sheetState,
            rationale = LocationPermissionManager.getPreciseLocationRationale(),
            primaryActionLabel = "Enable Precise Location",
        )
    }
}

/**
 * Open this app's system settings so the user can enable "Use precise
 * location". There is no direct intent to the precise toggle, so we open the
 * app-details page and hint at the path with a toast.
 */
private fun Context.openPreciseLocationSettings() {
    val opened =
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    if (opened) {
        Toast
            .makeText(
                this,
                "Open Permissions → Location and turn on \"Use precise location\".",
                Toast.LENGTH_LONG,
            ).show()
    }
}
