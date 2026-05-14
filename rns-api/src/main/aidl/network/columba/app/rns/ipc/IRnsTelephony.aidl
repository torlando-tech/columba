// AIDL surface mirroring the Kotlin RnsTelephony interface (LXST voice).
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - getCallState   → "state": VoiceCallState
//   - Result<Unit>   → Bundle.EMPTY
//
// profileCode is a nullable int — represented as (value, hasValue) for AIDL
// since boxed Int isn't supported. Pass hasValue=false to mean null.
//
// Observable StateFlow surfaces follow the snapshot+observer pattern from
// IRnsCore.networkStatus: a getCurrent*(cb) one-shot read + register/
// unregister*Observer(cb) pair. The client adapter seeds a local
// MutableStateFlow from the snapshot and updates it via observer callbacks.
package network.columba.app.rns.ipc;

import network.columba.app.rns.ipc.callback.IRnsBoolEventCallback;
import network.columba.app.rns.ipc.callback.IRnsCallStateCallback;
import network.columba.app.rns.ipc.callback.IRnsNullableStringEventCallback;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;

oneway interface IRnsTelephony {
    // ==================== Call control (IPC actions) ====================

    void initiateCall(String destinationHash, int profileCode, boolean hasProfileCode, in IRnsResultCallback cb);
    void answerCall(in IRnsResultCallback cb);

    // hangupCall, declineCall, setCallMuted, setCallSpeaker are suspend-Unit on Kotlin.
    // Failures propagate via onError → RnsException on the UI side.
    void hangupCall(in IRnsResultCallback cb);
    void declineCall(in IRnsResultCallback cb);
    void setCallMuted(boolean muted, in IRnsResultCallback cb);
    void setCallSpeaker(boolean speakerOn, in IRnsResultCallback cb);

    // One-shot snapshot of the legacy VoiceCallState shape (Result<VoiceCallState>).
    void getCallState(in IRnsResultCallback cb);

    // ==================== Observable surface (snapshot + observer) ====================

    // StateFlow<CallState>: typed snapshot + continuous observer.
    void getCurrentCallState(in IRnsCallStateCallback cb);
    void registerCallStateObserver(in IRnsCallStateCallback cb);
    void unregisterCallStateObserver(in IRnsCallStateCallback cb);

    // StateFlow<String?> remoteIdentity.
    void getCurrentRemoteIdentity(in IRnsNullableStringEventCallback cb);
    void registerRemoteIdentityObserver(in IRnsNullableStringEventCallback cb);
    void unregisterRemoteIdentityObserver(in IRnsNullableStringEventCallback cb);

    // StateFlow<Boolean> isMuted.
    void getCurrentIsMuted(in IRnsBoolEventCallback cb);
    void registerIsMutedObserver(in IRnsBoolEventCallback cb);
    void unregisterIsMutedObserver(in IRnsBoolEventCallback cb);

    // StateFlow<Boolean> isSpeakerOn.
    void getCurrentIsSpeakerOn(in IRnsBoolEventCallback cb);
    void registerIsSpeakerOnObserver(in IRnsBoolEventCallback cb);
    void unregisterIsSpeakerOnObserver(in IRnsBoolEventCallback cb);

    // StateFlow<Boolean> isPttMode.
    void getCurrentIsPttMode(in IRnsBoolEventCallback cb);
    void registerIsPttModeObserver(in IRnsBoolEventCallback cb);
    void unregisterIsPttModeObserver(in IRnsBoolEventCallback cb);

    // StateFlow<Boolean> isPttActive.
    void getCurrentIsPttActive(in IRnsBoolEventCallback cb);
    void registerIsPttActiveObserver(in IRnsBoolEventCallback cb);
    void unregisterIsPttActiveObserver(in IRnsBoolEventCallback cb);

    // ==================== Local-state mutators ====================
    //
    // These update host-side CallCoordinator state without invoking the
    // underlying audio controller. UI uses them to optimistically reflect
    // a change before (or alongside) the corresponding network action. See
    // RnsTelephony.kt for the rationale on each.

    void setConnecting(String destinationHash, in IRnsResultCallback cb);
    void setEnded(in IRnsResultCallback cb);
    void setMutedLocally(boolean muted, in IRnsResultCallback cb);
    void setSpeakerLocally(boolean enabled, in IRnsResultCallback cb);
    void setPttModeLocally(boolean enabled, in IRnsResultCallback cb);
    void setPttActiveLocally(boolean active, in IRnsResultCallback cb);
}
