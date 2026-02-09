package com.lxmf.messenger.service.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R
import com.lxmf.messenger.reticulum.protocol.PropagationState
import com.lxmf.messenger.service.state.ServiceState
import org.json.JSONObject

/**
 * Manages foreground service notification for ReticulumService.
 *
 * Handles notification channel creation (Android O+) and dynamic notification
 * updates based on network status.
 */
class ServiceNotificationManager(
    private val context: Context,
    private val state: ServiceState,
) {
    companion object {
        private const val TAG = "ServiceNotificationMgr"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "reticulum_service"
        const val CHANNEL_NAME = "Reticulum Network Service"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Track current sync state for notification updates
    private var currentSyncState: Int = PropagationState.STATE_IDLE

    @Suppress("unused")
    private var currentSyncProgress: Float = 0f
    private var lastNetworkStatus: String = "READY"

    // Watchdog: auto-reset sync notification if Python never sends a terminal state.
    // Timeout is sync timeout (5 min) + 30s buffer to let normal completion arrive first.
    private val syncNotificationTimeoutMs = 5 * 60 * 1000L + 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resetSyncRunnable = Runnable {
        Log.w(TAG, "Sync notification watchdog fired - resetting to normal notification")
        currentSyncState = PropagationState.STATE_IDLE
        currentSyncProgress = 0f
        notificationManager.notify(NOTIFICATION_ID, createNotification(lastNetworkStatus))
    }

    /**
     * Create notification channel for Android O+.
     * Must be called before showing any notification.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps Reticulum network running in background"
                    setShowBadge(false)
                    // Disable sound and vibration for non-intrusive foreground service
                    setSound(null, null)
                    enableVibration(false)
                }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground notification with current network status.
     *
     * @param networkStatus Current status: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     * @return Notification to display
     */
    fun createNotification(networkStatus: String): Notification {
        val pendingIntent = createContentIntent()
        val (statusText, detailText) = getStatusTexts(networkStatus)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Columba Mesh Network")
            .setContentText(statusText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(detailText),
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Update the existing notification with new status.
     *
     * @param networkStatus Current status: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     */
    fun updateNotification(networkStatus: String) {
        lastNetworkStatus = networkStatus
        // If we're actively syncing, don't override with network status
        if (currentSyncState in PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, createNotification(networkStatus))
    }

    /**
     * Update notification with propagation sync progress.
     *
     * @param stateJson JSON from Python: {"state": int, "state_name": str, "progress": float, ...}
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun updateSyncProgress(stateJson: String) {
        try {
            val json = JSONObject(stateJson)
            val state = json.optInt("state", PropagationState.STATE_IDLE)
            val stateName = json.optString("state_name", "idle")
            val progress = json.optDouble("progress", 0.0).toFloat()

            currentSyncState = state
            currentSyncProgress = progress

            // Only show sync notification for active sync states
            if (state in PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED) {
                val notification = createSyncNotification(stateName, progress)
                notificationManager.notify(NOTIFICATION_ID, notification)
                // Reset watchdog on each active state update
                scheduleSyncTimeout()
            } else {
                // Any non-active state (complete, idle, or error) restores normal notification
                cancelSyncTimeout()
                currentSyncState = PropagationState.STATE_IDLE
                notificationManager.notify(NOTIFICATION_ID, createNotification(lastNetworkStatus))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sync progress: ${e.message}")
        }
    }

    /**
     * Force-reset the sync notification to normal state.
     * Called when the service is shutting down or when sync state is
     * known to be stale (e.g., after a timeout in PropagationNodeManager).
     */
    fun resetSyncNotification() {
        cancelSyncTimeout()
        currentSyncState = PropagationState.STATE_IDLE
        currentSyncProgress = 0f
        notificationManager.notify(NOTIFICATION_ID, createNotification(lastNetworkStatus))
    }

    private fun scheduleSyncTimeout() {
        mainHandler.removeCallbacks(resetSyncRunnable)
        mainHandler.postDelayed(resetSyncRunnable, syncNotificationTimeoutMs)
    }

    private fun cancelSyncTimeout() {
        mainHandler.removeCallbacks(resetSyncRunnable)
    }

    /**
     * Create notification showing sync progress.
     */
    private fun createSyncNotification(
        stateName: String,
        progress: Float,
    ): Notification {
        val pendingIntent = createContentIntent()
        val (title, subtitle) = getSyncStatusTexts(stateName, progress)

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Add progress bar for receiving state
        if (stateName.lowercase() == "receiving" && progress > 0f) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            // Indeterminate progress for other sync states
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * Get status texts for sync notification.
     */
    private fun getSyncStatusTexts(
        stateName: String,
        progress: Float,
    ): Pair<String, String> {
        val title = "Syncing with relay..."
        val subtitle =
            when (stateName.lowercase()) {
                "path_requested" -> "Discovering network path..."
                "link_establishing" -> "Establishing connection..."
                "link_established" -> "Connected, preparing request..."
                "request_sent" -> "Requesting messages..."
                "receiving" ->
                    if (progress > 0f) {
                        "Downloading: ${(progress * 100).toInt()}%"
                    } else {
                        "Downloading messages..."
                    }
                "response_received" -> "Processing response..."
                else -> "Processing..."
            }
        return Pair(title, subtitle)
    }

    /**
     * Safely start the service in foreground mode with proper exception handling.
     * Uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE for Android 10+ to indicate
     * active BLE mesh connections.
     *
     * @param service The service to start in foreground mode
     * @return true if foreground started successfully, false if it failed
     */
    fun startForeground(service: Service): Boolean {
        try {
            val notification = createNotification(state.networkStatus.get())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires explicit foreground service type
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                // Android 9 and below
                service.startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started successfully")
            return true
        } catch (e: Exception) {
            // Handle ForegroundServiceStartNotAllowedException and other exceptions
            // This can happen when Android's time limits are exhausted
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)

            // Log specific handling for the time limit exception
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "Foreground service time limit exhausted. Service will run in background with limited capabilities.")
                // Update state to reflect the issue - service is now vulnerable to being killed
                state.networkStatus.set("ERROR:Foreground service not allowed - battery optimization may kill the service")
            }

            // Service continues to run but not as foreground - it's now killable
            return false
        }
    }

    private fun createContentIntent(): PendingIntent {
        val notificationIntent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getStatusTexts(networkStatus: String): Pair<String, String> {
        val statusText =
            when {
                networkStatus == "READY" -> "Connected - Mesh network active"
                networkStatus == "INITIALIZING" -> "Starting mesh network..."
                networkStatus == "CONNECTING" -> "Reconnecting..."
                networkStatus.startsWith("ERROR:") -> "Error - Tap to view"
                else -> "Disconnected"
            }

        val detailText =
            when {
                networkStatus == "READY" ->
                    "Background service running. Keep battery optimization disabled for reliable message delivery."
                networkStatus == "INITIALIZING" -> "Connecting to mesh network..."
                networkStatus == "CONNECTING" -> "Service was interrupted. Attempting to reconnect..."
                networkStatus.startsWith("ERROR:") -> networkStatus.substringAfter("ERROR:")
                else -> statusText
            }

        return Pair(statusText, detailText)
    }
}
