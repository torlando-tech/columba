package com.lxmf.messenger.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.lxmf.messenger.IReticulumService

/**
 * Companion Device Service for RNode Bluetooth devices.
 *
 * This service enables Android to recognize Columba as the companion app for associated
 * RNode devices. When a user taps "Connect" on an RNode in system Bluetooth settings,
 * Android will launch Columba.
 *
 * The service binding is automatically managed by the system based on device presence:
 * - Bound when the associated RNode is within BLE range or connected via Bluetooth
 * - Unbound when the device moves out of range or disconnects
 *
 * When the RNode reappears (comes back into BLE range), this service triggers
 * the ReticulumBridgeService to reconnect to the RNode interface.
 *
 * Requires Android 12 (API 31) or higher.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RNodeCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "RNodeCompanionService"
        private const val RECONNECT_DELAY_MS = 2000L // Wait 2s before reconnecting to ensure device is stable
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingReconnect: Runnable? = null
    private var reticulumService: IReticulumService? = null
    private var isServiceBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                Log.d(TAG, "Connected to ReticulumBridgeService")
                reticulumService = IReticulumService.Stub.asInterface(binder)
                isServiceBound = true

                // Now that we're connected, trigger the reconnection
                triggerReconnection()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Disconnected from ReticulumBridgeService")
                reticulumService = null
                isServiceBound = false
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RNodeCompanionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RNodeCompanionService destroyed")

        // Cancel any pending reconnect
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null

        // Unbind from service if bound
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding from service", e)
            }
            isServiceBound = false
        }
    }

    /**
     * Called when an associated RNode device appears (connects or comes into BLE range).
     * Android 13+ (API 33) version with AssociationInfo.
     */
    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val deviceName = associationInfo.displayName ?: "Unknown"
        Log.d(TAG, "████ RNODE APPEARED ████ name=$deviceName")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = associationInfo.associatedDevice?.bluetoothDevice
            if (device != null) {
                Log.d(TAG, "Device: ${device.name ?: device.address}")
            }
        }

        // Schedule reconnection with debounce
        scheduleReconnection()
    }

    /**
     * Called when an associated RNode device disappears (disconnects or goes out of BLE range).
     * Android 13+ (API 33) version with AssociationInfo.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(TAG, "████ RNODE DISAPPEARED ████ name=${associationInfo.displayName ?: "Unknown"}")

        // Cancel any pending reconnection if device disappears
        pendingReconnect?.let {
            handler.removeCallbacks(it)
            Log.d(TAG, "Cancelled pending reconnection due to device disappearance")
        }
        pendingReconnect = null
    }

    /**
     * Legacy callback for Android 12 (API 31-32).
     * Called when an associated device appears.
     */
    @Deprecated("Use onDeviceAppeared(AssociationInfo) for Android 13+")
    override fun onDeviceAppeared(address: String) {
        Log.d(TAG, "████ RNODE APPEARED (legacy) ████ address=$address")
        scheduleReconnection()
    }

    /**
     * Legacy callback for Android 12 (API 31-32).
     * Called when an associated device disappears.
     */
    @Deprecated("Use onDeviceDisappeared(AssociationInfo) for Android 13+")
    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "████ RNODE DISAPPEARED (legacy) ████ address=$address")

        // Cancel any pending reconnection
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
    }

    /**
     * Schedule reconnection with debounce to avoid spamming reconnection attempts
     * if the device appears/disappears rapidly.
     */
    private fun scheduleReconnection() {
        // Cancel any existing pending reconnection
        pendingReconnect?.let { handler.removeCallbacks(it) }

        Log.d(TAG, "Scheduling RNode reconnection in ${RECONNECT_DELAY_MS}ms")

        pendingReconnect =
            Runnable {
                Log.d(TAG, "Executing scheduled RNode reconnection")
                bindAndReconnect()
            }

        handler.postDelayed(pendingReconnect!!, RECONNECT_DELAY_MS)
    }

    /**
     * Bind to ReticulumBridgeService and trigger reconnection.
     */
    private fun bindAndReconnect() {
        if (isServiceBound && reticulumService != null) {
            // Already bound, just trigger reconnection
            triggerReconnection()
            return
        }

        Log.d(TAG, "Binding to ReticulumBridgeService for reconnection")

        try {
            val intent = Intent(this, ReticulumService::class.java)
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.e(TAG, "Failed to bind to ReticulumBridgeService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to ReticulumBridgeService", e)
        }
    }

    /**
     * Call the reconnectRNodeInterface method on the service.
     */
    private fun triggerReconnection() {
        val service = reticulumService
        if (service == null) {
            Log.e(TAG, "Cannot trigger reconnection - service is null")
            return
        }

        try {
            Log.i(TAG, "Triggering RNode interface reconnection")
            service.reconnectRNodeInterface()
        } catch (e: Exception) {
            Log.e(TAG, "Error calling reconnectRNodeInterface", e)
        }
    }
}
