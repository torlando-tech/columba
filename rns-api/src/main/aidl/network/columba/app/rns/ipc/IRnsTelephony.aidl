// AIDL surface mirroring the Kotlin RnsTelephony interface (LXST voice).
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - getCallState   → "state": VoiceCallState
//   - Result<Unit>   → Bundle.EMPTY
//
// profileCode is a nullable int — represented as (value, hasValue) for AIDL
// since boxed Int isn't supported. Pass hasValue=false to mean null.
package network.columba.app.rns.ipc;

import network.columba.app.rns.ipc.callback.IRnsResultCallback;

oneway interface IRnsTelephony {
    void initiateCall(String destinationHash, int profileCode, boolean hasProfileCode, in IRnsResultCallback cb);
    void answerCall(in IRnsResultCallback cb);

    // hangupCall, setCallMuted, setCallSpeaker are suspend-Unit on Kotlin.
    // Failures propagate via onError → RnsException on the UI side.
    void hangupCall(in IRnsResultCallback cb);
    void setCallMuted(boolean muted, in IRnsResultCallback cb);
    void setCallSpeaker(boolean speakerOn, in IRnsResultCallback cb);

    void getCallState(in IRnsResultCallback cb);

    // TODO(A.10): when CallCoordinator ownership moves into :rns-host, expose
    // its observable surface (callState StateFlow, isMuted, isSpeakerOn,
    // isPttMode, isPttActive, remoteIdentity) via observer callbacks here.
    // Kotlin RnsTelephony interface needs the matching property additions.
}
