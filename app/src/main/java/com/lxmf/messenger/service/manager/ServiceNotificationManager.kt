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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R
import com.lxmf.messenger.service.state.ServiceState

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
                    NotificationManager.IMPORTANCE_DEFAULT,
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
        notificationManager.notify(NOTIFICATION_ID, createNotification(networkStatus))
    }

    /**
     * Safely start the service in foreground mode with proper exception handling.
     * Uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE for Android 10+ to indicate
     * active BLE mesh connections.
     *
     * @param service The service to start in foreground mode
     */
    fun startForeground(service: Service) {
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
        } catch (e: Exception) {
            // Handle ForegroundServiceStartNotAllowedException and other exceptions
            // This can happen when Android's time limits are exhausted
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)

            // Log specific handling for the time limit exception
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "Foreground service time limit exhausted. Service will run in background with limited capabilities.")
            }

            // Service continues to run but not as foreground
            // Future enhancement: Could transition to WorkManager here
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
                networkStatus.startsWith("ERROR:") -> "Error - Tap to view"
                else -> "Disconnected"
            }

        val detailText =
            when {
                networkStatus == "READY" ->
                    "Background service running. Keep battery optimization disabled for reliable message delivery."
                networkStatus == "INITIALIZING" -> "Connecting to mesh network..."
                else -> statusText
            }

        return Pair(statusText, detailText)
    }
}
