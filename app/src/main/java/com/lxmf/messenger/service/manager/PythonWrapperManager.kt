package com.lxmf.messenger.service.manager

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.lxmf.messenger.crypto.StampGenerator
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import com.lxmf.messenger.reticulum.bridge.KotlinReticulumBridge
import com.lxmf.messenger.service.state.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Manages the Python/Chaquopy Reticulum wrapper lifecycle.
 *
 * Handles:
 * - Wrapper initialization with ANR-safe timeout
 * - Graceful shutdown with generation tracking
 * - Bridge registration (BLE, Reticulum)
 * - Callback registration for events (delivery status, messages)
 * - Safe wrapper access via withWrapper{} pattern
 *
 * IMPORTANT: Python's signal module requires the main thread for handler registration.
 * All initialization calls go through Dispatchers.Main.immediate.
 */
@Suppress("TooManyFunctions") // Manager class wrapping Python API methods
class PythonWrapperManager(
    private val state: ServiceState,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    // Thread-safety: Mutex protects wrapper state transitions (check-then-act patterns)
    private val wrapperLock = Mutex()

    companion object {
        private const val TAG = "PythonWrapperManager"
        private const val INIT_TIMEOUT_MS = 15_000L // 15s ANR protection
        private const val SHUTDOWN_TIMEOUT_MS = 10_000L // 10s shutdown timeout

        /**
         * Helper to safely get values from Python dict.
         */
        fun PyObject.getDictValue(key: String): PyObject? {
            return this.callAttr("get", key)
        }
    }

    /**
     * Initialize the Python Reticulum wrapper.
     *
     * Thread-safe: Uses wrapperLock to prevent concurrent initialization races.
     *
     * @param configJson JSON configuration for Reticulum
     * @param beforeInit Optional callback to run after wrapper is created but before Python initialize()
     *                   Used to set up bridges (BLE, Reticulum) that Python initialization depends on
     * @param onSuccess Suspend callback on successful initialization (allows calling suspend functions)
     *                  Receives isSharedInstance boolean indicating if connected to a shared RNS instance
     * @param onError Called on error with error message
     */
    suspend fun initialize(
        configJson: String,
        beforeInit: ((PyObject) -> Unit)? = null,
        onSuccess: suspend (isSharedInstance: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Acquire lock to prevent concurrent initialization races
        wrapperLock.withLock {
            try {
                Log.d(TAG, "=== Starting Reticulum Initialization ===")

                // Wait for any pending shutdown to complete
                state.shutdownJob?.let {
                    Log.d(TAG, "Waiting for pending shutdown to complete...")
                    it.join()
                    state.shutdownJob = null
                    Log.d(TAG, "Pending shutdown complete")
                }

                // Increment generation to invalidate stale shutdown jobs
                val generation = state.nextGeneration()
                Log.d(TAG, "Starting initialization generation $generation")

                // If wrapper already exists, shut it down first
                // Thread-safe: This check-then-act is protected by wrapperLock
                if (state.wrapper != null) {
                    Log.d(TAG, "Existing wrapper found, shutting down first...")
                    shutdownExistingWrapperLocked()
                }

                // Create wrapper instance
                val py = Python.getInstance()
                val module = py.getModule("reticulum_wrapper")
                val storagePath = context.filesDir.absolutePath + "/reticulum"

                Log.d(TAG, "Creating new ReticulumWrapper instance")
                val wrapper = module.callAttr("ReticulumWrapper", storagePath)
                state.wrapper = wrapper

                // Run beforeInit callback if provided (for setting up bridges before Python initializes)
                beforeInit?.invoke(wrapper)

                // Initialize Python wrapper on main thread (required for signal handlers)
                Log.d(TAG, "Calling Python initialize() on main thread (via Main.immediate)...")
                val startTime = System.currentTimeMillis()

                val result =
                    try {
                        withTimeout(INIT_TIMEOUT_MS) {
                            withContext(Dispatchers.Main.immediate) {
                                wrapper.callAttr("initialize", configJson)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        val duration = System.currentTimeMillis() - startTime
                        Log.e(TAG, "Python initialization timed out after ${duration}ms")
                        state.wrapper = null // Clean up on failure
                        onError("Initialization timeout - potential ANR risk")
                        return@withLock
                    } finally {
                        val duration = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Python initialization took ${duration}ms")
                    }

                val success = result.getDictValue("success")?.toBoolean() ?: false

                if (success) {
                    // Parse is_shared_instance from Python result
                    val isSharedInstance = result.getDictValue("is_shared_instance")?.toBoolean() ?: false
                    Log.d(TAG, "Reticulum initialized successfully (shared instance: $isSharedInstance)")
                    onSuccess(isSharedInstance)
                } else {
                    val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                    Log.e(TAG, "Reticulum initialization failed: $error")
                    state.wrapper = null // Clean up on failure
                    onError(sanitizeErrorMessage(error))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                state.wrapper = null // Clean up on failure
                onError(sanitizeErrorMessage(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Shutdown the Python wrapper asynchronously.
     *
     * Thread-safe: Uses wrapperLock to atomically capture and clear wrapper state.
     * Uses generation tracking to prevent stale shutdowns from corrupting state
     * when rapid restart sequences occur.
     *
     * @param onComplete Called when shutdown completes (on any outcome)
     */
    suspend fun shutdown(onComplete: () -> Unit) {
        // Thread-safe: Atomically capture and clear wrapper reference
        val wrapperToShutdown: PyObject?
        val shutdownGeneration: Int

        wrapperLock.withLock {
            wrapperToShutdown = state.wrapper
            state.wrapper = null
            shutdownGeneration = state.initializationGeneration.get()
        }

        if (wrapperToShutdown == null) {
            Log.d(TAG, "No wrapper to shutdown")
            onComplete()
            return
        }

        Log.d(TAG, "Starting shutdown for generation $shutdownGeneration")

        try {
            // Shutdown outside lock to avoid blocking other operations
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        wrapperToShutdown.callAttr("shutdown")
                    }
                }
                Log.d(TAG, "Python shutdown complete (generation $shutdownGeneration)")
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Python shutdown timed out after ${SHUTDOWN_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error during Python shutdown", e)
            }

            // Only update status if still current generation
            if (state.isCurrentGeneration(shutdownGeneration)) {
                Log.d(TAG, "Shutdown generation $shutdownGeneration matches current")
            } else {
                Log.i(TAG, "Shutdown generation $shutdownGeneration is stale, skipping state update")
            }

            onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
            onComplete()
        }
    }

    /**
     * Check if the wrapper is initialized and ready.
     */
    fun isInitialized(): Boolean = state.isInitialized()

    /**
     * Execute a block with the wrapper if available.
     * Returns null if wrapper is not initialized.
     *
     * @param block Lambda that receives the PyObject wrapper
     * @return Result of block or null if wrapper unavailable
     */
    fun <T> withWrapper(block: (PyObject) -> T): T? {
        val wrapper =
            state.wrapper ?: run {
                Log.w(TAG, "withWrapper called but wrapper is null")
                return null
            }
        return try {
            block(wrapper)
        } catch (e: Exception) {
            Log.e(TAG, "Error in withWrapper block", e)
            null
        }
    }

    /**
     * Setup BLE bridge for AndroidBLEDriver.
     */
    fun setupBleBridge(bleBridge: KotlinBLEBridge) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_ble_bridge", bleBridge)
                Log.d(TAG, "BLE bridge set in Python wrapper")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set BLE bridge: ${e.message}", e)
            }
        }
    }

    /**
     * Setup Reticulum bridge for event-driven announce notifications.
     *
     * @param onAnnounce Callback when announce is received
     */
    fun setupReticulumBridge(onAnnounce: () -> Unit) {
        withWrapper { wrapper ->
            try {
                val reticulumBridge = KotlinReticulumBridge.getInstance()
                wrapper.callAttr("set_reticulum_bridge", reticulumBridge)
                reticulumBridge.setOnAnnounceReceived(onAnnounce)
                Log.d(TAG, "ReticulumBridge configured for event-driven announces")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ReticulumBridge: ${e.message}", e)
            }
        }
    }

    /**
     * Set delivery status callback for message status updates.
     */
    fun setDeliveryStatusCallback(callback: (String) -> Unit) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_delivery_status_callback", callback)
                Log.d(TAG, "Delivery status callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set delivery status callback: ${e.message}", e)
            }
        }
    }

    /**
     * Set message received callback for incoming message notifications.
     */
    fun setMessageReceivedCallback(callback: (String) -> Unit) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_message_received_callback", callback)
                Log.d(TAG, "Message received callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set message received callback: ${e.message}", e)
            }
        }
    }

    /**
     * Set alternative relay request callback.
     * Called by Python when propagation to current relay fails and alternative is needed.
     */
    fun setAlternativeRelayCallback(callback: (String) -> Unit) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_kotlin_request_alternative_relay_callback", callback)
                Log.d(TAG, "Alternative relay callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set alternative relay callback: ${e.message}", e)
            }
        }
    }

    /**
     * Set location telemetry received callback.
     * Called by Python when location sharing data is received from a contact.
     */
    fun setLocationReceivedCallback(callback: (String) -> Unit) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_location_received_callback", callback)
                Log.d(TAG, "📍 Location received callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set location received callback: ${e.message}", e)
            }
        }
    }

    /**
     * Set reaction received callback.
     * Called by Python when an emoji reaction to a message is received.
     */
    fun setReactionReceivedCallback(callback: (String) -> Unit) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("set_reaction_received_callback", callback)
                Log.d(TAG, "😀 Reaction received callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set reaction received callback: ${e.message}", e)
            }
        }
    }

    /**
     * Set native Kotlin stamp generator callback.
     *
     * This bypasses Python multiprocessing-based stamp generation which fails on Android
     * due to lack of sem_open support and aggressive process killing by Android.
     *
     * The callback is invoked by Python's LXStamper when stamp generation is needed.
     *
     * @param stampGenerator The Kotlin StampGenerator instance to use
     */
    // Holder for stamp generator instance - used by static callback method
    private var stampGeneratorInstance: StampGenerator? = null

    fun setStampGeneratorCallback(stampGenerator: StampGenerator) {
        stampGeneratorInstance = stampGenerator
        withWrapper { wrapper ->
            try {
                // Pass static method reference to avoid lambda type erasure issues with R8
                wrapper.callAttr("set_stamp_generator_callback", ::generateStampForPython)
                Log.d(TAG, "Native stamp generator callback registered with Python")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set stamp generator callback: ${e.message}", e)
            }
        }
    }

    /**
     * Static-like method for Python to call for stamp generation.
     * Returns PyObject (Python tuple) to avoid Chaquopy type conversion issues.
     */
    fun generateStampForPython(
        workblock: ByteArray,
        stampCost: Int,
    ): PyObject {
        Log.d(TAG, "Stamp generator callback invoked: cost=$stampCost, workblock=${workblock.size} bytes")

        val generator = checkNotNull(stampGeneratorInstance) { "StampGenerator not initialized" }

        // Python expects synchronous return from this callback
        val result =
            runBlocking(Dispatchers.Default) { // THREADING: allowed
                generator.generateStamp(workblock, stampCost)
            }

        Log.d(TAG, "Stamp generated: value=${result.value}, rounds=${result.rounds}")

        // Create Python list with proper bytes conversion
        val py = Python.getInstance()
        val builtins = py.getBuiltins()
        val stamp = result.stamp ?: ByteArray(0)
        // Convert Java ByteArray to Python bytes for buffer protocol compatibility
        val pyBytes = builtins.callAttr("bytes", stamp)
        val pyList = builtins.callAttr("list")
        pyList.callAttr("append", pyBytes)
        pyList.callAttr("append", result.rounds)
        return pyList
    }

    /**
     * Provide alternative relay to Python for message retry.
     * Called after finding an alternative relay via PropagationNodeManager.
     *
     * @param relayHash 16-byte destination hash, or null if no alternatives available
     */
    fun provideAlternativeRelay(relayHash: ByteArray?) {
        withWrapper { wrapper ->
            try {
                wrapper.callAttr("on_alternative_relay_received", relayHash)
                Log.d(TAG, "Alternative relay provided: ${relayHash?.let { it.toHexString().take(16) } ?: "null"}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to provide alternative relay: ${e.message}", e)
            }
        }
    }

    /**
     * Get debug info from the wrapper.
     */
    fun getDebugInfo(): PyObject? =
        withWrapper { wrapper ->
            wrapper.callAttr("get_debug_info")
        }

    /**
     * Shutdown existing wrapper while holding the wrapperLock.
     * Must only be called from within wrapperLock.withLock { } block.
     */
    private suspend fun shutdownExistingWrapperLocked() {
        try {
            val wrapperToShutdown = state.wrapper
            state.wrapper = null

            if (wrapperToShutdown == null) return

            // Stop jobs are handled by caller
            withTimeout(SHUTDOWN_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    wrapperToShutdown.callAttr("shutdown")
                }
            }
            Log.d(TAG, "Previous wrapper shutdown complete")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Previous wrapper shutdown timed out - forcing continuation")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down existing wrapper: ${e.message}", e)
        }
    }

    /**
     * Sanitize error messages to hide Python internals from user.
     */
    private fun sanitizeErrorMessage(error: String): String {
        return when {
            error.contains("NoneType") -> "Network initialization failed"
            error.contains("AttributeError") -> "Configuration error"
            error.contains("ImportError") || error.contains("ModuleNotFoundError") -> "Missing network components"
            error.contains("PermissionError") || error.contains("Permission denied") -> "Permission denied"
            error.contains("NetworkError") || error.contains("socket") -> "Network connection error"
            error.contains("Bluetooth") -> "Bluetooth error"
            error.contains("timeout") || error.contains("Timeout") -> "Connection timeout"
            error.length > 100 -> "Network initialization error"
            else -> error
        }
    }

    /**
     * Convert ByteArray to hex string for logging.
     */
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
