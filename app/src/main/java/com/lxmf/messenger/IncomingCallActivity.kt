package com.lxmf.messenger

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lxmf.messenger.notifications.CallNotificationHelper
import com.lxmf.messenger.reticulum.call.bridge.CallBridge
import com.lxmf.messenger.reticulum.call.bridge.CallState
import com.lxmf.messenger.ui.screens.IncomingCallActivityScreen
import kotlinx.coroutines.launch

/**
 * Lightweight Activity that displays the incoming call screen over the lock screen.
 *
 * Unlike MainActivity (which is heavy with Hilt, navigation, etc.), this Activity
 * is designed to start instantly when a call arrives, even when the device is locked
 * or the app is closed. It mirrors the behavior of the native phone app.
 *
 * Key features:
 * - Shows over lock screen (showWhenLocked / FLAG_SHOW_WHEN_LOCKED)
 * - Turns screen on (turnScreenOn / FLAG_TURN_SCREEN_ON)
 * - Dismisses keyguard when answering
 * - Plays default ringtone and vibrates in a call pattern
 * - Directly uses CallBridge singleton (no Hilt dependency)
 * - Delegates to MainActivity for the active call screen after answering
 */
class IncomingCallActivity : ComponentActivity() {
    companion object {
        private const val TAG = "IncomingCallActivity"
    }

    private val callBridge = CallBridge.getInstance()
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val identityHash = intent?.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
        val callerName = intent?.getStringExtra(CallNotificationHelper.EXTRA_CALLER_NAME)

        Log.i(TAG, "onCreate - identityHash=${identityHash?.take(16)}, callerName=$callerName")

        if (identityHash == null) {
            Log.e(TAG, "No identity hash provided, finishing")
            finish()
            return
        }

        // Show over lock screen and turn screen on
        configureWindowForIncomingCall()

        // Start ringtone and vibration
        startRingtoneAndVibration()

        enableEdgeToEdge()

        // Monitor call state to auto-finish when call ends or is answered
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callBridge.callState.collect { state ->
                    Log.d(TAG, "Call state changed: $state")
                    when (state) {
                        is CallState.Active -> {
                            // Call was answered - open MainActivity with the active call
                            navigateToActiveCall(identityHash)
                        }
                        is CallState.Ended,
                        is CallState.Rejected,
                        is CallState.Idle,
                        -> {
                            // Call ended or declined - close this activity
                            Log.i(TAG, "Call ended/rejected/idle, finishing activity")
                            finish()
                        }
                        else -> {}
                    }
                }
            }
        }

        setContent {
            // Use a simple Material 3 theme (no Hilt-based theme needed)
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                IncomingCallActivityScreen(
                    identityHash = identityHash,
                    callerName = callerName,
                    onAnswer = { answerCall() },
                    onDecline = { declineCall() },
                )
            }
        }
    }

    override fun onDestroy() {
        stopRingtoneAndVibration()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent.action}")
        // If we get a new incoming call intent while already showing, just update
        setIntent(intent)
    }

    /**
     * Configure the window to show over the lock screen and turn the screen on,
     * similar to the native phone app's incoming call behavior.
     */
    private fun configureWindowForIncomingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // NOTE: Do NOT call requestDismissKeyguard() here.
            // It captures `this` Activity in a native IKeyguardDismissCallback that
            // persists as a GC root, causing a memory leak after onDestroy().
            // The keyguard is dismissed only when the user answers (see dismissKeyguardIfNeeded).
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        // Keep screen on while incoming call is displayed
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Dismiss the keyguard when the user answers the call.
     * Uses a weak reference to avoid the native IKeyguardDismissCallback
     * leaking this Activity after onDestroy().
     */
    private fun dismissKeyguardIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                val activityRef = java.lang.ref.WeakReference(this)
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            Log.d(TAG, "Keyguard dismissed")
                            activityRef.clear()
                        }
                        override fun onDismissCancelled() {
                            Log.d(TAG, "Keyguard dismiss cancelled")
                            activityRef.clear()
                        }
                        override fun onDismissError() {
                            Log.w(TAG, "Keyguard dismiss error")
                            activityRef.clear()
                        }
                    },
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    /**
     * Start playing the default ringtone and vibrating in a phone-call pattern.
     * Respects the device's ringer mode (silent/vibrate/normal).
     */
    private fun startRingtoneAndVibration() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = audioManager.ringerMode

        // Play ringtone (only if not in silent/vibrate mode)
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
                Log.d(TAG, "Ringtone started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ringtone", e)
            }
        }

        // Vibrate (in normal or vibrate mode, not silent)
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            try {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                // Phone-call vibration pattern: wait 0ms, vibrate 1s, pause 1s, repeat
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, 0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
                Log.d(TAG, "Vibration started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting vibration", e)
            }
        }
    }

    /**
     * Stop ringtone and vibration.
     */
    private fun stopRingtoneAndVibration() {
        try {
            ringtone?.stop()
            ringtone = null
            Log.d(TAG, "Ringtone stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone", e)
        }
        try {
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    /**
     * Answer the incoming call via CallBridge and navigate to MainActivity.
     */
    private fun answerCall() {
        Log.i(TAG, "Answering call")
        stopRingtoneAndVibration()
        dismissKeyguardIfNeeded()
        // The CallBridge will handle the actual answer via Python IPC
        // For the service-based architecture, we need to go through the protocol
        val app = applicationContext as? ColumbaApplication
        if (app != null) {
            lifecycleScope.launch {
                try {
                    app.reticulumProtocol.answerCall()
                } catch (e: Exception) {
                    Log.e(TAG, "Error answering call via protocol", e)
                    // Fallback: try CallBridge directly (works if in same process)
                    callBridge.answerCall()
                }
            }
        } else {
            // Fallback
            callBridge.answerCall()
        }
    }

    /**
     * Decline the incoming call.
     */
    private fun declineCall() {
        Log.i(TAG, "Declining call")
        stopRingtoneAndVibration()
        val app = applicationContext as? ColumbaApplication
        if (app != null) {
            lifecycleScope.launch {
                try {
                    app.reticulumProtocol.hangupCall()
                } catch (e: Exception) {
                    Log.e(TAG, "Error declining call via protocol", e)
                    callBridge.declineCall()
                }
                finish()
            }
        } else {
            callBridge.declineCall()
            finish()
        }
    }

    /**
     * Navigate to MainActivity to show the active call screen.
     */
    private fun navigateToActiveCall(identityHash: String) {
        Log.i(TAG, "Navigating to active call in MainActivity")
        // Cancel the incoming call notification
        CallNotificationHelper(this).cancelIncomingCallNotification()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = CallNotificationHelper.ACTION_ANSWER_CALL
            putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, identityHash)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}
