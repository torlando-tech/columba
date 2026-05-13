package network.columba.app.rns.ipc.client

import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.VoiceCallState
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelephony

/**
 * UI-side adapter that translates the Kotlin [RnsTelephony] surface into
 * `oneway` [IRnsTelephony] AIDL calls. Each suspend method is wrapped in
 * [suspendCancellableCoroutine] (via [awaitResult]) so the call shape on the
 * UI matches what the legacy in-process implementation exposed.
 */
internal class ClientRnsTelephony(
    private val remote: IRnsTelephony,
) : RnsTelephony {
    override suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int?,
    ): Result<Unit> = runCatching {
        awaitResult { cb ->
            remote.initiateCall(
                destinationHash,
                profileCode ?: 0,
                profileCode != null,
                cb,
            )
        }
        Unit
    }

    override suspend fun answerCall(): Result<Unit> = runCatching {
        awaitResult { cb -> remote.answerCall(cb) }
        Unit
    }

    override suspend fun hangupCall() {
        awaitResult { cb -> remote.hangupCall(cb) }
    }

    override suspend fun setCallMuted(muted: Boolean) {
        awaitResult { cb -> remote.setCallMuted(muted, cb) }
    }

    override suspend fun setCallSpeaker(speakerOn: Boolean) {
        awaitResult { cb -> remote.setCallSpeaker(speakerOn, cb) }
    }

    override suspend fun getCallState(): Result<VoiceCallState> = runCatching {
        val bundle = awaitResult { cb -> remote.getCallState(cb) }
        bundle.classLoader = VoiceCallState::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<VoiceCallState>(BundleKeys.CALL_STATE)
            ?: throw RnsException(RnsError.Generic("getCallState payload missing 'state'", null))
    }
}
