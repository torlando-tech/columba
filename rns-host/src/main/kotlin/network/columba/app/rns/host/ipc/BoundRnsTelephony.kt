package network.columba.app.rns.host.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState

/**
 * UI-side proxy that delegates every [RnsTelephony] member to the currently-
 * bound [RnsBackend]. The host-side state is authoritative; client-side
 * StateFlows mirror it via republishing through `flatMapLatest`.
 *
 * `hasActiveCall()` is intentionally left to the interface's default
 * implementation — it derives from [callState] which is already republished.
 */
internal class BoundRnsTelephony(
    private val backendFlow: StateFlow<RnsBackend?>,
    scope: CoroutineScope,
) : RnsTelephony {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    override suspend fun initiateCall(destinationHash: String, profileCode: Int?): Result<Unit> =
        awaitBound().telephony.initiateCall(destinationHash, profileCode)

    override suspend fun answerCall(): Result<Unit> = awaitBound().telephony.answerCall()

    override suspend fun hangupCall() {
        awaitBound().telephony.hangupCall()
    }

    override suspend fun declineCall() {
        awaitBound().telephony.declineCall()
    }

    override suspend fun setCallMuted(muted: Boolean) {
        awaitBound().telephony.setCallMuted(muted)
    }

    override suspend fun setCallSpeaker(speakerOn: Boolean) {
        awaitBound().telephony.setCallSpeaker(speakerOn)
    }

    override suspend fun getCallState(): Result<VoiceCallState> =
        awaitBound().telephony.getCallState()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val callState: StateFlow<CallState> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.callState }
            .stateIn(scope, SharingStarted.Eagerly, CallState.Idle)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val remoteIdentity: StateFlow<String?> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.remoteIdentity }
            .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isMuted: StateFlow<Boolean> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.isMuted }
            .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isSpeakerOn: StateFlow<Boolean> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.isSpeakerOn }
            .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isPttMode: StateFlow<Boolean> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.isPttMode }
            .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isPttActive: StateFlow<Boolean> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telephony.isPttActive }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun setConnecting(destinationHash: String) {
        awaitBound().telephony.setConnecting(destinationHash)
    }

    override suspend fun setEnded() {
        awaitBound().telephony.setEnded()
    }

    override suspend fun setMutedLocally(muted: Boolean) {
        awaitBound().telephony.setMutedLocally(muted)
    }

    override suspend fun setSpeakerLocally(enabled: Boolean) {
        awaitBound().telephony.setSpeakerLocally(enabled)
    }

    override suspend fun setPttModeLocally(enabled: Boolean) {
        awaitBound().telephony.setPttModeLocally(enabled)
    }

    override suspend fun setPttActiveLocally(active: Boolean) {
        awaitBound().telephony.setPttActiveLocally(active)
    }
}
