package network.columba.app.rns.ipc.server

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelephony
import network.columba.app.rns.ipc.callback.IRnsResultCallback

/**
 * Host-side adapter that bridges `oneway` [IRnsTelephony] AIDL calls onto
 * the suspend [RnsTelephony] implementation. Each AIDL entry point launches
 * a coroutine on [scope] that runs the suspend call and forwards the result
 * (or [network.columba.app.rns.api.RnsError]) back via the supplied
 * [IRnsResultCallback].
 */
internal class ServerRnsTelephony(
    private val impl: RnsTelephony,
    private val scope: CoroutineScope,
) : IRnsTelephony.Stub() {
    override fun initiateCall(
        destinationHash: String,
        profileCode: Int,
        hasProfileCode: Boolean,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        impl.initiateCall(destinationHash, if (hasProfileCode) profileCode else null).bundleOrThrow()
    }

    override fun answerCall(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.answerCall().bundleOrThrow()
    }

    override fun hangupCall(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.hangupCall()
        Bundle.EMPTY
    }

    override fun setCallMuted(muted: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setCallMuted(muted)
        Bundle.EMPTY
    }

    override fun setCallSpeaker(speakerOn: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setCallSpeaker(speakerOn)
        Bundle.EMPTY
    }

    override fun getCallState(cb: IRnsResultCallback) = dispatch(cb, scope) {
        val state = impl.getCallState().getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.CALL_STATE, state) }
    }
}
