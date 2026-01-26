package com.lxmf.messenger.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent

/**
 * Manages a [MediaSessionCompat] to capture Bluetooth headset button events
 * for Push-to-Talk (PTT) mode during voice calls.
 *
 * When active, the standard headset button (KEYCODE_HEADSETHOOK) is mapped to:
 * - ACTION_DOWN → PTT active (transmitting)
 * - ACTION_UP   → PTT inactive (listening)
 *
 * This works with:
 * - Standard Bluetooth headsets (single multifunction button)
 * - BLE PTT accessories that present as HID devices
 * - Wired headsets with inline buttons
 *
 * Lifecycle: call [activate] when PTT mode is enabled during an active call,
 * and [deactivate] when PTT mode is disabled or the call ends.
 */
class PttMediaSessionManager(
    private val context: Context,
    private val onPttStateChanged: (active: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "Columba:PTT"
        private const val SESSION_TAG = "ColumbaVoicePTT"
    }

    private var mediaSession: MediaSessionCompat? = null
    private var isActive = false

    /**
     * Activate the MediaSession to capture headset button events.
     * Call when PTT mode is enabled during an active call.
     */
    fun activate() {
        if (isActive) return
        Log.d(TAG, "Activating PTT MediaSession")

        val session = MediaSessionCompat(context, SESSION_TAG).apply {
            setCallback(mediaSessionCallback)

            // Set a "playing" playback state so the session receives media button events.
            // Without an active state, Android routes buttons to the last active media app.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                    .build(),
            )

            isActive = true
        }

        session.isActive = true
        mediaSession = session

        // Request audio focus so our session is the active one for media buttons
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)

        Log.i(TAG, "PTT MediaSession activated")
    }

    /**
     * Deactivate the MediaSession and release resources.
     * Call when PTT mode is disabled or the call ends.
     */
    fun deactivate() {
        if (!isActive) return
        Log.d(TAG, "Deactivating PTT MediaSession")

        // Ensure PTT is released if deactivating mid-press
        onPttStateChanged(false)

        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        isActive = false

        Log.i(TAG, "PTT MediaSession deactivated")
    }

    /**
     * Check if the MediaSession is currently active.
     */
    fun isActive(): Boolean = isActive

    /**
     * Release all resources. Call when the call screen is destroyed.
     */
    fun release() {
        deactivate()
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return super.onMediaButtonEvent(mediaButtonEvent)

            if (keyEvent.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
                keyEvent.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            ) {
                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            return when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyEvent.repeatCount == 0) {
                        Log.d(TAG, "PTT button pressed")
                        onPttStateChanged(true)
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    Log.d(TAG, "PTT button released")
                    onPttStateChanged(false)
                    true
                }
                else -> super.onMediaButtonEvent(mediaButtonEvent)
            }
        }
    }
}
