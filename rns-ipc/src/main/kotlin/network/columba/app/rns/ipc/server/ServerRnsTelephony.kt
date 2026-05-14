package network.columba.app.rns.ipc.server

import android.os.Bundle
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelephony
import network.columba.app.rns.ipc.callback.IRnsBoolEventCallback
import network.columba.app.rns.ipc.callback.IRnsCallStateCallback
import network.columba.app.rns.ipc.callback.IRnsNullableStringEventCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback

/**
 * Host-side adapter that bridges `oneway` [IRnsTelephony] AIDL calls onto
 * the suspend [RnsTelephony] implementation. Each AIDL entry point launches
 * a coroutine on [scope] that runs the suspend call and forwards the result
 * (or [network.columba.app.rns.api.RnsError]) back via the supplied
 * [IRnsResultCallback].
 *
 * Observable StateFlow surfaces (callState/remoteIdentity/isMuted/isSpeakerOn/
 * isPttMode/isPttActive) are fanned out via [ObserverHub] — lazy upstream
 * collection on first observer registration, `linkToDeath`-cleaned exits.
 */
internal class ServerRnsTelephony(
    private val impl: RnsTelephony,
    private val scope: CoroutineScope,
) : IRnsTelephony.Stub() {
    private val callStateHub = ObserverHub<CallState, IRnsCallStateCallback>(
        scope = scope,
        upstream = { impl.callState },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onState(value) },
    )
    private val remoteIdentityHub = ObserverHub<String?, IRnsNullableStringEventCallback>(
        scope = scope,
        upstream = { impl.remoteIdentity },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onString(value) },
    )
    private val isMutedHub = ObserverHub<Boolean, IRnsBoolEventCallback>(
        scope = scope,
        upstream = { impl.isMuted },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onBool(value) },
    )
    private val isSpeakerOnHub = ObserverHub<Boolean, IRnsBoolEventCallback>(
        scope = scope,
        upstream = { impl.isSpeakerOn },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onBool(value) },
    )
    private val isPttModeHub = ObserverHub<Boolean, IRnsBoolEventCallback>(
        scope = scope,
        upstream = { impl.isPttMode },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onBool(value) },
    )
    private val isPttActiveHub = ObserverHub<Boolean, IRnsBoolEventCallback>(
        scope = scope,
        upstream = { impl.isPttActive },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onBool(value) },
    )

    // ==================== Call control (IPC actions) ====================

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

    override fun declineCall(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.declineCall()
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

    // ==================== Observable surface ====================

    override fun getCurrentCallState(cb: IRnsCallStateCallback) {
        try { cb.onState(impl.callState.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerCallStateObserver(cb: IRnsCallStateCallback) =
        callStateHub.registerObserver(cb)
    override fun unregisterCallStateObserver(cb: IRnsCallStateCallback) =
        callStateHub.unregisterObserver(cb)

    override fun getCurrentRemoteIdentity(cb: IRnsNullableStringEventCallback) {
        try { cb.onString(impl.remoteIdentity.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerRemoteIdentityObserver(cb: IRnsNullableStringEventCallback) =
        remoteIdentityHub.registerObserver(cb)
    override fun unregisterRemoteIdentityObserver(cb: IRnsNullableStringEventCallback) =
        remoteIdentityHub.unregisterObserver(cb)

    override fun getCurrentIsMuted(cb: IRnsBoolEventCallback) {
        try { cb.onBool(impl.isMuted.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerIsMutedObserver(cb: IRnsBoolEventCallback) =
        isMutedHub.registerObserver(cb)
    override fun unregisterIsMutedObserver(cb: IRnsBoolEventCallback) =
        isMutedHub.unregisterObserver(cb)

    override fun getCurrentIsSpeakerOn(cb: IRnsBoolEventCallback) {
        try { cb.onBool(impl.isSpeakerOn.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerIsSpeakerOnObserver(cb: IRnsBoolEventCallback) =
        isSpeakerOnHub.registerObserver(cb)
    override fun unregisterIsSpeakerOnObserver(cb: IRnsBoolEventCallback) =
        isSpeakerOnHub.unregisterObserver(cb)

    override fun getCurrentIsPttMode(cb: IRnsBoolEventCallback) {
        try { cb.onBool(impl.isPttMode.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerIsPttModeObserver(cb: IRnsBoolEventCallback) =
        isPttModeHub.registerObserver(cb)
    override fun unregisterIsPttModeObserver(cb: IRnsBoolEventCallback) =
        isPttModeHub.unregisterObserver(cb)

    override fun getCurrentIsPttActive(cb: IRnsBoolEventCallback) {
        try { cb.onBool(impl.isPttActive.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerIsPttActiveObserver(cb: IRnsBoolEventCallback) =
        isPttActiveHub.registerObserver(cb)
    override fun unregisterIsPttActiveObserver(cb: IRnsBoolEventCallback) =
        isPttActiveHub.unregisterObserver(cb)

    // ==================== Local-state mutators ====================

    override fun setConnecting(destinationHash: String, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setConnecting(destinationHash)
        Bundle.EMPTY
    }

    override fun setEnded(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setEnded()
        Bundle.EMPTY
    }

    override fun setMutedLocally(muted: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setMutedLocally(muted)
        Bundle.EMPTY
    }

    override fun setSpeakerLocally(enabled: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setSpeakerLocally(enabled)
        Bundle.EMPTY
    }

    override fun setPttModeLocally(enabled: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setPttModeLocally(enabled)
        Bundle.EMPTY
    }

    override fun setPttActiveLocally(active: Boolean, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.setPttActiveLocally(active)
        Bundle.EMPTY
    }
}
