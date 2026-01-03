package com.lxmf.messenger.service.coordinator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates audio resources for voice calls.
 *
 * Manages:
 * - Audio focus acquisition and release
 * - Wake locks (CPU and proximity)
 * - Audio mode switching (normal vs voice call)
 * - Speaker/earpiece routing
 */
@Singleton
class AudioCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "AudioCoordinator"
            private const val WAKE_LOCK_TAG = "Columba:CallWakeLock"
            private const val PROXIMITY_WAKE_LOCK_TAG = "Columba:ProximityWakeLock"
        }

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        private var audioFocusRequest: AudioFocusRequest? = null
        private var cpuWakeLock: PowerManager.WakeLock? = null
        private var proximityWakeLock: PowerManager.WakeLock? = null

        private var hasAudioFocus = false
        private var originalAudioMode = AudioManager.MODE_NORMAL

        private val audioFocusListener =
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                        hasAudioFocus = true
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "Audio focus lost")
                        hasAudioFocus = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Audio focus lost transiently")
                        hasAudioFocus = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Audio focus lost, can duck")
                    }
                }
            }

        /**
         * Start audio coordination for a voice call.
         *
         * Acquires audio focus, sets audio mode, and acquires wake locks.
         */
        fun startCall() {
            Log.i(TAG, "Starting call audio coordination")

            // Request audio focus
            requestAudioFocus()

            // Switch to voice call mode
            originalAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Acquire CPU wake lock to prevent device from sleeping
            acquireCpuWakeLock()

            // Acquire proximity wake lock to turn off screen when phone is to ear
            acquireProximityWakeLock()

            Log.d(TAG, "Call audio coordination started")
        }

        /**
         * Stop audio coordination after call ends.
         *
         * Releases audio focus, restores audio mode, and releases wake locks.
         */
        fun stopCall() {
            Log.i(TAG, "Stopping call audio coordination")

            // Release audio focus
            abandonAudioFocus()

            // Restore original audio mode
            audioManager.mode = originalAudioMode

            // Release wake locks
            releaseCpuWakeLock()
            releaseProximityWakeLock()

            Log.d(TAG, "Call audio coordination stopped")
        }

        /**
         * Set speaker phone on or off.
         *
         * @param enabled True for speaker, false for earpiece
         */
        fun setSpeakerphoneOn(enabled: Boolean) {
            audioManager.isSpeakerphoneOn = enabled
            Log.d(TAG, "Speakerphone: $enabled")

            // When using speaker, release proximity wake lock
            // When using earpiece, acquire it
            if (enabled) {
                releaseProximityWakeLock()
            } else {
                acquireProximityWakeLock()
            }
        }

        /**
         * Set microphone mute state.
         *
         * @param muted True to mute, false to unmute
         */
        fun setMicrophoneMute(muted: Boolean) {
            audioManager.isMicrophoneMute = muted
            Log.d(TAG, "Microphone mute: $muted")
        }

        /**
         * Check if audio focus is currently held.
         */
        fun hasAudioFocus(): Boolean = hasAudioFocus

        // ===== Private Helper Methods =====

        private fun requestAudioFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                audioFocusRequest =
                    AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener(audioFocusListener)
                        .setAcceptsDelayedFocusGain(false)
                        .build()

                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d(TAG, "Audio focus request result: $result")
            } else {
                @Suppress("DEPRECATION")
                val result =
                    audioManager.requestAudioFocus(
                        audioFocusListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                    )
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d(TAG, "Audio focus request result (legacy): $result")
            }
        }

        private fun abandonAudioFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
            hasAudioFocus = false
            Log.d(TAG, "Audio focus abandoned")
        }

        private fun acquireCpuWakeLock() {
            if (cpuWakeLock == null) {
                cpuWakeLock =
                    powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG,
                    )
            }
            cpuWakeLock?.apply {
                if (!isHeld) {
                    // Max 60 minutes for a call
                    acquire(60 * 60 * 1000L)
                    Log.d(TAG, "CPU wake lock acquired")
                }
            }
        }

        private fun releaseCpuWakeLock() {
            cpuWakeLock?.apply {
                if (isHeld) {
                    release()
                    Log.d(TAG, "CPU wake lock released")
                }
            }
        }

        private fun acquireProximityWakeLock() {
            // Check if proximity wake lock is supported
            if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                Log.d(TAG, "Proximity wake lock not supported")
                return
            }

            if (proximityWakeLock == null) {
                proximityWakeLock =
                    powerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        PROXIMITY_WAKE_LOCK_TAG,
                    )
            }
            proximityWakeLock?.apply {
                if (!isHeld) {
                    acquire(60 * 60 * 1000L)
                    Log.d(TAG, "Proximity wake lock acquired")
                }
            }
        }

        private fun releaseProximityWakeLock() {
            proximityWakeLock?.apply {
                if (isHeld) {
                    release()
                    Log.d(TAG, "Proximity wake lock released")
                }
            }
        }
    }
