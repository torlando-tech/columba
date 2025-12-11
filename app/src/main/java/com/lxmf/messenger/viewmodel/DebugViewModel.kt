package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.util.IdentityQrCodeUtils
import com.lxmf.messenger.util.generateDefaultDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class DebugInfo(
    val initialized: Boolean = false,
    val reticulumAvailable: Boolean = false,
    val storagePath: String = "",
    val interfaceCount: Int = 0,
    val interfaces: List<InterfaceInfo> = emptyList(),
    val transportEnabled: Boolean = false,
    val multicastLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val error: String? = null,
)

@androidx.compose.runtime.Immutable
data class InterfaceInfo(
    val name: String,
    val type: String,
    val online: Boolean,
    val error: String? = null, // Error message if interface failed to initialize
)

@androidx.compose.runtime.Immutable
data class TestAnnounceResult(
    val success: Boolean,
    val hexHash: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DebugViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val settingsRepository: SettingsRepository,
        private val identityRepository: com.lxmf.messenger.data.repository.IdentityRepository,
        private val interfaceConfigManager: com.lxmf.messenger.service.InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "DebugViewModel"
            private const val POLL_INTERVAL_MS = 2000L
            private const val DEFAULT_IDENTITY_FILE = "default_identity"
        }

        // Cached identity and destination for test announces - reused across all announces
        private var cachedIdentity: com.lxmf.messenger.reticulum.model.Identity? = null
        private var cachedDestination: com.lxmf.messenger.reticulum.model.Destination? = null

        private val _debugInfo = MutableStateFlow(DebugInfo())
        val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

        private val _testAnnounceResult = MutableStateFlow<TestAnnounceResult?>(null)
        val testAnnounceResult: StateFlow<TestAnnounceResult?> = _testAnnounceResult.asStateFlow()

        private val _networkStatus = MutableStateFlow<String>("Unknown")
        val networkStatus: StateFlow<String> = _networkStatus.asStateFlow()

        // Identity data for QR code sharing
        private val _identityHash = MutableStateFlow<String?>(null)
        val identityHash: StateFlow<String?> = _identityHash.asStateFlow()

        private val _destinationHash = MutableStateFlow<String?>(null)
        val destinationHash: StateFlow<String?> = _destinationHash.asStateFlow()

        private val _publicKey = MutableStateFlow<ByteArray?>(null)
        val publicKey: StateFlow<ByteArray?> = _publicKey.asStateFlow()

        private val _qrCodeData = MutableStateFlow<String?>(null)
        val qrCodeData: StateFlow<String?> = _qrCodeData.asStateFlow()

        private val _isRestarting = MutableStateFlow(false)
        val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

        init {
            startPollingDebugInfo()
            observeNetworkStatus()
            loadIdentityData()
        }

        private fun startPollingDebugInfo() {
            viewModelScope.launch {
                while (true) {
                    try {
                        fetchDebugInfo()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching debug info", e)
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        private fun observeNetworkStatus() {
            viewModelScope.launch {
                // Phase 2.1: Observe StateFlow (no polling!)
                reticulumProtocol.networkStatus.collect { status ->
                    // Convert NetworkStatus to readable string
                    _networkStatus.value =
                        when (status) {
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.READY -> "READY"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.INITIALIZING -> "INITIALIZING"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.CONNECTING -> "CONNECTING"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.ERROR -> "ERROR: ${status.message}"
                            else -> status.toString()
                        }
                }
            }
        }

        private fun fetchDebugInfo() {
            viewModelScope.launch {
                try {
                    // Get real debug info from Python
                    val pythonDebugInfo = reticulumProtocol.getDebugInfo()

                    // Extract interface information
                    @Suppress("UNCHECKED_CAST")
                    val interfacesData = pythonDebugInfo["interfaces"] as? List<Map<String, Any>> ?: emptyList()
                    val activeInterfaces =
                        interfacesData.map { ifaceMap ->
                            InterfaceInfo(
                                name = ifaceMap["name"] as? String ?: "",
                                type = ifaceMap["type"] as? String ?: "",
                                online = ifaceMap["online"] as? Boolean ?: false,
                            )
                        }

                    // Get failed interfaces and add them to the list
                    val failedInterfaces = reticulumProtocol.getFailedInterfaces()
                    val failedInterfaceInfos = failedInterfaces.map { failed ->
                        InterfaceInfo(
                            name = failed.name,
                            type = failed.name, // Use name as type since we don't have detailed type info
                            online = false,
                            error = failed.error,
                        )
                    }

                    // Combine active and failed interfaces
                    val interfaces = activeInterfaces + failedInterfaceInfos

                    // Get status
                    val status = reticulumProtocol.networkStatus.value
                    val isReady = status is com.lxmf.messenger.reticulum.model.NetworkStatus.READY
                    val statusString =
                        when (status) {
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.READY -> "READY"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.INITIALIZING -> "INITIALIZING"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                            is com.lxmf.messenger.reticulum.model.NetworkStatus.ERROR -> "ERROR: ${status.message}"
                            else -> status.toString()
                        }

                    // Build debug info
                    val wakeLockHeld = pythonDebugInfo["wake_lock_held"] as? Boolean ?: false
                    Log.d(
                        TAG,
                        "DebugViewModel: wake_lock_held from service = $wakeLockHeld, " +
                            "raw value = ${pythonDebugInfo["wake_lock_held"]}",
                    )

                    _debugInfo.value =
                        DebugInfo(
                            initialized = pythonDebugInfo["initialized"] as? Boolean ?: false,
                            reticulumAvailable = pythonDebugInfo["reticulum_available"] as? Boolean ?: false,
                            storagePath = pythonDebugInfo["storage_path"] as? String ?: "",
                            interfaceCount = interfaces.size,
                            interfaces = interfaces,
                            transportEnabled = pythonDebugInfo["transport_enabled"] as? Boolean ?: false,
                            multicastLockHeld = pythonDebugInfo["multicast_lock_held"] as? Boolean ?: false,
                            wifiLockHeld = pythonDebugInfo["wifi_lock_held"] as? Boolean ?: false,
                            wakeLockHeld = wakeLockHeld,
                            error =
                                pythonDebugInfo["error"] as? String
                                    ?: if (status is com.lxmf.messenger.reticulum.model.NetworkStatus.ERROR) status.message else null,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching debug info", e)
                    _debugInfo.value =
                        _debugInfo.value.copy(
                            error = e.message,
                        )
                }
            }
        }

        fun createTestAnnounce() {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Creating test announce...")

                    // Get or create persistent identity and destination
                    val identity = getOrCreateIdentity()
                    Log.d(TAG, "Got identity: ${identity.hash.take(8).joinToString("") { "%02x".format(it) }}")

                    val destination = getOrCreateDestination(identity)
                    Log.d(TAG, "Got destination: ${destination.hexHash}")

                    // Get display name from active identity (or use default)
                    val displayName =
                        try {
                            identityRepository.activeIdentity.first()?.displayName
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get display name from active identity, using default: ${e.message}")
                            null
                        } ?: generateDefaultDisplayName(identity.hash)

                    Log.d(TAG, "Using display name: $displayName")

                    // Announce it with configured display name
                    reticulumProtocol.announceDestination(
                        destination = destination,
                        appData = displayName.toByteArray(),
                    ).getOrThrow()

                    Log.d(TAG, "Test announce sent successfully")
                    _testAnnounceResult.value =
                        TestAnnounceResult(
                            success = true,
                            hexHash = destination.hexHash,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating test announce", e)
                    _testAnnounceResult.value =
                        TestAnnounceResult(
                            success = false,
                            error = e.message,
                        )
                }
            }
        }

        fun clearTestResult() {
            _testAnnounceResult.value = null
        }

        /**
         * Get the LXMF identity for test announces.
         * This ensures announces use the same identity as LXMF messaging.
         */
        private suspend fun getOrCreateIdentity(): com.lxmf.messenger.reticulum.model.Identity {
            // Return cached identity if available
            cachedIdentity?.let {
                Log.d(TAG, "Using cached identity")
                return it
            }

            Log.d(TAG, "Fetching LXMF identity from service...")

            // Get LXMF identity from the service (this is the router's identity)
            try {
                if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                    val lxmfIdentity = reticulumProtocol.getLxmfIdentity().getOrThrow()
                    cachedIdentity = lxmfIdentity
                    Log.d(TAG, "Successfully retrieved LXMF identity from service")
                    return lxmfIdentity
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting LXMF identity: ${e.message}", e)
            }

            // Fallback: create new identity (shouldn't happen with service-based protocol)
            Log.w(TAG, "Creating fallback identity (this shouldn't normally happen)")
            val newIdentity = reticulumProtocol.createIdentity().getOrThrow()
            cachedIdentity = newIdentity
            return newIdentity
        }

        /**
         * Get the LXMF delivery destination for test announces.
         * This reuses the destination already created by the LXMF router.
         */
        private suspend fun getOrCreateDestination(identity: com.lxmf.messenger.reticulum.model.Identity): com.lxmf.messenger.reticulum.model.Destination {
            // Return cached destination if available
            cachedDestination?.let {
                Log.d(TAG, "Using cached destination")
                return it
            }

            Log.d(TAG, "Fetching LXMF destination from service...")

            // Get LXMF destination from service (already registered by router)
            try {
                if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                    val destination = reticulumProtocol.getLxmfDestination().getOrThrow()
                    cachedDestination = destination
                    Log.d(TAG, "Successfully retrieved LXMF destination from service")
                    return destination
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting LXMF destination: ${e.message}", e)
            }

            // Fallback: shouldn't happen with service protocol
            Log.e(TAG, "Failed to get LXMF destination - throwing RuntimeException")
            throw RuntimeException("Could not get LXMF destination")
        }

        /**
         * Load identity data for QR code generation and sharing.
         * Retries with exponential backoff if the service isn't ready yet.
         */
        private fun loadIdentityData() {
            viewModelScope.launch {
                var attempt = 0
                val maxAttempts = 5
                var delay = 200L // Start with 200ms

                while (attempt < maxAttempts) {
                    try {
                        val identity = getOrCreateIdentity()
                        val destination = getOrCreateDestination(identity)

                        // Convert hashes to hex strings
                        val identityHashHex = identity.hash.joinToString("") { "%02x".format(it) }
                        val destinationHashHex = destination.hexHash

                        // Update state
                        _identityHash.value = identityHashHex
                        _destinationHash.value = destinationHashHex
                        _publicKey.value = identity.publicKey

                        // Generate QR code data
                        val qrData =
                            IdentityQrCodeUtils.encodeToQrString(
                                destinationHash = destination.hash,
                                publicKey = identity.publicKey,
                            )
                        _qrCodeData.value = qrData

                        Log.d(TAG, "Identity data loaded successfully (attempt ${attempt + 1})")
                        Log.d(TAG, "Identity hash: $identityHashHex")
                        Log.d(TAG, "Destination hash: $destinationHashHex")
                        return@launch // Success - exit
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= maxAttempts) {
                            Log.e(TAG, "Failed to load identity data after $maxAttempts attempts", e)
                            return@launch
                        }
                        Log.w(TAG, "Error loading identity data (attempt $attempt/$maxAttempts), retrying in ${delay}ms: ${e.message}")
                        kotlinx.coroutines.delay(delay)
                        delay *= 2 // Exponential backoff
                    }
                }
            }
        }

        /**
         * Generate share text for the current identity.
         */
        fun generateShareText(displayName: String): String? {
            val destHash = _publicKey.value ?: return null
            val pubKey = _publicKey.value ?: return null
            val destinationHashBytes =
                _destinationHash.value?.let { hex ->
                    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } ?: return null

            return IdentityQrCodeUtils.generateShareText(
                displayName = displayName,
                destinationHash = destinationHashBytes,
                publicKey = pubKey,
            )
        }

        fun shutdownService() {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "User requested service shutdown")
                    reticulumProtocol.shutdown()
                    Log.i(TAG, "Service shutdown complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Error shutting down service", e)
                }
            }
        }

        fun restartService() {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "User requested service restart")
                    _isRestarting.value = true

                    // Use InterfaceConfigManager which handles the full restart lifecycle:
                    // 1. Shutdown current service
                    // 2. Restart the service process
                    // 3. Re-initialize with config from database
                    interfaceConfigManager.applyInterfaceChanges()
                    Log.i(TAG, "Service restart completed successfully")

                    _isRestarting.value = false
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting service", e)
                    _isRestarting.value = false
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            Log.d(TAG, "DebugViewModel cleared")
        }
    }
