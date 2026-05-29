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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import network.columba.app.R
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
 * without a persistent map banner. The state is also re-evaluated on every
 * `ON_RESUME` so granting/revoking precise from system settings while the app
 * is backgrounded is reflected when the user returns.
 *
 * Dismissals persist: tapping "Not Now", swiping the sheet away, or finishing the
 * precise-permission request without granting `FINE` calls [onDismiss] to set
 * [dismissed], so the prompt does not reappear on every cold start. It re-arms when
 * the user next (re)selects Precise — `SettingsRepository.saveLocationPrecisionRadius`
 * clears the persisted flag on a transition into radius 0.
 *
 * Tapping "Enable Precise Location" re-requests `FINE`+`COARSE`. Any outcome that
 * doesn't grant `FINE` (plain decline, Approximate-only, or permanent denial)
 * settles the interaction by persisting a dismissal, so the sheet re-arms only when
 * the user next selects Precise. This also stops the `ON_RESUME` that the closing
 * system chooser delivers from immediately re-showing the sheet (issue #855). When
 * Android will additionally no longer show the in-app dialog
 * ([ActivityCompat.shouldShowRequestPermissionRationale] is false — settled on
 * Approximate, or permanently denied), it also routes to the app's settings page as
 * the only remaining way to enable precise access.
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Bump on every foreground transition so the sheet state is refreshed when
    // the user returns after granting/revoking fine location in system settings
    // (none of the other keys would change to reflect that) — issue #855.
    var resumeKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeKey++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Local guard bridging the window between the permission-launcher callback
    // deciding to dismiss and the persisted [dismissed] flag propagating back.
    // On Android 12+ the precise-location chooser closing delivers an ON_RESUME
    // (which bumps resumeKey) in the same frame the callback runs; without this
    // guard the resume-driven LaunchedEffect re-evaluates needsPreciseLocationUpgrade
    // (still true) and re-shows the sheet before onDismiss() takes effect, trapping
    // the user in a loop (issue #855).
    var pendingDismiss by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            showSheet = false
            val fineGranted =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    LocationPermissionManager.hasFineLocationPermission(context)
            if (!fineGranted) {
                // The Enable flow finished without precise access (plain decline,
                // Approximate-only, or permanent denial). Settle the interaction:
                // persist a dismissal so the prompt re-arms only when the user next
                // selects Precise, and set pendingDismiss so the ON_RESUME the closing
                // system chooser delivers can't re-show the sheet before that flag
                // propagates back (issue #855).
                pendingDismiss = true
                onDismiss()
                // Additionally route to app settings only when Android will NOT show
                // the in-app dialog again: shouldShowRequestPermissionRationale ==
                // false with FINE still missing means the dialog is suppressed
                // (settled on Approximate, or permanently denied), so settings is the
                // only remaining recourse. A plain decline (rationale true) can be
                // re-prompted next time the user re-selects Precise.
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        context.findActivity(),
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                ) {
                    context.openPreciseLocationSettings()
                }
            }
        }

    // Clear the local guard once the persisted dismissal state settles — true after
    // a non-grant (suppression handed back to [dismissed]), or false again when the
    // user re-selects Precise and re-arms the prompt (issue #855).
    LaunchedEffect(dismissed) { pendingDismiss = false }

    LaunchedEffect(locationPrecisionRadius, enabled, dismissed, pendingDismiss, resumeKey) {
        showSheet =
            enabled &&
            !dismissed &&
            !pendingDismiss &&
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
            primaryActionLabel = stringResource(R.string.enable_precise_location),
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
                getString(R.string.precise_location_settings_hint),
                Toast.LENGTH_LONG,
            ).show()
    }
}
