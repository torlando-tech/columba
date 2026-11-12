package network.columba.app.rns.host.manager

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import network.columba.app.rns.api.call.CallNotificationActions
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.host.R
import network.columba.app.rns.host.state.ServiceState
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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

        const val NOTIFICATION_ID_RNODE = 1002
        private const val CHANNEL_ID_RNODE = "rnode_alerts"

        // Ongoing-call notification (CallStyle) channel: silent, low importance.
        const val CHANNEL_ID_CALL = "call_service"

        // PendingIntent request codes for the call notification's hang-up action and
        // content intent. Process-wide constants so every repost updates the same
        // PendingIntents instead of creating a new one per call event.
        private const val CALL_HANG_UP_REQUEST_CODE = 6001
        private const val CALL_OPEN_REQUEST_CODE = 6002
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Service reference for reposting dismissed foreground notifications.
    // On Android 13+, users can swipe away foreground notifications; only
    // Service.startForeground() can restore them — NotificationManager.notify() cannot.
    private var service: Service? = null

    // The live voice call, as presented by the foreground notification and used
    // to claim the MICROPHONE foreground type. Null when no call is Active.
    @Volatile
    private var activeCall: ActiveCall? = null

    /**
     * What the foreground notification shows while a call is live.
     *
     * @param identityHash peer identity hash (32-char hex) carried by the hang-up action
     * @param peerName display name for the notification's person; never blank
     * @param startedAtWallClockMs call start (wall clock) — the chip's chronometer anchor
     */
    data class ActiveCall(
        val identityHash: String,
        val peerName: String,
        val startedAtWallClockMs: Long,
    )

    // Track current sync state for notification updates
    private var currentSyncState: Int = PropagationState.STATE_IDLE

    @Suppress("unused")
    private var currentSyncProgress: Float = 0f
    private var lastNetworkStatus: String = "READY"

    // Last RNode battery percent (0-100). null when absent / offline. Mutated
    // only on the main thread (via mainHandler) like the other notification state.
    private var rnodeBatteryPercent: Int? = null
    private var lastNotifiedBattery: Int? = null

    // Track per-interface RNode disconnect state. Thread-safe because startForeground()
    // (which reads this via getStatusTexts()) can be called from any thread, while mutations
    // are posted to mainHandler.
    private val disconnectedRNodeInterfaces: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    // Debounce heads-up disconnect alerts to avoid notification spam on flaky BLE connections.
    // Only gates fresh postings (notification not in the shade); content-only updates to a
    // visible notification are always allowed since they don't re-trigger the heads-up overlay.
    private var lastDisconnectNotifyMs: Long = 0L
    private val disconnectNotifyCooldownMs = 10_000L

    // Watchdog: auto-reset sync notification if Python never sends a terminal state.
    // Timeout is sync timeout (5 min) + 30s buffer to let normal completion arrive first.
    private val syncNotificationTimeoutMs = 5 * 60 * 1000L + 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resetSyncRunnable =
        Runnable {
            Log.w(TAG, "Sync notification watchdog fired - resetting to normal notification")
            currentSyncState = PropagationState.STATE_IDLE
            currentSyncProgress = 0f
            repostNotification(createNotification(lastNetworkStatus))
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
                    context.getString(R.string.notif_channel_reticulum_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_reticulum_description)
                    setShowBadge(false)
                    // Disable sound and vibration for non-intrusive foreground service
                    setSound(null, null)
                    enableVibration(false)
                }
            notificationManager.createNotificationChannel(channel)

            // RNode disconnect alerts — IMPORTANCE_HIGH for heads-up display
            val rnodeChannel =
                NotificationChannel(
                    CHANNEL_ID_RNODE,
                    context.getString(R.string.notif_channel_rnode_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_rnode_description)
                    setShowBadge(true)
                }
            notificationManager.createNotificationChannel(rnodeChannel)

            // Ongoing-call channel: silent; the notification shows call duration and
            // the hang-up action while a call is live.
            val callChannel =
                NotificationChannel(
                    CHANNEL_ID_CALL,
                    context.getString(R.string.notif_channel_calls_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_calls_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    /**
     * Create foreground notification with current network status.
     *
     * @param networkStatus Current status: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     * @return Notification to display
     */
    fun createNotification(networkStatus: String): Notification {
        val (statusText, detailText) = getStatusTexts(networkStatus)

        activeCall?.let { call ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return buildOngoingCallNotification(call)
            }
        }

        val pendingIntent = createContentIntent()

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_foreground_title))
            .setContentText(statusText)
            .setStyle(
                NotificationCompat
                    .BigTextStyle()
                    .bigText(detailText),
            ).setSmallIcon(R.drawable.ic_notification_stat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Ongoing-call notification for the foreground service, built as
     * [android.app.Notification.CallStyle] (API 31+).
     *
     * SystemUI's ongoing-call status-bar chip only reads notifications whose style
     * is CallStyle and whose `EXTRA_CALL_TYPE` is `CALL_TYPE_ONGOING`; the platform
     * additionally rejects CallStyle notifications that are not enqueued through
     * [Service.startForeground] — the reason this notification is the service's own
     * foreground notification rather than a separate `notify()` post. The chip's
     * chronometer counts from the notification's `when`, so it carries the call
     * start time, not the post time. CallStyle contributes the hang-up action.
     *
     * The hang-up action targets the app-process `CallActionReceiver` explicitly by
     * component name (the receiver is not exported; an explicit same-uid broadcast
     * from this process still reaches it).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildOngoingCallNotification(call: ActiveCall): Notification {
        val hangUpIntent =
            Intent(CallNotificationActions.ACTION_END_CALL)
                .setComponent(
                    ComponentName(context.packageName, CallNotificationActions.RECEIVER_CLASS),
                )
                .putExtra(CallNotificationActions.EXTRA_IDENTITY_HASH, call.identityHash)
        val hangUpPendingIntent =
            PendingIntent.getBroadcast(
                context,
                CALL_HANG_UP_REQUEST_CODE,
                hangUpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // SystemUI's chip fires this notification's content intent, so it must land on
        // the call screen: ACTION_OPEN_CALL on MainActivity navigates to
        // `incoming_call/{identityHash}`, which renders whatever state the call is in.
        val contentIntent =
            PendingIntent.getActivity(
                context,
                CALL_OPEN_REQUEST_CODE,
                Intent(CallNotificationActions.ACTION_OPEN_CALL)
                    .setClassName(context.packageName, CallNotificationActions.MAIN_ACTIVITY_CLASS)
                    .putExtra(CallNotificationActions.EXTRA_IDENTITY_HASH, call.identityHash)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val callerName = call.peerName
        val caller =
            Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

        // Platform builder, not NotificationCompat: CallStyle lives on the platform
        // Notification (API 31+) and this branch never runs below S.
        return Notification.Builder(context, CHANNEL_ID_CALL)
            .setSmallIcon(R.drawable.ic_notification_stat)
            .setContentTitle(callerName)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(call.startedAtWallClockMs)
            .setUsesChronometer(true)
            .setStyle(Notification.CallStyle.forOngoingCall(caller, hangUpPendingIntent))
            .build()
    }

    /**
     * Update the existing notification with new status.
     *
     * @param networkStatus Current status: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     */
    fun updateNotification(networkStatus: String) {
        mainHandler.post {
            lastNetworkStatus = networkStatus
            // If we're actively syncing, don't override with network status
            if (currentSyncState in PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED) {
                return@post
            }
            repostNotification(createNotification(networkStatus))
        }
    }

    /**
     * Update notification with propagation sync progress.
     *
     * @param stateJson JSON from Python: {"state": int, "state_name": str, "progress": float, ...}
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun updateSyncProgress(stateJson: String) {
        // Parse JSON on caller thread, then post state mutation + notify to main thread
        // to serialize with watchdog resets (which also run on main via mainHandler).
        try {
            val json = JSONObject(stateJson)
            val state = json.optInt("state", PropagationState.STATE_IDLE)
            val stateName = json.optString("state_name", "idle")
            val progress = json.optDouble("progress", 0.0).toFloat()

            mainHandler.post {
                currentSyncState = state
                currentSyncProgress = progress

                // Only show sync notification for active sync states
                if (state in PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED) {
                    val notification = createSyncNotification(stateName, progress)
                    repostNotification(notification)
                    // Reset watchdog on each active state update
                    scheduleSyncTimeout()
                } else {
                    // Any non-active state (complete, idle, or error) restores normal notification
                    cancelSyncTimeout()
                    currentSyncState = PropagationState.STATE_IDLE
                    repostNotification(createNotification(lastNetworkStatus))
                }
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
        mainHandler.post {
            cancelSyncTimeout()
            currentSyncState = PropagationState.STATE_IDLE
            currentSyncProgress = 0f
            repostNotification(createNotification(lastNetworkStatus))
        }
    }

    /**
     * Update RNode connection status and manage the disconnect alert notification.
     *
     * When [online] is false, posts a heads-up notification on the RNode alert channel.
     * When [online] is true, cancels any existing disconnect alert.
     * Also refreshes the foreground notification to reflect RNode status.
     *
     * @param online True if RNode is online, false if disconnected
     * @param interfaceName Name of the RNode interface for the notification body
     */
    fun updateRNodeStatus(
        online: Boolean,
        interfaceName: String,
    ) {
        mainHandler.post {
            if (!online) {
                disconnectedRNodeInterfaces.add(interfaceName)
            } else {
                disconnectedRNodeInterfaces.remove(interfaceName)
            }

            if (disconnectedRNodeInterfaces.isNotEmpty()) {
                // Battery reading is stale once the RNode is down; hide it.
                rnodeBatteryPercent = null
                lastNotifiedBattery = null
                val pendingIntent = createContentIntent()
                val names = disconnectedRNodeInterfaces.joinToString(", ")
                val alert =
                    NotificationCompat
                        .Builder(context, CHANNEL_ID_RNODE)
                        .setSmallIcon(R.drawable.ic_notification_stat)
                        .setContentTitle(context.getString(R.string.notif_rnode_alert_title))
                        .setContentText(context.getString(R.string.notif_rnode_alert_text, names))
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        // Explicit setGroup escapes Samsung OneUI's eager
                        // autogroup, which on Fold tonight was wrapping
                        // both the foreground service notification and
                        // this alert under `g:Aggregate_NormalNotificationSection`
                        // — the system demoted the autogroup's effective
                        // importance to LOW (=2), suppressing the heads-up
                        // peek AND visually folding the alert into the
                        // service notification in the shade. Distinct
                        // group key keeps the alert standalone so the
                        // IMPORTANCE_HIGH channel actually fires heads-up.
                        .setGroup("rnode_disconnect_alert")
                        .build()

                // Query the system for ground truth — covers autoCancel tap, user swipe,
                // and programmatic cancel without maintaining a stale flag.
                val isCurrentlyVisible =
                    notificationManager.activeNotifications
                        .any { it.id == NOTIFICATION_ID_RNODE }

                if (isCurrentlyVisible) {
                    // Notification already in the shade — silent content update (no heads-up)
                    notificationManager.notify(NOTIFICATION_ID_RNODE, alert)
                } else {
                    // Notification not visible — debounce fresh heads-up postings
                    val now = System.currentTimeMillis()
                    if (now - lastDisconnectNotifyMs >= disconnectNotifyCooldownMs) {
                        lastDisconnectNotifyMs = now
                        notificationManager.notify(NOTIFICATION_ID_RNODE, alert)
                    }
                }
            } else {
                // All RNode interfaces are online — dismiss the alert
                notificationManager.cancel(NOTIFICATION_ID_RNODE)
                lastDisconnectNotifyMs = 0L
            }

            // Refresh the foreground notification so status text reflects RNode state
            if (currentSyncState !in
                PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED
            ) {
                repostNotification(createNotification(lastNetworkStatus))
            }
        }
    }

    /**
     * Update the RNode battery shown in the persistent foreground notification.
     * [percent] is 0-100, or < 0 (absent) to hide it. Only reposts when the value
     * actually changed, to avoid needless startForeground churn.
     */
    fun updateRNodeBattery(percent: Int) {
        mainHandler.post {
            // Reject a stale positive value if any RNode interface is
            // disconnected: the poller may have captured the reading just
            // before a disconnect, and the disconnect event may reach the
            // main handler before this update. Without this guard the
            // notification would show "(RNode disconnected)" and
            // "(RNode battery N%)" at the same time.
            val newBattery =
                if (disconnectedRNodeInterfaces.isEmpty() && percent in 0..100) percent else null
            rnodeBatteryPercent = newBattery
            // Skip the repost during active sync (matches updateRNodeStatus) and
            // when nothing changed.
            if (newBattery == lastNotifiedBattery) return@post
            if (currentSyncState in
                PropagationState.STATE_PATH_REQUESTED..PropagationState.STATE_RESPONSE_RECEIVED
            ) {
                return@post
            }
            lastNotifiedBattery = newBattery
            repostNotification(createNotification(lastNetworkStatus))
        }
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
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_notification_stat)
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
        val title = context.getString(R.string.notif_sync_title)
        val subtitle =
            when (stateName.lowercase()) {
                "path_requested" -> context.getString(R.string.notif_sync_path_requested)
                "link_establishing" -> context.getString(R.string.notif_sync_link_establishing)
                "link_established" -> context.getString(R.string.notif_sync_link_established)
                "request_sent" -> context.getString(R.string.notif_sync_request_sent)
                "receiving" ->
                    if (progress > 0f) {
                        context.getString(R.string.notif_sync_downloading, (progress * 100).toInt())
                    } else {
                        context.getString(R.string.notif_sync_downloading_messages)
                    }
                "response_received" -> context.getString(R.string.notif_sync_response_received)
                else -> context.getString(R.string.notif_sync_processing)
            }
        return Pair(title, subtitle)
    }

    /**
     * Track the live voice call and re-assert the foreground state.
     *
     * Two things follow the call state here:
     * - **The notification becomes the ongoing-call notification** ([ActiveCall] != null):
     *   built as [android.app.Notification.CallStyle] so SystemUI draws the status-bar
     *   call chip with its duration, and posted through [startForeground] because the
     *   platform rejects CallStyle notifications from an app that is neither running a
     *   foreground service nor attaching a full-screen intent.
     * - **The MICROPHONE type is claimed**: Android only exempts a backgrounded app
     *   from the while-in-use microphone restriction while a foreground service with
     *   that type is running, and the call engine (LXST) captures inside this process.
     *
     * @param service the service to re-assert the foreground state on
     * @param call the live call, or null when no call is Active
     */
    fun setCallState(service: Service, call: ActiveCall?) {
        if (activeCall == call) return
        activeCall = call
        Log.d(TAG, "Call active=${call != null}; re-asserting foreground notification")
        startForeground(service)
    }

    private fun foregroundTypes(): Int =
        foregroundServiceTypes(
            callActive = activeCall != null,
            recordAudioGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            sdkInt = Build.VERSION.SDK_INT,
        )

    /**
     * Safely start the service in foreground mode with proper exception handling.
     * Uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE for Android 10+ to indicate
     * active BLE mesh connections.
     *
     * @param service The service to start in foreground mode
     * @return true if foreground started successfully, false if it failed
     */
    fun startForeground(service: Service): Boolean {
        this.service = service
        try {
            val notification = createNotification(state.networkStatus.get())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires explicit foreground service type
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundTypes(),
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
                state.networkStatus.set(
                    "ERROR:" + context.getString(R.string.notif_error_foreground_not_allowed),
                )
            }

            // Service continues to run but not as foreground - it's now killable
            return false
        }
    }

    /**
     * Repost a notification via [Service.startForeground] so it survives user dismissal
     * on Android 13+. Falls back to [NotificationManager.notify] if the service reference
     * is unavailable (should never happen in practice).
     */
    private fun repostNotification(notification: Notification) {
        val svc = service
        if (svc != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    svc.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        foregroundTypes(),
                    )
                } else {
                    svc.startForeground(NOTIFICATION_ID, notification)
                }
            } catch (
                // startForeground() makes a Binder IPC call; if the system server's ProcessRecord
                // is null (race during process teardown) it throws a RemoteException that Parcel
                // re-wraps as RuntimeException on the client side — no more specific type exists.
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
                Log.w(TAG, "startForeground failed during process teardown, falling back to notify", e)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createContentIntent(): PendingIntent {
        val notificationIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
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
                networkStatus == "READY" -> context.getString(R.string.notif_status_connected)
                networkStatus == "INITIALIZING" -> context.getString(R.string.notif_status_initializing)
                networkStatus == "CONNECTING" -> context.getString(R.string.notif_status_reconnecting)
                networkStatus.startsWith("ERROR:") -> context.getString(R.string.notif_status_error)
                else -> context.getString(R.string.notif_status_disconnected)
            }

        var detailText =
            when {
                networkStatus == "READY" ->
                    context.getString(R.string.notif_detail_running)
                networkStatus == "INITIALIZING" -> context.getString(R.string.notif_detail_initializing)
                networkStatus == "CONNECTING" -> context.getString(R.string.notif_detail_reconnecting)
                networkStatus.startsWith("ERROR:") -> networkStatus.substringAfter("ERROR:")
                else -> statusText
            }

        // Append RNode status when network is otherwise healthy
        if (networkStatus == "READY" && disconnectedRNodeInterfaces.isNotEmpty()) {
            detailText += context.getString(R.string.notif_detail_rnode_disconnected)
        }

        // Append RNode battery when we have a live reading and the network is healthy.
        val battery = rnodeBatteryPercent
        if (networkStatus == "READY" && battery != null) {
            detailText += context.getString(R.string.notif_detail_rnode_battery, battery)
        }

        return Pair(statusText, detailText)
    }
}

/**
 * Foreground-service types to pass to [Service.startForeground].
 *
 * CONNECTED_DEVICE is always claimed on API 29+ (BLE mesh links). MICROPHONE is
 * added only while a call is live and RECORD_AUDIO is granted: API 30+ FGS types
 * must be a subset of the manifest declarations (they are), and API 34+ throws
 * SecurityException when the service requests a type whose permission the app
 * does not hold.
 */
internal fun foregroundServiceTypes(
    callActive: Boolean,
    recordAudioGranted: Boolean,
    sdkInt: Int,
): Int {
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (sdkInt >= Build.VERSION_CODES.R && callActive && recordAudioGranted) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    return types
}
