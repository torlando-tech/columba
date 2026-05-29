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
import network.columba.app.util.LocationPermissionManager

/**
 * Prompts the user to grant *precise* (fine) location when they've chosen
 * precise location sharing but the OS has only granted approximate access.
 *
 * The trigger is [locationPrecisionRadius] being "Precise"
 * ([LocationPermissionManager.PRECISE_PRECISION_RADIUS]) while fine location is
 * missing. Because the persisted precision setting is the single value that
 * changes on app start, on settings import, and when the user edits the
 * precision picker, observing it here covers all three cases (issue #855)
 * without a persistent map banner.
 *
 * Tapping "Enable Precise Location" re-requests `FINE`+`COARSE`. On a fresh
 * grant that surfaces the system dialog; but once the user has already settled
 * on **Approximate**, Android no longer shows the in-app precise-upgrade dialog
 * and the request returns denied with no UI. In that case we fall back to the
 * app's settings page, where "Use precise location" can be turned on manually —
 * otherwise the prompt would re-appear forever with no way to act on it.
 *
 * @param locationPrecisionRadius persisted precision radius (0 = precise)
 * @param enabled gate so the prompt only fires once the main UI is up
 *   (onboarding complete, settings loaded) — never during splash/onboarding
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreciseLocationPermissionPrompt(
    locationPrecisionRadius: Int,
    enabled: Boolean,
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
            if (!fineGranted) {
                // Android won't surface the in-app precise-upgrade dialog once
                // the user has settled on Approximate — the request returns
                // without UI. Guide them to the app's settings page to turn on
                // "Use precise location" manually (issue #855).
                context.openPreciseLocationSettings()
            }
        }

    LaunchedEffect(locationPrecisionRadius, enabled) {
        showSheet =
            enabled &&
            LocationPermissionManager.needsPreciseLocationUpgrade(
                precisionRadiusMeters = locationPrecisionRadius,
                hasFineLocation = LocationPermissionManager.hasFineLocationPermission(context),
            )
    }

    if (showSheet) {
        LocationPermissionBottomSheet(
            onDismiss = { showSheet = false },
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
