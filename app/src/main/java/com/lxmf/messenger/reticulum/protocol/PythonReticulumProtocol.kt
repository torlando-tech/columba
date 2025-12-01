package com.lxmf.messenger.reticulum.protocol

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.lxmf.messenger.reticulum.bridge.KotlinReticulumBridge
import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.model.PacketType
import com.lxmf.messenger.reticulum.model.ReceivedPacket
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.reticulum.util.SmartPoller
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Python-based implementation of ReticulumProtocol using Chaquopy.
 * This bridges Kotlin to the Python Reticulum implementation.
 */
class PythonReticulumProtocol(
    private val context: Context,
) : ReticulumProtocol {
    companion object {
        private const val TAG = "PythonReticulumProtocol"

        // Helper to safely get values from Python dict
        private fun PyObject.getDictValue(key: String): PyObject? {
            return this.callAttr("get", key)
        }

        // Helper to convert Direction to string
        private fun Direction.toDirectionString(): String =
            when (this) {
                is Direction.IN -> "IN"
                is Direction.OUT -> "OUT"
            }

        // Helper to convert PacketType to string
        private fun PacketType.toPacketTypeString(): String =
            when (this) {
                is PacketType.DATA -> "DATA"
                is PacketType.ANNOUNCE -> "ANNOUNCE"
                is PacketType.LINKREQUEST -> "LINKREQUEST"
                is PacketType.PROOF -> "PROOF"
            }

        // Helper to convert DestinationType to string
        private fun DestinationType.toDestinationTypeString(): String =
            when (this) {
                is DestinationType.SINGLE -> "SINGLE"
                is DestinationType.GROUP -> "GROUP"
                is DestinationType.PLAIN -> "PLAIN"
            }
    }

    private var wrapper: PyObject? = null

    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // Flow for announces
    private val announceFlow =
        MutableSharedFlow<AnnounceEvent>(
            replay = 0,
            extraBufferCapacity = 100,
        )

    // Polling job for announces
    private var pollingJob: Job? = null

    // Polling scope uses IO dispatcher for background polling operations
    // Phase 2.2: Smart polling with exponential backoff (2s-30s adaptive)
    // SupervisorJob ensures polling failures don't affect other operations
    private val pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Smart poller for adaptive polling (Phase 2.2)
    // Event-driven announces via KotlinReticulumBridge for instant delivery
    // Polling remains active as fallback with shorter intervals
    private val announcesPoller =
        SmartPoller(
            minInterval = 2_000, // 2s when active
            maxInterval = 10_000, // 10s max (reduced from 30s)
        )

    // WiFi locks for multicast reception
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    override suspend fun initialize(config: ReticulumConfig): Result<Unit> {
        return runCatching {
            Log.d(TAG, "=== Starting Reticulum Initialization ===")
            Log.d(TAG, "Step 1: Getting Python instance")

            val py = Python.getInstance()
            Log.d(TAG, "Step 2: Getting reticulum_wrapper module")

            val module =
                try {
                    py.getModule("reticulum_wrapper")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get reticulum_wrapper module", e)
                    throw RuntimeException("Failed to load Python module: ${e.message}", e)
                }

            // Use app's internal storage for Reticulum data
            val storagePath = context.filesDir.absolutePath + "/reticulum"
            Log.d(TAG, "Step 3: Storage path: $storagePath")

            // Create wrapper instance
            Log.d(TAG, "Step 4: Creating ReticulumWrapper instance")
            wrapper =
                try {
                    module.callAttr("ReticulumWrapper", storagePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create ReticulumWrapper", e)
                    throw RuntimeException("Failed to create wrapper: ${e.message}", e)
                }

            // Set up ReticulumBridge for event-driven Python-to-Kotlin callbacks
            Log.d(TAG, "Step 4b: Setting up ReticulumBridge for event-driven callbacks")
            try {
                val reticulumBridge = KotlinReticulumBridge.getInstance()
                wrapper?.callAttr("set_reticulum_bridge", reticulumBridge)

                // Register for immediate announce notifications
                reticulumBridge.setOnAnnounceReceived {
                    pollingScope.launch {
                        val announces = wrapper?.callAttr("get_pending_announces")?.asList()
                        if (announces != null && announces.isNotEmpty()) {
                            Log.d(TAG, "Event-driven: processing ${announces.size} announces")
                            for (announceObj in announces) {
                                handleAnnounceEvent(announceObj as PyObject)
                            }
                        }
                    }
                }
                Log.d(TAG, "ReticulumBridge configured for event-driven announces")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ReticulumBridge: ${e.message}", e)
                // Non-fatal - polling will still work as fallback
            }

            // Validate config BEFORE building JSON
            Log.d(TAG, "Step 5: Validating config")
            try {
                validateReticulumConfig(config)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Config validation failed", e)
                throw RuntimeException("Invalid configuration: ${e.message}", e)
            }

            // Convert config to JSON
            Log.d(TAG, "Step 6: Building config JSON")
            Log.d(TAG, "Config has ${config.enabledInterfaces.size} interfaces")

            val configJson =
                try {
                    buildConfigJson(config)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build config JSON", e)
                    throw RuntimeException("Failed to build config: ${e.message}", e)
                }

            // Verify JSON structure
            try {
                JSONObject(configJson) // Parse to verify structure
            } catch (e: JSONException) {
                Log.e(TAG, "Generated invalid JSON structure", e)
                throw RuntimeException("Invalid JSON structure generated", e)
            }

            Log.d(TAG, "Step 7: Config JSON: $configJson")

            // Initialize
            Log.d(TAG, "Step 8: Calling Python initialize()")
            _networkStatus.value = NetworkStatus.INITIALIZING

            val result =
                try {
                    wrapper!!.callAttr("initialize", configJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Python initialize() call failed: ${e.message}", e)
                    _networkStatus.value = NetworkStatus.ERROR("Python error: ${e.message}")
                    throw RuntimeException("Python initialize failed: ${e.message}", e)
                }

            Log.d(TAG, "Step 9: Checking result")
            val success = result.getDictValue("success")?.toBoolean() ?: false
            Log.d(TAG, "Success value: $success")

            if (success) {
                // Acquire WiFi locks for multicast reception
                Log.d(TAG, "Step 10: Acquiring WiFi locks")
                acquireWifiLocks()

                // Start polling for announces
                Log.d(TAG, "Step 11: Starting announces polling")
                startAnnouncesPolling()
                _networkStatus.value = NetworkStatus.READY
                Log.d(TAG, "=== Reticulum Initialized Successfully ===")
                Unit // Explicitly return Unit
            } else {
                val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                Log.e(TAG, "Initialization failed with error: $error")
                _networkStatus.value = NetworkStatus.ERROR(error)
                throw RuntimeException(error)
            }
        }.onFailure { e ->
            Log.e(TAG, "=== Reticulum Initialization FAILED ===", e)
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Error cause: ${e.cause}")
            _networkStatus.value = NetworkStatus.ERROR(e.message ?: "Unknown error")
            Unit // Explicitly return Unit
        }
    }

    private fun acquireWifiLocks() {
        try {
            // Acquire multicast lock - CRITICAL for receiving UDP multicast packets
            if (multicastLock == null || !multicastLock!!.isHeld) {
                multicastLock = wifiManager.createMulticastLock("ReticulumMulticast")
                multicastLock?.setReferenceCounted(false)
                multicastLock?.acquire()
                Log.i(TAG, "MulticastLock acquired - phone can now receive multicast announces!")
            }

            // Acquire WiFi lock for reliability
            if (wifiLock == null || !wifiLock!!.isHeld) {
                wifiLock =
                    wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "ReticulumWifi",
                    )
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
                Log.i(TAG, "WifiLock acquired - WiFi will stay active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WiFi locks", e)
            // Don't fail initialization if locks can't be acquired
        }
    }

    private fun releaseWifiLocks() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.i(TAG, "MulticastLock released")
            }
            multicastLock = null

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock released")
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WiFi locks", e)
        }
    }

    private fun startAnnouncesPolling() {
        pollingJob?.cancel()
        pollingJob =
            pollingScope.launch {
                Log.d(TAG, "Started announces polling with smart polling")
                announcesPoller.reset() // Reset to initial state

                while (isActive) {
                    try {
                        // Poll for new announces from Python
                        val announces = wrapper?.callAttr("get_pending_announces")?.asList()

                        if (announces != null && announces.isNotEmpty()) {
                            Log.d(TAG, "Retrieved ${announces.size} pending announces")
                            announcesPoller.markActive() // Frequent polling when active

                            for (announceObj in announces) {
                                handleAnnounceEvent(announceObj as PyObject)
                            }
                        } else {
                            announcesPoller.markIdle() // Back off when idle
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error polling announces", e)
                    }

                    // Smart polling with exponential backoff
                    val nextInterval = announcesPoller.getNextInterval()
                    Log.d(TAG, "Next announce check in ${nextInterval}ms")
                    delay(nextInterval)
                }
            }
    }

    /**
     * Validates Reticulum configuration before passing to Python.
     *
     * Validates:
     * - Interface names (non-empty, within length limits)
     * - Hostnames and IP addresses (proper format)
     * - Port numbers (valid range 1-65535)
     * - Device names (non-empty, within length limits)
     *
     * @param config The configuration to validate
     * @return true if valid, false otherwise
     * @throws IllegalArgumentException if validation fails with details
     */
    private fun validateReticulumConfig(config: ReticulumConfig) {
        Log.d(TAG, "Validating Reticulum configuration...")

        // Validate each interface
        for ((index, iface) in config.enabledInterfaces.withIndex()) {
            when (iface) {
                is InterfaceConfig.AutoInterface -> {
                    // Validate interface name
                    when (val nameResult = InputValidator.validateInterfaceName(iface.name)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index: ${nameResult.message}")
                        }
                        else -> {}
                    }

                    // Validate ports
                    when (val portResult = InputValidator.validatePort(iface.discoveryPort.toString())) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException(
                                "Interface $index (${iface.name}): Invalid discovery port - ${portResult.message}",
                            )
                        }
                        else -> {}
                    }

                    when (val portResult = InputValidator.validatePort(iface.dataPort.toString())) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index (${iface.name}): Invalid data port - ${portResult.message}")
                        }
                        else -> {}
                    }
                }

                is InterfaceConfig.TCPClient -> {
                    // Validate interface name
                    when (val nameResult = InputValidator.validateInterfaceName(iface.name)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index: ${nameResult.message}")
                        }
                        else -> {}
                    }

                    // Validate target host
                    when (val hostResult = InputValidator.validateHostname(iface.targetHost)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index (${iface.name}): Invalid target host - ${hostResult.message}")
                        }
                        else -> {}
                    }

                    // Validate target port
                    when (val portResult = InputValidator.validatePort(iface.targetPort.toString())) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index (${iface.name}): Invalid target port - ${portResult.message}")
                        }
                        else -> {}
                    }
                }

                is InterfaceConfig.UDP -> {
                    // Validate interface name
                    when (val nameResult = InputValidator.validateInterfaceName(iface.name)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index: ${nameResult.message}")
                        }
                        else -> {}
                    }

                    // Validate listen IP
                    when (val hostResult = InputValidator.validateHostname(iface.listenIp)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index (${iface.name}): Invalid listen IP - ${hostResult.message}")
                        }
                        else -> {}
                    }

                    // Validate listen port
                    when (val portResult = InputValidator.validatePort(iface.listenPort.toString())) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index (${iface.name}): Invalid listen port - ${portResult.message}")
                        }
                        else -> {}
                    }

                    // Validate forward IP (optional)
                    if (iface.forwardIp.isNotEmpty()) {
                        when (val hostResult = InputValidator.validateHostname(iface.forwardIp)) {
                            is ValidationResult.Error -> {
                                throw IllegalArgumentException(
                                    "Interface $index (${iface.name}): Invalid forward IP - ${hostResult.message}",
                                )
                            }
                            else -> {}
                        }
                    }

                    // Validate forward port (optional)
                    if (iface.forwardPort > 0) {
                        when (val portResult = InputValidator.validatePort(iface.forwardPort.toString())) {
                            is ValidationResult.Error -> {
                                throw IllegalArgumentException(
                                    "Interface $index (${iface.name}): Invalid forward port - ${portResult.message}",
                                )
                            }
                            else -> {}
                        }
                    }
                }

                is InterfaceConfig.RNode -> {
                    // Validate interface name
                    when (val nameResult = InputValidator.validateInterfaceName(iface.name)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index: ${nameResult.message}")
                        }
                        else -> {}
                    }
                    // RNode has hardware-specific params that don't need string validation
                }

                is InterfaceConfig.AndroidBLE -> {
                    // Validate interface name
                    when (val nameResult = InputValidator.validateInterfaceName(iface.name)) {
                        is ValidationResult.Error -> {
                            throw IllegalArgumentException("Interface $index: ${nameResult.message}")
                        }
                        else -> {}
                    }

                    // Validate device name only if not empty
                    // Empty device names are allowed - they omit the name from BLE advertisement
                    if (iface.deviceName.isNotBlank()) {
                        when (val deviceResult = InputValidator.validateDeviceName(iface.deviceName)) {
                            is ValidationResult.Error -> {
                                throw IllegalArgumentException(
                                    "Interface $index (${iface.name}): Invalid device name - ${deviceResult.message}",
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Reticulum configuration validation passed")
    }

    private fun buildConfigJson(config: ReticulumConfig): String {
        Log.d(TAG, "buildConfigJson: Starting")
        try {
            val json = JSONObject()

            Log.d(TAG, "buildConfigJson: Setting storagePath=${config.storagePath}")
            json.put("storagePath", config.storagePath)

            Log.d(TAG, "buildConfigJson: Setting logLevel=${config.logLevel}")
            // LogLevel is an enum, so .name should work
            val logLevelName =
                try {
                    config.logLevel.name
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting logLevel.name", e)
                    "INFO" // fallback
                }
            json.put("logLevel", logLevelName)

            Log.d(TAG, "buildConfigJson: Setting allowAnonymous=${config.allowAnonymous}")
            json.put("allowAnonymous", config.allowAnonymous)

            // Build interfaces array
            Log.d(TAG, "buildConfigJson: Building interfaces array (${config.enabledInterfaces.size} interfaces)")
            val interfacesArray = JSONArray()

            for ((index, iface) in config.enabledInterfaces.withIndex()) {
                Log.d(TAG, "buildConfigJson: Processing interface $index: ${iface.javaClass.simpleName}")
                val ifaceJson = JSONObject()

                try {
                    when (iface) {
                        is InterfaceConfig.AutoInterface -> {
                            Log.d(TAG, "buildConfigJson: AutoInterface - ${iface.name}")
                            ifaceJson.put("type", "AutoInterface")
                            ifaceJson.put("name", iface.name)
                            ifaceJson.put("group_id", iface.groupId)
                            ifaceJson.put("discovery_scope", iface.discoveryScope)
                            ifaceJson.put("discovery_port", iface.discoveryPort)
                            ifaceJson.put("data_port", iface.dataPort)
                            ifaceJson.put("mode", iface.mode)
                        }
                        is InterfaceConfig.TCPClient -> {
                            Log.d(TAG, "buildConfigJson: TCPClient - ${iface.name}")
                            ifaceJson.put("type", "TCPClient")
                            ifaceJson.put("name", iface.name)
                            ifaceJson.put("target_host", iface.targetHost)
                            ifaceJson.put("target_port", iface.targetPort)
                            ifaceJson.put("kiss_framing", iface.kissFraming)
                            ifaceJson.put("mode", iface.mode)
                        }
                        is InterfaceConfig.UDP -> {
                            Log.d(TAG, "buildConfigJson: UDP - ${iface.name}")
                            ifaceJson.put("type", "UDP")
                            ifaceJson.put("name", iface.name)
                            ifaceJson.put("listen_ip", iface.listenIp)
                            ifaceJson.put("listen_port", iface.listenPort)
                            ifaceJson.put("forward_ip", iface.forwardIp)
                            ifaceJson.put("forward_port", iface.forwardPort)
                            ifaceJson.put("mode", iface.mode)
                        }
                        is InterfaceConfig.RNode -> {
                            Log.d(TAG, "buildConfigJson: RNode - ${iface.name}")
                            ifaceJson.put("type", "RNode")
                            ifaceJson.put("name", iface.name)
                            ifaceJson.put("port", iface.port)
                            ifaceJson.put("frequency", iface.frequency)
                            ifaceJson.put("bandwidth", iface.bandwidth)
                            ifaceJson.put("tx_power", iface.txPower)
                            ifaceJson.put("spreading_factor", iface.spreadingFactor)
                            ifaceJson.put("coding_rate", iface.codingRate)
                            ifaceJson.put("mode", iface.mode)
                        }
                        is InterfaceConfig.AndroidBLE -> {
                            Log.d(TAG, "buildConfigJson: AndroidBLE - ${iface.name}")
                            ifaceJson.put("type", "AndroidBLE")
                            ifaceJson.put("name", iface.name)
                            ifaceJson.put("device_name", iface.deviceName)
                            ifaceJson.put("max_connections", iface.maxConnections)
                            ifaceJson.put("mode", iface.mode)
                        }
                    }
                    interfacesArray.put(ifaceJson)
                    Log.d(TAG, "buildConfigJson: Interface $index added successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "buildConfigJson: Error processing interface $index", e)
                    throw e
                }
            }

            json.put("enabledInterfaces", interfacesArray)

            val result = json.toString()
            Log.d(TAG, "buildConfigJson: Complete. JSON length: ${result.length}")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "buildConfigJson: Exception occurred", e)
            Log.e(TAG, "buildConfigJson: Exception type: ${e.javaClass.name}")
            Log.e(TAG, "buildConfigJson: Exception message: ${e.message}")
            throw e
        }
    }

    private fun handleAnnounceEvent(event: PyObject) {
        try {
            val destinationHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val identityHash = event.getDictValue("identity_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val publicKey = event.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
            val appData = event.getDictValue("app_data")?.toJava(ByteArray::class.java) as? ByteArray
            val hops = event.getDictValue("hops")?.toInt() ?: 0
            val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()
            val aspect = event.getDictValue("aspect")?.toString() // Try to get aspect if provided
            val receivingInterface = event.getDictValue("interface")?.toString() // Get interface name if provided

            if (destinationHash != null && identityHash != null && publicKey != null) {
                val identity =
                    Identity(
                        hash = identityHash,
                        publicKey = publicKey,
                        privateKey = null, // We don't have the private key for remote nodes
                    )

                // Use NodeTypeDetector to determine the proper node type
                val nodeType = NodeTypeDetector.detectNodeType(appData, aspect)

                val announceEvent =
                    AnnounceEvent(
                        destinationHash = destinationHash,
                        identity = identity,
                        appData = appData,
                        hops = hops,
                        timestamp = timestamp,
                        nodeType = nodeType,
                        receivingInterface = receivingInterface,
                    )

                // Emit to flow
                announceFlow.tryEmit(announceEvent)
                Log.d(TAG, "Announce event emitted: ${destinationHash.take(8).joinToString("") { "%02x".format(it) }}, type: $nodeType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling announce event", e)
        }
    }

    override suspend fun shutdown(): Result<Unit> {
        return runCatching {
            Log.d(TAG, "Shutting down Reticulum")

            // Stop polling
            pollingJob?.cancel()
            pollingJob = null

            // Release WiFi locks
            releaseWifiLocks()

            // Shutdown Python wrapper
            wrapper?.callAttr("shutdown")
            _networkStatus.value = NetworkStatus.SHUTDOWN
            wrapper = null
        }
    }

    override suspend fun createIdentity(): Result<Identity> {
        return runCatching {
            Log.d(TAG, "Creating identity")
            val result = wrapper!!.callAttr("create_identity")

            val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as ByteArray
            val publicKey = result.getDictValue("public_key")?.toJava(ByteArray::class.java) as ByteArray
            val privateKey = result.getDictValue("private_key")?.toJava(ByteArray::class.java) as ByteArray

            Identity(
                hash = hash,
                publicKey = publicKey,
                privateKey = privateKey,
            )
        }
    }

    override suspend fun loadIdentity(path: String): Result<Identity> {
        return runCatching {
            Log.d(TAG, "Loading identity from $path")
            val result = wrapper!!.callAttr("load_identity", path)

            val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as ByteArray
            val publicKey = result.getDictValue("public_key")?.toJava(ByteArray::class.java) as ByteArray
            val privateKey = result.getDictValue("private_key")?.toJava(ByteArray::class.java) as ByteArray

            Identity(
                hash = hash,
                publicKey = publicKey,
                privateKey = privateKey,
            )
        }
    }

    override suspend fun saveIdentity(
        identity: Identity,
        path: String,
    ): Result<Unit> {
        return runCatching {
            Log.d(TAG, "Saving identity to $path")
            val result = wrapper!!.callAttr("save_identity", identity.privateKey, path)

            val success = result.getDictValue("success")?.toBoolean() ?: false
            if (!success) {
                val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                throw RuntimeException(error)
            }
        }
    }

    override suspend fun recallIdentity(hash: ByteArray): Identity? {
        // Python wrapper doesn't implement this yet
        return null
    }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> {
        return runCatching {
            Log.d(TAG, "Creating identity with display name: $displayName")
            val result = wrapper!!.callAttr("create_identity", displayName)

            // Convert PyObject dict to Kotlin Map
            val map = mutableMapOf<String, Any>()

            result.getDictValue("identity_hash")?.toString()?.let {
                map["identity_hash"] = it
            }
            result.getDictValue("destination_hash")?.toString()?.let {
                map["destination_hash"] = it
            }
            result.getDictValue("file_path")?.toString()?.let {
                map["file_path"] = it
            }
            result.getDictValue("error")?.toString()?.let {
                map["error"] = it
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to create identity with name", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun deleteIdentityFile(identityHash: String): Map<String, Any> {
        return runCatching {
            Log.d(TAG, "Deleting identity file: $identityHash")
            val result = wrapper!!.callAttr("delete_identity_file", identityHash)

            // Convert PyObject dict to Kotlin Map
            val map = mutableMapOf<String, Any>()

            result.getDictValue("success")?.toBoolean()?.let {
                map["success"] = it
            }
            result.getDictValue("error")?.toString()?.let {
                map["error"] = it
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to delete identity file", e)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any> {
        return runCatching {
            Log.d(TAG, "Importing identity file with display name: $displayName")
            val result = wrapper!!.callAttr("import_identity_file", fileData, displayName)

            // Convert PyObject dict to Kotlin Map
            val map = mutableMapOf<String, Any>()

            result.getDictValue("identity_hash")?.toString()?.let {
                map["identity_hash"] = it
            }
            result.getDictValue("destination_hash")?.toString()?.let {
                map["destination_hash"] = it
            }
            result.getDictValue("file_path")?.toString()?.let {
                map["file_path"] = it
            }
            result.getDictValue("error")?.toString()?.let {
                map["error"] = it
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to import identity file", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray {
        return runCatching {
            Log.d(TAG, "Exporting identity file: $identityHash from $filePath")
            val result = wrapper!!.callAttr("export_identity_file", identityHash, filePath)

            // Convert PyObject bytes to Kotlin ByteArray
            result.toJava(ByteArray::class.java)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to export identity file", e)
            ByteArray(0)
        }
    }

    override suspend fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): Map<String, Any> {
        return runCatching {
            Log.d(TAG, "Recovering identity file: $identityHash to $filePath")
            val result = wrapper!!.callAttr("recover_identity_file", identityHash, keyData, filePath)
            result.asMap().mapKeys { it.key.toString() }.mapValues { it.value as Any }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to recover identity file", e)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> {
        return runCatching {
            Log.d(TAG, "Creating destination: $appName with aspects $aspects")

            // Build identity dict for Python using proper Chaquopy method
            val py = Python.getInstance()
            val builtins = py.getBuiltins()

            // Create a dict using Python's dict() constructor with kwargs
            val identityDict = builtins.callAttr("dict")
            identityDict.callAttr("__setitem__", "hash", identity.hash)
            identityDict.callAttr("__setitem__", "public_key", identity.publicKey)
            identityDict.callAttr("__setitem__", "private_key", identity.privateKey)

            // Convert aspects list to Python list
            val aspectsList = py.getBuiltins().callAttr("list")
            for (aspect in aspects) {
                aspectsList.callAttr("append", aspect)
            }

            val result =
                wrapper!!.callAttr(
                    "create_destination",
                    identityDict,
                    direction.toDirectionString(),
                    type.toDestinationTypeString(),
                    appName,
                    aspectsList,
                )

            val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as ByteArray
            val hexHash = result.getDictValue("hex_hash")?.toString() ?: ""

            Destination(
                hash = hash,
                hexHash = hexHash,
                identity = identity,
                direction = direction,
                type = type,
                appName = appName,
                aspects = aspects,
            )
        }
    }

    override suspend fun announceDestination(
        destination: Destination,
        appData: ByteArray?,
    ): Result<Unit> {
        return runCatching {
            Log.d(TAG, "Announcing destination: ${destination.hexHash}")
            val result = wrapper!!.callAttr("announce_destination", destination.hash, appData)

            val success = result.getDictValue("success")?.toBoolean() ?: false
            if (!success) {
                val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                throw RuntimeException(error)
            }
        }
    }

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> {
        return runCatching {
            Log.d(TAG, "Sending packet to ${destination.hexHash}")
            val result =
                wrapper!!.callAttr(
                    "send_packet",
                    destination.hash,
                    data,
                    packetType.toPacketTypeString(),
                )

            val receiptHash = result.getDictValue("receipt_hash")?.toJava(ByteArray::class.java) as ByteArray
            val delivered = result.getDictValue("delivered")?.toBoolean() ?: false
            val timestamp = result.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()

            PacketReceipt(
                hash = receiptHash,
                delivered = delivered,
                timestamp = timestamp,
            )
        }
    }

    override fun observePackets(): Flow<ReceivedPacket> =
        callbackFlow {
            awaitClose { }
        }

    override suspend fun establishLink(destination: Destination): Result<Link> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override suspend fun closeLink(link: Link): Result<Unit> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override suspend fun sendOverLink(
        link: Link,
        data: ByteArray,
    ): Result<Unit> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override fun observeLinks(): Flow<LinkEvent> =
        callbackFlow {
            awaitClose { }
        }

    override suspend fun hasPath(destinationHash: ByteArray): Boolean {
        return try {
            wrapper?.callAttr("has_path", destinationHash)?.toBoolean() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking path", e)
            false
        }
    }

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> {
        return runCatching {
            val result = wrapper!!.callAttr("request_path", destinationHash)

            val success = result.getDictValue("success")?.toBoolean() ?: false
            if (!success) {
                val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                throw RuntimeException(error)
            }
        }
    }

    override fun getHopCount(destinationHash: ByteArray): Int? {
        return try {
            wrapper?.callAttr("get_hop_count", destinationHash)?.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hop count", e)
            null
        }
    }

    override suspend fun getPathTableHashes(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val result = wrapper?.callAttr("get_path_table")
                if (result != null) {
                    // Convert Python list to Kotlin list of strings
                    result.asList().map { it.toString() }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting path table", e)
                emptyList()
            }
        }
    }

    override fun observeAnnounces(): Flow<AnnounceEvent> = announceFlow

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
    ): Result<MessageReceipt> {
        return Result.failure(NotImplementedError("LXMF messaging not yet implemented for PythonReticulumProtocol"))
    }

    override fun observeMessages(): Flow<ReceivedMessage> =
        callbackFlow {
            awaitClose { }
        }

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> =
        callbackFlow {
            awaitClose { }
        }

    override suspend fun getDebugInfo(): Map<String, Any> {
        return try {
            val result = wrapper?.callAttr("get_debug_info") ?: return emptyMap()

            val debugInfo = mutableMapOf<String, Any>()

            // Extract basic info
            debugInfo["initialized"] = result.getDictValue("initialized")?.toBoolean() ?: false
            debugInfo["reticulum_available"] = result.getDictValue("reticulum_available")?.toBoolean() ?: false
            debugInfo["storage_path"] = result.getDictValue("storage_path")?.toString() ?: ""
            debugInfo["identity_count"] = result.getDictValue("identity_count")?.toInt() ?: 0
            debugInfo["destination_count"] = result.getDictValue("destination_count")?.toInt() ?: 0
            debugInfo["pending_announces"] = result.getDictValue("pending_announces")?.toInt() ?: 0
            debugInfo["transport_enabled"] = result.getDictValue("transport_enabled")?.toBoolean() ?: false

            // Extract interfaces
            val interfacesList = mutableListOf<Map<String, Any>>()
            val interfacesArray = result.getDictValue("interfaces")?.asList()

            if (interfacesArray != null) {
                for (ifaceObj in interfacesArray) {
                    val iface = ifaceObj as? PyObject ?: continue
                    val ifaceMap =
                        mapOf(
                            "name" to (iface.getDictValue("name")?.toString() ?: ""),
                            "type" to (iface.getDictValue("type")?.toString() ?: ""),
                            "online" to (iface.getDictValue("online")?.toBoolean() ?: false),
                        )
                    interfacesList.add(ifaceMap)
                }
            }

            debugInfo["interfaces"] = interfacesList

            // Add lock status
            debugInfo["multicast_lock_held"] = multicastLock?.isHeld ?: false
            debugInfo["wifi_lock_held"] = wifiLock?.isHeld ?: false

            debugInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error getting debug info", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override fun setConversationActive(active: Boolean) {
        // No-op for PythonReticulumProtocol since it doesn't use service-based polling
        // This is only relevant for ServiceReticulumProtocol
        Log.d(TAG, "setConversationActive($active) - no-op for PythonReticulumProtocol")
    }
}
