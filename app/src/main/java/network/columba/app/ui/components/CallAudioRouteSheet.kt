package network.columba.app.ui.components

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.api.model.CallAudioRoute

/**
 * Modal bottom sheet for choosing the call-audio output device.
 *
 * One route per row: builtin earpiece/speaker, Bluetooth SCO/LE, hearing aid,
 * wired or USB headset, as reported by the backend's platform communication
 * device list. The current route is preselected from [CallAudioRoute.isActive].
 * Selecting applies immediately and the caller dismisses the sheet; a separate
 * confirm step would leave the previous route live while the dialog covers the
 * call screen.
 *
 * Android's own calling apps put device selection in a sheet rather than
 * cycling a single button: on Android 14+ hearing aids and LE Audio headsets
 * are ordinary output choices, so a two-state speaker toggle cannot represent
 * the real routing state.
 *
 * @param routes Snapshot taken when the sheet opened.
 * @param onRouteSelected Invoked with the tapped route's device id.
 * @param onDismiss Invoked on scrim tap, swipe-down, or back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun CallAudioRouteSheet(
    routes: List<CallAudioRoute>,
    onRouteSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Match the app's other modal sheets: systemBarsPadding on the sheet
        // instead of M3's content window insets, so a tall navigation bar
        // cannot cover the last row.
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        CallAudioRouteSheetContent(routes = routes, onRouteSelected = onRouteSelected)
    }
}

/**
 * Sheet body: title, subtitle, and one single-select row per route.
 *
 * Split out of [CallAudioRouteSheet] so row labelling and selection can be
 * tested without the modal window.
 */
@Composable
internal fun CallAudioRouteSheetContent(
    routes: List<CallAudioRoute>,
    onRouteSelected: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.call_audio_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        Text(
            text = stringResource(R.string.call_audio_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )

        HorizontalDivider()

        routes.forEach { route ->
            CallAudioRouteRow(
                route = route,
                onClick = { onRouteSelected(route.deviceId) },
            )
        }
    }
}

@Composable
private fun CallAudioRouteRow(
    route: CallAudioRoute,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = route.isActive,
                    onClick = onClick,
                    role = Role.RadioButton,
                )
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("callAudioRoute-${route.deviceId}"),
    ) {
        Icon(
            imageVector = callAudioRouteIcon(route.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = callAudioRouteLabel(route),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
        )
        RadioButton(
            selected = route.isActive,
            onClick = null,
        )
    }
}

/** Localized label: builtin devices are named from resources, headsets by product name. */
@Composable
internal fun callAudioRouteLabel(route: CallAudioRoute): String =
    when (route.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> stringResource(R.string.call_earpiece)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.call_speaker)
        else ->
            route.name.ifBlank { stringResource(R.string.call_audio_unknown_device) }
    }

/**
 * Type-based label for the call-screen control, where a long product name
 * ("Galaxy Buds Pro") would crowd the neighbouring buttons. The sheet keeps
 * the full [callAudioRouteLabel]; Android's own dialers likewise show
 * "Bluetooth" on the call screen and device names in the picker.
 */
@Composable
internal fun callAudioRouteShortLabel(route: CallAudioRoute): String =
    when (route.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> stringResource(R.string.call_earpiece)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.call_speaker)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> stringResource(R.string.call_audio_bluetooth)
        AudioDeviceInfo.TYPE_HEARING_AID -> stringResource(R.string.call_audio_hearing_aid)
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> stringResource(R.string.call_audio_wired)
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> stringResource(R.string.call_audio_usb)
        else -> callAudioRouteLabel(route)
    }

internal fun callAudioRouteIcon(type: Int): ImageVector =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Icons.Default.PhoneInTalk
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Default.VolumeUp
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> Icons.Default.Bluetooth
        else -> Icons.Default.Headset
    }
