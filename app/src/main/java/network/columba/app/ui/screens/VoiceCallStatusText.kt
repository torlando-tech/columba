package network.columba.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import network.columba.app.R
import network.columba.app.rns.api.model.CallState
import network.columba.app.viewmodel.CallInitiationFailure

@Composable
internal fun voiceCallStatusText(
    callState: CallState,
    initiationFailure: CallInitiationFailure?,
    isPttMode: Boolean,
    isPttActive: Boolean,
    formattedDuration: String,
): String =
    initiationFailure?.let { stringResource(it.messageResource()) } ?: when (callState) {
        is CallState.Connecting -> stringResource(R.string.call_status_connecting)
        is CallState.Ringing -> stringResource(R.string.call_status_ringing)
        is CallState.Active ->
            if (isPttMode) {
                stringResource(if (isPttActive) R.string.call_status_transmitting else R.string.call_status_listening)
            } else {
                formattedDuration
            }
        is CallState.Busy -> stringResource(R.string.call_status_busy)
        is CallState.Rejected -> stringResource(R.string.call_status_rejected)
        is CallState.Ended -> stringResource(R.string.call_status_ended)
        else -> stringResource(R.string.call_status_calling)
    }

@StringRes
internal fun CallInitiationFailure.messageResource(): Int =
    when (this) {
        CallInitiationFailure.INVALID_IDENTITY -> R.string.call_error_invalid_identity
        CallInitiationFailure.LOCAL_IDENTITY_CHANGED -> R.string.call_error_local_identity_changed
        CallInitiationFailure.REMOTE_IDENTITY_UNAVAILABLE -> R.string.call_error_remote_identity_unavailable
        CallInitiationFailure.BLOCKED_IDENTITY -> R.string.call_error_blocked_identity
        CallInitiationFailure.TELEPHONY_DESTINATION_UNAVAILABLE -> R.string.call_error_destination_unavailable
        CallInitiationFailure.MICROPHONE_PERMISSION_DENIED -> R.string.call_error_microphone_permission
        CallInitiationFailure.SERVICE_FAILURE -> R.string.call_error_service_failure
    }
