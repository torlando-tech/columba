package network.columba.app.rns.api

import network.columba.app.rns.api.model.VoiceCallState

/**
 * Voice call surface (LXST — Link eXtensible Stream Transport).
 *
 * Single state-machine driven contract — implementations own the
 * underlying `LXSTSession` / Opus codec / Android `AudioTrack` plumbing.
 * UI observes call state via `getCallState`; transitions (initiate /
 * answer / hangup) drive the state forward.
 *
 * Note: a future `callStateFlow: StateFlow<VoiceCallState>` is expected
 * to land here when CallCoordinator ownership moves into `:rns-host`
 * (Phase A.10) — for now callers poll `getCallState()`.
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
     * Set microphone mute state during a call.
     *
     * @param muted true to mute, false to unmute
     */
    suspend fun setCallMuted(muted: Boolean)

    /**
     * Set speaker/earpiece mode during a call.
     *
     * @param speakerOn true for speaker, false for earpiece
     */
    suspend fun setCallSpeaker(speakerOn: Boolean)

    /**
     * Get current call state.
     *
     * @return Result containing CallState
     */
    suspend fun getCallState(): Result<VoiceCallState>
}
