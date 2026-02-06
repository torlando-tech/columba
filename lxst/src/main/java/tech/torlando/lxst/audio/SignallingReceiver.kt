package tech.torlando.lxst.audio

import tech.torlando.lxst.bridge.NetworkPacketBridge

/**
 * Signalling constants matching Python LXST Telephony.Signalling (lines 102-112).
 *
 * Used for call state transitions and profile changes during LXST calls.
 */
object Signalling {
    // Call status signals
    /** Remote line is busy (call cannot be established). */
    const val STATUS_BUSY = 0x00
    /** Call was rejected by remote party. */
    const val STATUS_REJECTED = 0x01
    /** Initiating outbound call. */
    const val STATUS_CALLING = 0x02
    /** Remote line is available. */
    const val STATUS_AVAILABLE = 0x03
    /** Remote is ringing (awaiting answer). */
    const val STATUS_RINGING = 0x04
    /** Remote answered, setting up audio pipelines. */
    const val STATUS_CONNECTING = 0x05
    /** Call fully established, audio active. */
    const val STATUS_ESTABLISHED = 0x06

    // Profile change prefix
    // Actual signal = PREFERRED_PROFILE + profile_byte
    // e.g., 0xFF + 0x40 = profile MQ (QUALITY_MEDIUM)
    /** Base for profile change signals. Signal = PREFERRED_PROFILE + profile_byte. */
    const val PREFERRED_PROFILE = 0xFF
}

/**
 * SignallingReceiver - Handles bidirectional call signalling.
 *
 * Matches Python LXST Network.py SignallingReceiver (lines 13-47).
 * Signals include call status changes (BUSY, RINGING, ESTABLISHED, etc.)
 * and profile switches (codec/quality changes during active calls).
 *
 * Threading:
 * - signal() is non-blocking (fire-and-forget via bridge coroutine)
 * - onSignalReceived callback is invoked from bridge thread (Python GIL held briefly)
 *   DO NOT perform heavy work in the callback
 *
 * @param bridge NetworkPacketBridge for sending signals to Python
 * @param onSignalReceived Callback when signal received from remote
 *        - signal: Raw signal value received
 *        - isProfileChange: True if signal >= PREFERRED_PROFILE
 *        - profile: Profile byte if isProfileChange, null otherwise
 */
class SignallingReceiver(
    private val bridge: NetworkPacketBridge,
    private val onSignalReceived: (signal: Int, isProfileChange: Boolean, profile: Int?) -> Unit
) {
    init {
        bridge.setSignalCallback { signal ->
            handleSignalling(signal)
        }
    }

    /**
     * Send signal to remote peer.
     *
     * Non-blocking: delegates to NetworkPacketBridge which dispatches on IO thread.
     * Signal values are Signalling.STATUS_* constants or profile changes.
     *
     * @param signal Signal value (STATUS_* or PREFERRED_PROFILE + profile)
     * @param immediate If true, send immediately (default). If false, queue for inband (future).
     */
    fun signal(signal: Int, immediate: Boolean = true) {
        if (immediate) {
            bridge.sendSignal(signal)
        }
        // Note: Non-immediate (inband) signalling not implemented yet
        // Python LXST Network.py has TODO for this too (line 15)
    }

    /**
     * Send profile change signal.
     *
     * Convenience method that adds PREFERRED_PROFILE prefix.
     * Profile values from Python Telephony.py Profiles class:
     * - 0x10 BANDWIDTH_ULTRA_LOW
     * - 0x20 BANDWIDTH_VERY_LOW
     * - 0x30 BANDWIDTH_LOW
     * - 0x40 QUALITY_MEDIUM (default)
     * - 0x50 QUALITY_HIGH
     * - 0x60 QUALITY_MAX
     * - 0x70 LATENCY_ULTRA_LOW
     * - 0x80 LATENCY_LOW
     *
     * @param profile Profile byte (e.g., 0x40 for QUALITY_MEDIUM)
     */
    fun signalProfileChange(profile: Int) {
        signal(Signalling.PREFERRED_PROFILE + profile)
    }

    /**
     * Handle signal received from remote peer.
     *
     * Parses signal type (status vs profile change) and invokes callback.
     * Called from NetworkPacketBridge when Python delivers a signal.
     *
     * Profile change detection: signal >= PREFERRED_PROFILE (0xFF).
     * Matches Python Telephony.py line 726: `elif signal >= Signalling.PREFERRED_PROFILE`
     */
    private fun handleSignalling(signal: Int) {
        when {
            signal >= Signalling.PREFERRED_PROFILE -> {
                // Profile change: signal = 0xFF + profile_byte
                val profile = signal - Signalling.PREFERRED_PROFILE
                onSignalReceived(signal, true, profile)
            }
            else -> {
                // Status signal (BUSY, REJECTED, RINGING, etc.)
                onSignalReceived(signal, false, null)
            }
        }
    }

    /**
     * Handle multiple signals (batch processing).
     *
     * For future use when inband signalling scheduler is implemented.
     * Python Network.py receives signals as a list (line 42-43).
     */
    fun handleSignalling(signals: List<Int>) {
        signals.forEach { handleSignalling(it) }
    }

    companion object {
        /**
         * Convert status signal to human-readable string.
         *
         * For debug logging only - do not call in hot path.
         * Matches Python Signalling constant names.
         */
        fun statusToString(status: Int): String = when (status) {
            Signalling.STATUS_BUSY -> "BUSY"
            Signalling.STATUS_REJECTED -> "REJECTED"
            Signalling.STATUS_CALLING -> "CALLING"
            Signalling.STATUS_AVAILABLE -> "AVAILABLE"
            Signalling.STATUS_RINGING -> "RINGING"
            Signalling.STATUS_CONNECTING -> "CONNECTING"
            Signalling.STATUS_ESTABLISHED -> "ESTABLISHED"
            else -> if (status >= Signalling.PREFERRED_PROFILE) {
                "PROFILE_CHANGE(${status - Signalling.PREFERRED_PROFILE})"
            } else {
                "UNKNOWN($status)"
            }
        }
    }
}
