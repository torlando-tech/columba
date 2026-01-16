package com.lxmf.messenger.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lxmf.messenger.ColumbaApplication
import com.lxmf.messenger.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling call notification actions.
 *
 * Handles:
 * - Answer call: Opens app and triggers auto-answer
 * - Decline call: Sends hangup to service via IPC
 * - End call: Sends hangup to service via IPC
 *
 * Note: Call actions require IPC to the service process where Python runs.
 * Answer opens the VoiceCallScreen which handles the IPC properly.
 * Decline/End use goAsync() to ensure the coroutine completes before Android
 * kills the BroadcastReceiver process.
 */
class CallActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallActionReceiver"
        const val EXTRA_AUTO_ANSWER = "auto_answer"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)

        Log.d(TAG, "Received action: $action for identity: ${identityHash?.take(16)}")

        // Cancel the notification
        CallNotificationHelper(context).cancelIncomingCallNotification()

        when (action) {
            CallNotificationHelper.ACTION_ANSWER_CALL -> {
                Log.i(TAG, "Answering call from notification - opening voice call screen")
                // Open voice call screen with auto-answer flag
                val answerIntent =
                    Intent(context, MainActivity::class.java).apply {
                        this.action = CallNotificationHelper.ACTION_ANSWER_CALL
                        putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, identityHash)
                        putExtra(EXTRA_AUTO_ANSWER, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                context.startActivity(answerIntent)
            }
            CallNotificationHelper.ACTION_DECLINE_CALL -> {
                Log.i(TAG, "Declining call from notification")
                performAsyncHangup(context, "decline")
            }
            CallNotificationHelper.ACTION_END_CALL -> {
                Log.i(TAG, "Ending call from notification")
                performAsyncHangup(context, "end")
            }
        }
    }

    /**
     * Perform hangup asynchronously using goAsync() to prevent process termination.
     *
     * BroadcastReceiver.onReceive() is synchronous - once it returns, Android may kill
     * the process immediately. goAsync() signals that we need more time to complete
     * background work, and finish() must be called when done.
     */
    private fun performAsyncHangup(
        context: Context,
        actionType: String,
    ) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? ColumbaApplication

        if (app == null) {
            Log.e(TAG, "Failed to $actionType call: Application not available")
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.reticulumProtocol.hangupCall()
                Log.d(TAG, "Successfully sent hangup for $actionType action")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to $actionType call", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
