package network.columba.app.rns.api

import kotlinx.coroutines.flow.StateFlow
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState

/**
 * Voice call surface (LXST — Link eXtensible Stream Transport).
 *
 * Single state-machine driven contract — implementations own the
 * underlying `LXSTSession` / Opus codec / Android `AudioTrack` plumbing.
 * UI observes lifecycle via [callState] + the supporting StateFlows;
 * transitions (initiate / answer / hangup) drive the state forward.
 *
 * Observable surface (`callState`, [remoteIdentity], [isMuted],
 * [isSpeakerOn], [isPttMode], [isPttActive]) is bridged across the AIDL
 * seam via snapshot+observer pairs in `:rns-ipc`. The Kotlin
 * [StateFlow] view UI consumers see is a client-side mirror seeded
 * from the host snapshot and updated by AIDL observer callbacks; the
 * authoritative state lives in the backend process.
 *
 * Local-state mutators (`setConnecting`/`setEnded`/`setMutedLocally`/
 * `setSpeakerLocally`/`setPttModeLocally`/`setPttActiveLocally`) update
 * the host-side state directly — they do not invoke the underlying
 * audio controller. They exist for UI flows that want to optimistically
 * reflect a change before (or alongside) the corresponding network
 * action — e.g. `togglePttMode` flips local PTT state immediately,
 * then issues `setCallMuted` to enact the mute.
 */
interface RnsTelephony {
    /**
     * Initiate an outgoing voice call to a destination.
     *
     * @param destinationHash Hex string of destination identity hash (32 chars)
     * @param profileCode LXST codec profile code (0x10-0x80), or null to use default
     * @return Result with success/failure status
     */
    suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int? = null,
    ): Result<Unit>

    /**
     * Answer an incoming voice call.
     *
     * @return Result with success/failure status
     */
    suspend fun answerCall(): Result<Unit>

    /**
     * End the current voice call (hangup).
     */
    suspend fun hangupCall()

    /**
     * Reject the current incoming call. Functionally equivalent to
     * [hangupCall] but kept separate to preserve call-site intent
     * (rejecting an unanswered call vs ending an active one).
     */
    suspend fun declineCall()

    /**
     * Set microphone mute state during a call. This is the network /
     * audio-controller action; for the matching UI-local state update
     * see [setMutedLocally].
     *
     * @param muted true to mute, false to unmute
     */
    suspend fun setCallMuted(muted: Boolean)

    /**
     * Set speaker/earpiece mode during a call. This is the
     * audio-controller action; for the matching UI-local state update
     * see [setSpeakerLocally].
     *
     * @param speakerOn true for speaker, false for earpiece
     */
    suspend fun setCallSpeaker(speakerOn: Boolean)

    /**
     * One-shot snapshot of the current call state. UI consumers should
     * usually observe [callState] (and friends) instead — this exists
     * for the rare case where a single read is sufficient.
     *
     * @return Result containing [VoiceCallState]
     */
    suspend fun getCallState(): Result<VoiceCallState>

    /**
     * Current call lifecycle. Idle when no call is in progress;
     * progresses through Connecting/Ringing/Incoming/Active and ends in
     * Ended/Busy/Rejected before returning to Idle.
     */
    val callState: StateFlow<CallState>

    /**
     * Identity hash of the remote party for the current call, if any.
     * Null when [callState] is [CallState.Idle]. Identity hashes are
     * 32-char hex strings.
     */
    val remoteIdentity: StateFlow<String?>

    /** Microphone mute state during the current call. */
    val isMuted: StateFlow<Boolean>

    /** Speaker (true) vs earpiece (false) audio routing. */
    val isSpeakerOn: StateFlow<Boolean>

    /**
     * Push-to-talk mode toggle. When true the user must hold the PTT
     * button to transmit; when false the call is full duplex.
     */
    val isPttMode: StateFlow<Boolean>

    /**
     * PTT button press state. Meaningful only while [isPttMode] is true
     * and [callState] is [CallState.Active].
     */
    val isPttActive: StateFlow<Boolean>

    /**
     * Update host-side `callState` to [CallState.Connecting] for the
     * given destination. UI calls this before issuing [initiateCall] so
     * the connecting UI renders immediately rather than waiting for the
     * AIDL round-trip.
     */
    suspend fun setConnecting(destinationHash: String)

    /**
     * Update host-side `callState` to [CallState.Ended]. UI calls this
     * after [hangupCall] returns to drive the "Call Ended" transient
     * state before [callState] returns to [CallState.Idle].
     */
    suspend fun setEnded()

    /**
     * Update host-side `isMuted` without invoking the audio controller.
     * Used by UI flows where the mute IPC is issued separately on a
     * mutex; the local update keeps the toggle UI in sync immediately.
     */
    suspend fun setMutedLocally(muted: Boolean)

    /**
     * Update host-side `isSpeakerOn` without invoking the audio
     * controller. See [setMutedLocally] for the same rationale.
     */
    suspend fun setSpeakerLocally(enabled: Boolean)

    /** Update host-side `isPttMode`. */
    suspend fun setPttModeLocally(enabled: Boolean)

    /** Update host-side `isPttActive`. */
    suspend fun setPttActiveLocally(active: Boolean)

    /**
     * True when [callState] is currently in any non-terminal phase
     * (Connecting / Ringing / Incoming / Active). Derived from
     * [callState] — does not cross the AIDL boundary on every call.
     */
    fun hasActiveCall(): Boolean =
        when (callState.value) {
            is CallState.Connecting,
            is CallState.Ringing,
            is CallState.Incoming,
            is CallState.Active,
            -> true
            else -> false
        }
}
