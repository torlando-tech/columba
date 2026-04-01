package com.lxmf.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R

/**
 * Lightweight foreground service that keeps the main process alive during Android Doze
 * so that GPS callbacks from FusedLocationProviderClient continue to fire.
 *
 * Only runs when location sharing or telemetry collection is active.
 * Managed via [LocationServiceCoordinator].
 */
class LocationForegroundService : Service() {
    companion object {
        private const val TAG = "LocationFgService"
        private const val CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 1004

        private const val EXTRA_TEXT = "notification_text"

        fun start(context: Context, notificationText: String = "Location active") {
            val intent = Intent(context, LocationForegroundService::class.java)
                .putExtra(EXTRA_TEXT, notificationText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Location active"
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot start: location permission not granted", e)
            LocationServiceCoordinator.clearAll()
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "Location foreground service started")
        // NOT_STICKY: don't restart after process death — coordinator will re-acquire
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Location foreground service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Location Sharing",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while location sharing or telemetry collection is active"
                    setShowBadge(false)
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: use a white-on-transparent vector drawable (app-wide issue)
            .setContentTitle("Location Sharing")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }
}
