package network.columba.app.rns.backend.py

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState
import tech.torlando.lxst.core.CallCoordinator

/**
 * `RnsTelephony` for the Python-Chaquopy backend.
 *
 * Unlike the other `PythonRns*` sub-impls this class touches **no** Python:
 * voice/telephony runs on LXST-kt's [CallCoordinator] on *both* backends (the
 * Python backend ships no Python LXST). So this is a near-exact mirror of the
 * telephony section of `:rns-backend-kt`'s `NativeRnsBackendImpl` — pure
 * LXST-kt `CallCoordinator` delegation, with no [PythonRnsRuntime] /
 * [PythonEventBridge] / `PyObject` involvement. There is therefore no
 * `pyResult` / `pyCall` routing here; the suspend signatures come from the
 * [RnsTelephony] contract, not from JNI-hop requirements.
 *
 * The [CallCoordinator] is **constructor-injected** — `:rns-backend-py` is
 * deliberately *not* on the `NoCallCoordinatorGetInstanceOutsideHost` Detekt
 * allowlist, so `CallCoordinator.getInstance()` must never be called here. The
 * `:rns-host` pythonBackend Hilt module supplies the singleton.
 *
 * Observable surface:
 *  - [callState] is a [MutableStateFlow] of `:rns-api`'s [CallState], fed by a
 *    forever-running relay coroutine that collects LXST-kt's own `CallState`
 *    flow and translates each value via [toApiCallState];
 *  - [remoteIdentity] / [isMuted] / [isSpeakerOn] / [isPttMode] / [isPttActive]
 *    are thin `get()` delegates straight onto the coordinator's flows;
 *  - every mutator forwards to the matching [CallCoordinator] method.
 */
class PythonRnsTelephony(
    private val callCoordinator: CallCoordinator,
) : RnsTelephony {
    private companion object {
        const val TAG = "PythonRnsTelephony"

        /**
         * Translate LXST-kt's `CallState` into the `:rns-api` [CallState]
         * envelope crossing the backend seam. Copied verbatim (in intent)
         * from `NativeRnsBackendImpl.toApiCallState()` so both backends map
         * the LXST state machine identically.
         */
        fun tech.torlando.lxst.core.CallState.toApiCallState(): CallState =
            when (this) {
                is tech.torlando.lxst.core.CallState.Idle -> CallState.Idle
                is tech.torlando.lxst.core.CallState.Connecting -> CallState.Connecting(this.identityHash)
                is tech.torlando.lxst.core.CallState.Ringing -> CallState.Ringing(this.identityHash)
                is tech.torlando.lxst.core.CallState.Incoming -> CallState.Incoming(this.identityHash)
                is tech.torlando.lxst.core.CallState.Active -> CallState.Active(this.identityHash)
                is tech.torlando.lxst.core.CallState.Busy -> CallState.Busy
                is tech.torlando.lxst.core.CallState.Rejected -> CallState.Rejected
                is tech.torlando.lxst.core.CallState.Ended -> CallState.Ended
            }
    }

    // ==================== RnsTelephony observable surface ====================
    //
    // `CallCoordinator` is a JVM singleton with stable StateFlow references
    // that outlive any backend init/shutdown cycle, so — mirroring
    // `NativeRnsBackendImpl` — the call observable surface gets its own
    // forever-running scope. Cancelling this scope would invalidate the
    // StateFlow seen by AIDL observers, which is undesirable.
    private val telephonyRelayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _callState.asStateFlow()

    override val remoteIdentity: StateFlow<String?>
        get() = callCoordinator.remoteIdentity
    override val isMuted: StateFlow<Boolean>
        get() = callCoordinator.isMuted
    override val isSpeakerOn: StateFlow<Boolean>
        get() = callCoordinator.isSpeakerOn
    override val isPttMode: StateFlow<Boolean>
        get() = callCoordinator.isPttMode
    override val isPttActive: StateFlow<Boolean>
        get() = callCoordinator.isPttActive

    init {
        // Translate LXST-kt's CallState → :rns-api CallState into the StateFlow
        // we expose. Lives on telephonyRelayScope so init/shutdown cycles don't
        // tear down the relay.
        telephonyRelayScope.launch {
            callCoordinator.callState.collect { lxstState ->
                _callState.value = lxstState.toApiCallState()
            }
        }
    }

    // ==================== Call lifecycle ====================
    //
    // `NativeRnsBackendImpl` routes initiate/answer/hangup/mute/speaker through
    // a native `NativeCallManager`. The Python backend has no such manager —
    // LXST-kt's `CallCoordinator` *is* the call manager on this backend — so
    // these forward to the coordinator's own UI-facing methods, which fan out
    // to whatever `CallController` the host wired in. The coordinator methods
    // are non-suspend; the suspend signatures are dictated by the contract.

    /** Set by PythonCallManager — bypasses CallCoordinator which drops profileCode. */
    @Volatile
    var profileAwareCallHook: ((String, Int?) -> Unit)? = null

    override suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int?,
    ): Result<Unit> =
        runCatching {
            profileAwareCallHook?.invoke(destinationHash, profileCode)
                ?: callCoordinator.initiateCall(destinationHash)
        }

    override suspend fun answerCall(): Result<Unit> =
        runCatching {
            callCoordinator.answerCall()
        }

    override suspend fun hangupCall() {
        try {
            callCoordinator.endCall()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored error hanging up call: $e")
        }
    }

    override suspend fun declineCall() {
        // CallCoordinator.declineCall is non-suspend and routes through the same
        // controller.hangup path as endCall — exposing it as a separate seam
        // method preserves the call-site intent (rejecting unanswered vs ending
        // active) without diverging behaviour.
        callCoordinator.declineCall()
    }

    override suspend fun setCallMuted(muted: Boolean) {
        try {
            callCoordinator.setMuted(muted)
        } catch (e: Exception) {
            Log.w(TAG, "Ignored error setting call muted=$muted: $e")
        }
    }

    override suspend fun setCallSpeaker(speakerOn: Boolean) {
        try {
            callCoordinator.setSpeaker(speakerOn)
        } catch (e: Exception) {
            Log.w(TAG, "Ignored error setting speaker=$speakerOn: $e")
        }
    }

    // ==================== Host-side local-state mutators ====================
    //
    // These update CallCoordinator's host-side state directly without invoking
    // the underlying audio controller — see the RnsTelephony KDoc for the
    // optimistic-UI rationale. Verbatim delegation, same as NativeRnsBackendImpl.

    override suspend fun setConnecting(destinationHash: String) {
        callCoordinator.setConnecting(destinationHash)
    }

    override suspend fun setEnded() {
        callCoordinator.setEnded()
    }

    override suspend fun setMutedLocally(muted: Boolean) {
        callCoordinator.setMutedLocally(muted)
    }

    override suspend fun setSpeakerLocally(enabled: Boolean) {
        callCoordinator.setSpeakerLocally(enabled)
    }

    override suspend fun setPttModeLocally(enabled: Boolean) {
        callCoordinator.setPttModeLocally(enabled)
    }

    override suspend fun setPttActiveLocally(active: Boolean) {
        callCoordinator.setPttActiveLocally(active)
    }

    // ==================== One-shot snapshot ====================

    override suspend fun getCallState(): Result<VoiceCallState> =
        runCatching {
            val callState = callCoordinator.callState.value
            val isMuted = callCoordinator.isMuted.value
            val remoteIdentity = callCoordinator.remoteIdentity.value

            when (callState) {
                is tech.torlando.lxst.core.CallState.Idle,
                is tech.torlando.lxst.core.CallState.Ended,
                is tech.torlando.lxst.core.CallState.Busy,
                is tech.torlando.lxst.core.CallState.Rejected,
                ->
                    VoiceCallState(
                        status = callState::class.simpleName?.lowercase() ?: "idle",
                        isActive = false,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        profile = null,
                    )
                is tech.torlando.lxst.core.CallState.Connecting,
                is tech.torlando.lxst.core.CallState.Ringing,
                is tech.torlando.lxst.core.CallState.Incoming,
                ->
                    VoiceCallState(
                        status = callState::class.simpleName?.lowercase() ?: "connecting",
                        isActive = false,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        profile = null,
                    )
                is tech.torlando.lxst.core.CallState.Active ->
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        // No NativeCallManager.telephone on this backend, so the
                        // active LXST codec profile abbreviation is unavailable
                        // from the seam — the profile lives transport-side.
                        profile = null,
                    )
            }
        }

    // hasActiveCall() intentionally not overridden — the RnsTelephony default
    // (derive from callState.value) is correct here, and NativeRnsBackendImpl
    // does not override it either.
}
