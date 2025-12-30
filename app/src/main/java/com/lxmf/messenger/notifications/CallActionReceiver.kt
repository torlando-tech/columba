package com.lxmf.messenger.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lxmf.messenger.reticulum.call.bridge.CallBridge

/**
 * BroadcastReceiver for handling call notification actions.
 *
 * Handles:
 * - Answer call
 * - Decline call
 * - End call
 */
class CallActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)

        Log.d(TAG, "Received action: $action for identity: ${identityHash?.take(16)}")

        val callBridge = CallBridge.getInstance()

        when (action) {
            CallNotificationHelper.ACTION_ANSWER_CALL -> {
                Log.i(TAG, "Answering call from notification")
                callBridge.answerCall()
            }
            CallNotificationHelper.ACTION_DECLINE_CALL -> {
                Log.i(TAG, "Declining call from notification")
                callBridge.declineCall()
            }
            CallNotificationHelper.ACTION_END_CALL -> {
                Log.i(TAG, "Ending call from notification")
                callBridge.endCall()
            }
        }
    }
}
