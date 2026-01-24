package com.lxmf.messenger.viewmodel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.util.InterfaceReconnectSignal
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * State for the interface stats screen.
 */
@androidx.compose.runtime.Immutable
data class InterfaceStatsState(
    val interfaceEntity: InterfaceEntity? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    // Runtime status
    val isOnline: Boolean = false,
    val isConnecting: Boolean = false,
    // RNode-specific stats (from live connection)
    val rssi: Int? = null,
    val snr: Float? = null,
    // Traffic stats (from Python/RNS)
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    // Parsed config fields for display
    val connectionMode: String? = null,
    val targetDeviceName: String? = null,
    val tcpHost: String? = null,
    val tcpPort: Int? = null,
    val usbDeviceId: Int? = null,
    val frequency: Long? = null,
    val bandwidth: Int? = null,
    val spreadingFactor: Int? = null,
    val txPower: Int? = null,
    val codingRate: Int? = null,
    val interfaceMode: String? = null,
    // USB permission state
    val needsUsbPermission: Boolean = false,
)

/**
 * ViewModel for displaying interface statistics and status.
 */
@HiltViewModel
class InterfaceStatsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val interfaceRepository: InterfaceRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val configManager: InterfaceConfigManager,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        companion object {
            private const val TAG = "InterfaceStatsVM"
            private const val STATS_REFRESH_INTERVAL_MS = 1000L // Poll faster for responsive UI
            private const val CONNECTING_TIMEOUT_MS = 15000L // Stop showing "connecting" after 15s
            private const val ACTION_USB_PERMISSION = "com.lxmf.messenger.USB_PERMISSION"

            // Test configuration flags - disable background operations during tests
            internal var enableStatsPolling = true
            internal var enableReconnectSignalObserver = true
        }

        private val usbManager: UsbManager by lazy {
            context.getSystemService(Context.USB_SERVICE) as UsbManager
        }

        // Broadcast receiver for USB permission results
        private val usbPermissionReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "USB permission result received: granted=$granted")
                        if (granted) {
                            // Permission granted - trigger reconnect
                            viewModelScope.launch {
                                Log.d(TAG, "Triggering RNode reconnect after permission granted")
                                signalReconnecting()
                                reticulumProtocol.reconnectRNodeInterface()
                            }
                        }
                    }
                }
            }

        private var receiverRegistered = false

        private val interfaceId: Long = savedStateHandle["interfaceId"] ?: -1L

        private val _state = MutableStateFlow(InterfaceStatsState())
        val state: StateFlow<InterfaceStatsState> = _state.asStateFlow()

        // Track when we started showing "connecting" state
        private var connectingStartTime: Long = 0L
        private var hasEverBeenOnline: Boolean = false
        private var lastReconnectSignal: Long = 0L

        init {
            if (interfaceId >= 0) {
                loadInterface()
                if (enableStatsPolling) {
                    startStatsPolling()
                }
                if (enableReconnectSignalObserver) {
                    observeReconnectSignal()
                }
            } else {
                _state.update { it.copy(isLoading = false, errorMessage = "Invalid interface ID") }
            }
        }

        /**
         * Observe the shared reconnect signal to reset connecting state when USB is reattached.
         */
        private fun observeReconnectSignal() {
            viewModelScope.launch {
                InterfaceReconnectSignal.reconnectTimestamp.collect { timestamp ->
                    if (timestamp > lastReconnectSignal && lastReconnectSignal > 0) {
                        // New reconnect signal received - reset connecting state
                        signalReconnecting()
                    }
                    lastReconnectSignal = timestamp
                }
            }
        }

        private fun loadInterface() {
            viewModelScope.launch {
                try {
                    val entity = interfaceRepository.getInterfaceByIdOnce(interfaceId)
                    if (entity != null) {
                        val parsedConfig = parseConfigJson(entity.configJson, entity.type)
                        _state.update {
                            it.copy(
                                interfaceEntity = entity,
                                isLoading = false,
                                connectionMode = parsedConfig.connectionMode,
                                targetDeviceName = parsedConfig.targetDeviceName,
                                tcpHost = parsedConfig.tcpHost,
                                tcpPort = parsedConfig.tcpPort,
                                usbDeviceId = parsedConfig.usbDeviceId,
                                frequency = parsedConfig.frequency,
                                bandwidth = parsedConfig.bandwidth,
                                spreadingFactor = parsedConfig.spreadingFactor,
                                txPower = parsedConfig.txPower,
                                codingRate = parsedConfig.codingRate,
                                interfaceMode = parsedConfig.interfaceMode,
                            )
                        }
                        Log.d(TAG, "Loaded interface: ${entity.name}")
                    } else {
                        _state.update {
                            it.copy(isLoading = false, errorMessage = "Interface not found")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading interface", e)
                    _state.update {
                        it.copy(isLoading = false, errorMessage = "Error loading interface: ${e.message}")
                    }
                }
            }
        }

        private fun startStatsPolling() {
            viewModelScope.launch {
                while (isActive) {
                    refreshStats()
                    delay(STATS_REFRESH_INTERVAL_MS)
                }
            }
        }

        internal suspend fun refreshStats() {
            val entity = _state.value.interfaceEntity ?: return

            try {
                // Get interface online status from the protocol
                val interfaceStats = reticulumProtocol.getInterfaceStats(entity.name)
                val isOnline = interfaceStats?.get("online") as? Boolean ?: false
                val rxBytes = (interfaceStats?.get("rxb") as? Number)?.toLong() ?: 0L
                val txBytes = (interfaceStats?.get("txb") as? Number)?.toLong() ?: 0L

                // Track if interface has ever been online during this session
                if (isOnline) {
                    hasEverBeenOnline = true
                }

                // Determine connecting state:
                // Show "connecting" only when:
                // 1. Interface is enabled and offline
                // 2. Interface hasn't been online yet during this session (so we're waiting for initial connection)
                // 3. We haven't exceeded the timeout (avoid showing spinner forever)
                val isConnecting =
                    if (entity.enabled && !isOnline && !hasEverBeenOnline) {
                        val now = System.currentTimeMillis()
                        if (connectingStartTime == 0L) {
                            connectingStartTime = now
                        }
                        (now - connectingStartTime) < CONNECTING_TIMEOUT_MS
                    } else {
                        false
                    }

                // For RNode interfaces, also get RSSI if available
                var rssi: Int? = null
                if (entity.type == "RNode" && isOnline) {
                    val rnodeRssi = configManager.getRNodeRssi()
                    if (rnodeRssi > -100) {
                        rssi = rnodeRssi
                    }
                }

                // Check USB permission for USB-mode RNode interfaces that are offline
                val needsUsbPermission =
                    if (!isOnline && _state.value.connectionMode == "usb") {
                        val usbDeviceId = _state.value.usbDeviceId
                        if (usbDeviceId != null) {
                            checkNeedsUsbPermission(usbDeviceId)
                        } else {
                            false
                        }
                    } else {
                        false
                    }

                _state.update {
                    it.copy(
                        isOnline = isOnline,
                        isConnecting = isConnecting,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        rssi = rssi,
                        needsUsbPermission = needsUsbPermission,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing stats", e)
            }
        }

        /**
         * Signal that a reconnection attempt is starting.
         * This resets the connecting state so the UI shows the spinner.
         */
        fun signalReconnecting() {
            hasEverBeenOnline = false
            connectingStartTime = 0L
            _state.update { it.copy(isConnecting = true) }
            Log.d(TAG, "Reconnection signaled - showing connecting spinner")
        }

        /**
         * Check if a USB device exists but lacks permission.
         * Returns true if the device is found but we don't have permission.
         */
        private fun checkNeedsUsbPermission(usbDeviceId: Int): Boolean {
            val device = usbManager.deviceList.values.find { it.deviceId == usbDeviceId }
            return if (device != null) {
                val hasPermission = usbManager.hasPermission(device)
                Log.d(TAG, "USB device $usbDeviceId found, hasPermission=$hasPermission")
                !hasPermission
            } else {
                Log.d(TAG, "USB device $usbDeviceId not found in device list")
                false
            }
        }

        /**
         * Request USB permission for the configured device.
         * This will show the system permission dialog.
         */
        fun requestUsbPermission() {
            val usbDeviceId = _state.value.usbDeviceId ?: return
            val device = usbManager.deviceList.values.find { it.deviceId == usbDeviceId }
            if (device == null) {
                Log.w(TAG, "Cannot request permission - USB device $usbDeviceId not found")
                return
            }

            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "Already have permission for USB device $usbDeviceId")
                // Trigger reconnect since we have permission
                viewModelScope.launch {
                    signalReconnecting()
                    reticulumProtocol.reconnectRNodeInterface()
                }
                return
            }

            // Register receiver to listen for permission result
            if (!receiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(usbPermissionReceiver, filter)
                }
                receiverRegistered = true
                Log.d(TAG, "Registered USB permission receiver")
            }

            val intent =
                Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                }
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            Log.d(TAG, "Requesting USB permission for device $usbDeviceId")
            usbManager.requestPermission(device, permissionIntent)
        }

        /**
         * Toggle the enabled state of the interface.
         */
        fun toggleEnabled() {
            val entity = _state.value.interfaceEntity ?: return
            viewModelScope.launch {
                try {
                    val newEnabled = !entity.enabled
                    interfaceRepository.toggleInterfaceEnabled(entity.id, newEnabled)
                    _state.update {
                        it.copy(interfaceEntity = entity.copy(enabled = newEnabled))
                    }
                    Log.d(TAG, "Toggled interface ${entity.name} enabled: $newEnabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling interface", e)
                }
            }
        }

        /**
         * Parsed configuration fields from configJson.
         */
        private data class ParsedConfig(
            val connectionMode: String? = null,
            val targetDeviceName: String? = null,
            val tcpHost: String? = null,
            val tcpPort: Int? = null,
            val usbDeviceId: Int? = null,
            val frequency: Long? = null,
            val bandwidth: Int? = null,
            val spreadingFactor: Int? = null,
            val txPower: Int? = null,
            val codingRate: Int? = null,
            val interfaceMode: String? = null,
        )

        @Suppress("SwallowedException")
        private fun parseConfigJson(
            configJson: String,
            type: String,
        ): ParsedConfig {
            return try {
                val json = JSONObject(configJson)
                when (type) {
                    "RNode" ->
                        ParsedConfig(
                            connectionMode = json.optString("connection_mode", "classic"),
                            targetDeviceName = json.optString("target_device_name", "").ifEmpty { null },
                            tcpHost = json.optString("tcp_host", "").ifEmpty { null },
                            tcpPort = if (json.has("tcp_port")) json.getInt("tcp_port") else null,
                            usbDeviceId = if (json.has("usb_device_id")) json.getInt("usb_device_id") else null,
                            frequency = json.optLong("frequency", 0).takeIf { it > 0 },
                            bandwidth = json.optInt("bandwidth", 0).takeIf { it > 0 },
                            spreadingFactor = json.optInt("spreading_factor", 0).takeIf { it > 0 },
                            txPower = json.optInt("tx_power", 0).takeIf { it > 0 },
                            codingRate = json.optInt("coding_rate", 0).takeIf { it > 0 },
                            interfaceMode = json.optString("mode", "full"),
                        )
                    "TCPClient" ->
                        ParsedConfig(
                            tcpHost = json.optString("target_host", ""),
                            tcpPort = json.optInt("target_port", 4242),
                            interfaceMode = json.optString("mode", "full"),
                        )
                    "TCPServer" ->
                        ParsedConfig(
                            tcpHost = json.optString("listen_ip", "0.0.0.0"),
                            tcpPort = json.optInt("listen_port", 4242),
                            interfaceMode = json.optString("mode", "full"),
                        )
                    else ->
                        ParsedConfig(
                            interfaceMode = json.optString("mode", "full"),
                        )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing config JSON", e)
                ParsedConfig()
            }
        }

        override fun onCleared() {
            super.onCleared()
            // Unregister USB permission receiver if registered
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(usbPermissionReceiver)
                    Log.d(TAG, "Unregistered USB permission receiver")
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering USB permission receiver", e)
                }
                receiverRegistered = false
            }
        }
    }
