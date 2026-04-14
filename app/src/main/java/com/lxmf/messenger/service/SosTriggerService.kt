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
import androidx.core.content.ContextCompat
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R

/**
 * Lightweight foreground service that keeps the main process alive while SOS
 * trigger detection (shake/tap via accelerometer) is active.
 *
 * The actual sensor logic lives in [SosTriggerDetector]; this service only
 * provides a persistent notification so Android won't kill the process when
 * the app is in the background.
 *
 * Started/stopped by [SosTriggerDetector.startObserving] when SOS settings change.
 */
class SosTriggerService : Service() {
    companion object {
        private const val TAG = "SosTriggerService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "sos_trigger_monitoring"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SosTriggerService::class.java),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SosTriggerService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SosTriggerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "SOS Trigger Monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SOS gesture detection active in background"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS Monitoring Active")
            .setContentText("Monitoring for emergency trigger gestures")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
