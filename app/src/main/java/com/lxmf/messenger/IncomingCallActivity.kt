package com.lxmf.messenger

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
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lxmf.messenger.notifications.CallNotificationHelper
import com.lxmf.messenger.ui.screens.IncomingCallActivityScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.CallState

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
 * - Directly uses CallCoordinator singleton (no Hilt dependency)
 * - Delegates to MainActivity for the active call screen after answering
 */
class IncomingCallActivity : ComponentActivity() {
    companion object {
        private const val TAG = "IncomingCallActivity"
    }

    private val callCoordinator = CallCoordinator.getInstance()
    private var ringtone: Ringtone? = null
    private var ringtoneLoopJob: Job? = null
    private var vibrator: Vibrator? = null

    // Compose-observable state so UI updates when onNewIntent delivers a new call
    private val currentIdentityHash = mutableStateOf<String?>(null)
    private val currentCallerName = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentIdentityHash.value = intent?.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
        currentCallerName.value = intent?.getStringExtra(CallNotificationHelper.EXTRA_CALLER_NAME)

        Log.i(TAG, "onCreate - identityHash=${currentIdentityHash.value?.take(16)}, callerName=${currentCallerName.value}")

        if (currentIdentityHash.value == null) {
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
                callCoordinator.callState.collect { state ->
                    Log.d(TAG, "Call state changed: $state")
                    when (state) {
                        is CallState.Active -> {
                            // Call was answered - open MainActivity with the active call
                            navigateToActiveCall(currentIdentityHash.value ?: return@collect)
                        }
                        is CallState.Ended,
                        is CallState.Rejected,
                        is CallState.Busy,
                        is CallState.Idle,
                        -> {
                            // Call ended or declined - close this activity
                            Log.i(TAG, "Call ended/rejected/busy/idle, finishing activity")
                            finish()
                        }
                        else -> {}
                    }
                }
            }
        }

        setContent {
            val hash = currentIdentityHash.value ?: return@setContent
            // Use a simple Material 3 theme (no Hilt-based theme needed)
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                IncomingCallActivityScreen(
                    identityHash = hash,
                    callerName = currentCallerName.value,
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
        setIntent(intent)
        val newHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
        val newName = intent.getStringExtra(CallNotificationHelper.EXTRA_CALLER_NAME)
        Log.d(TAG, "onNewIntent: hash=${newHash?.take(16)}, name=$newName")
        if (newHash != null) {
            currentIdentityHash.value = newHash
            currentCallerName.value = newName
            // Restart ringtone/vibration for the new call
            stopRingtoneAndVibration()
            startRingtoneAndVibration()
        }
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
            // The keyguard is dismissed in answerCall() -> dismissKeyguardAndAnswer().
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
                ringtone =
                    RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        audioAttributes =
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        play()
                    }
                // On pre-P devices, isLooping is not available; manually restart
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && ringtone != null) {
                    ringtoneLoopJob =
                        lifecycleScope.launch {
                            val rt = ringtone ?: return@launch
                            while (isActive) {
                                delay(1000)
                                if (!rt.isPlaying) rt.play()
                            }
                        }
                }
                Log.d(TAG, "Ringtone started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ringtone", e)
            }
        }

        // Vibrate (in normal or vibrate mode, not silent)
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            try {
                vibrator =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        ringtoneLoopJob?.cancel()
        ringtoneLoopJob = null
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
     * Answer the incoming call via CallCoordinator and navigate to MainActivity.
     *
     * Dismisses the keyguard first (if locked) so the mic is accessible.
     * requestDismissKeyguard() is called here — not in onCreate() — to avoid
     * the IKeyguardDismissCallback leak that occurs when the callback outlives
     * the Activity.
     */
    private fun answerCall() {
        Log.i(TAG, "Answering call")
        stopRingtoneAndVibration()
        dismissKeyguardAndAnswer()
    }

    /**
     * Dismiss the keyguard (if showing) then proceed to answer the call.
     * If the keyguard is not showing, answers immediately.
     */
    private fun dismissKeyguardAndAnswer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : android.app.KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            Log.i(TAG, "Keyguard dismissed, answering call")
                            performAnswer()
                        }

                        override fun onDismissCancelled() {
                            Log.w(TAG, "Keyguard dismiss cancelled, answering anyway")
                            performAnswer()
                        }

                        override fun onDismissError() {
                            Log.e(TAG, "Keyguard dismiss error, answering anyway")
                            performAnswer()
                        }
                    },
                )
                return
            }
        }
        performAnswer()
    }

    private fun performAnswer() {
        val app = applicationContext as? ColumbaApplication
        if (app != null) {
            lifecycleScope.launch {
                try {
                    app.reticulumProtocol.answerCall()
                } catch (e: Exception) {
                    Log.e(TAG, "Error answering call via protocol", e)
                    callCoordinator.answerCall()
                }
            }
        } else {
            callCoordinator.answerCall()
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
                    callCoordinator.declineCall()
                }
                finish()
            }
        } else {
            callCoordinator.declineCall()
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

        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = CallNotificationHelper.ACTION_ANSWER_CALL
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, identityHash)
                // NO_ANIMATION suppresses the slide transition — the user is already
                // on a call screen so a visual transition feels redundant and jarring.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
        startActivity(intent)
        finish()
        // Also suppress the exit animation on this activity
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
