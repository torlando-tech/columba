package network.columba.app.navigation

import android.net.Uri
import androidx.navigation.NavHostController
import network.columba.app.viewmodel.CALL_AGAIN_FAILURE_RESULT
import network.columba.app.viewmodel.CallInitiationFailure

const val ACTIVE_ONLY_ARGUMENT = "activeOnly"
const val EXPECTED_CALL_ATTEMPT_ID_ARGUMENT = "expectedCallAttemptId"
const val EXPECTED_LOCAL_IDENTITY_ARGUMENT = "expectedLocalIdentity"

internal fun callDetailsRoute(callAttemptId: String): String =
    "call_details/${Uri.encode(callAttemptId)}"

internal fun callAgainRoute(
    remoteIdentityHash: String,
    profileCode: Int,
    localIdentityHash: String,
): String =
    "voice_call/${Uri.encode(remoteIdentityHash)}" +
        "?autoAnswer=false&profileCode=$profileCode&identityTarget=true" +
        "&$EXPECTED_LOCAL_IDENTITY_ARGUMENT=${Uri.encode(localIdentityHash)}"

internal fun activeCallRoute(
    callAttemptId: String,
    remoteIdentityHash: String,
    profileCode: Int,
    localIdentityHash: String,
): String =
    "voice_call/${Uri.encode(remoteIdentityHash)}" +
        "?autoAnswer=false&profileCode=$profileCode&identityTarget=true" +
        "&$EXPECTED_LOCAL_IDENTITY_ARGUMENT=${Uri.encode(localIdentityHash)}" +
        "&$ACTIVE_ONLY_ARGUMENT=true&$EXPECTED_CALL_ATTEMPT_ID_ARGUMENT=${Uri.encode(callAttemptId)}"

internal fun NavHostController.returnCallAgainFailure(failure: CallInitiationFailure): Boolean {
    val caller = previousBackStackEntry
    if (caller?.destination?.route != AppDestination.CALL_DETAILS.routePattern) return false
    caller.savedStateHandle[CALL_AGAIN_FAILURE_RESULT] = failure.name
    popBackStack()
    return true
}
