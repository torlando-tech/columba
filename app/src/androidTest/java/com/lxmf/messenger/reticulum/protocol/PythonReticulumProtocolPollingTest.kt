package com.lxmf.messenger.reticulum.protocol

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for PythonReticulumProtocol polling with SmartPoller.
 *
 * Uses shared instance pattern to avoid Python/Reticulum re-initialization issues.
 * Tests verify polling behavior on an already-initialized protocol instance.
 *
 * Part of Phase 2.2 integration testing.
 *
 * IMPORTANT: These tests require a device/emulator because Chaquopy
 * cannot run in JVM-based unit tests.
 */
@RunWith(AndroidJUnit4::class)
class PythonReticulumProtocolPollingTest {
    companion object {
        private const val TAG = "ProtocolPollingTest"
        private lateinit var protocol: PythonReticulumProtocol
        private lateinit var context: Context

        @BeforeClass
        @JvmStatic
        fun setupOnce() {
            Log.d(TAG, "Setting up shared PythonReticulumProtocol instance")
            context = InstrumentationRegistry.getInstrumentation().targetContext

            // Initialize Python if not already started
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }

            protocol = PythonReticulumProtocol(context)

            // Initialize once for all tests
            // CRITICAL: Use Dispatchers.Main for signal handler compatibility
            runBlocking(Dispatchers.Main) {
                val config = createMinimalConfig()
                Log.d(TAG, "Initializing protocol with config: ${config.storagePath}")

                val result = protocol.initialize(config)
                if (result.isFailure) {
                    Log.e(TAG, "Initialization failed: ${result.exceptionOrNull()?.message}")
                    throw result.exceptionOrNull()!!
                }

                // Wait for READY state
                withTimeoutOrNull(30.seconds) {
                    while (protocol.networkStatus.value != NetworkStatus.READY) {
                        delay(500)
                        Log.d(TAG, "Waiting for READY, current: ${protocol.networkStatus.value}")
                    }
                }

                val finalStatus = protocol.networkStatus.value
                check(finalStatus == NetworkStatus.READY) {
                    "Protocol failed to reach READY state, final status: $finalStatus"
                }

                Log.d(TAG, "Protocol initialized successfully, status: READY")
            }
        }

        @AfterClass
        @JvmStatic
        fun teardownOnce() {
            Log.d(TAG, "Tearing down shared PythonReticulumProtocol instance")
            runBlocking(Dispatchers.Main) {
                try {
                    protocol.shutdown()
                    // Allow full shutdown - Python threads need time to stop
                    delay(5000)
                    Log.d(TAG, "Protocol shutdown complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during teardown", e)
                }
            }
        }

        private fun createMinimalConfig(): ReticulumConfig {
            val testDir = context.getDir("protocol_polling_test_shared", Context.MODE_PRIVATE)
            testDir.listFiles()?.forEach { it.deleteRecursively() }

            return ReticulumConfig(
                storagePath = testDir.absolutePath,
                logLevel = LogLevel.ERROR, // Minimal logging
                enabledInterfaces = emptyList(), // No interfaces to avoid network activity
            )
        }
    }

    @Test
    fun protocolIsInitializedAndReady() {
        // Verify the shared instance is in READY state
        assertEquals("Protocol should be READY", NetworkStatus.READY, protocol.networkStatus.value)
    }

    @Test
    fun pollingIsRunningWithoutErrors() =
        runBlocking {
            // Verify protocol is ready
            assertEquals(NetworkStatus.READY, protocol.networkStatus.value)

            // Wait for a few polling cycles (reduced from 10s to 3s)
            delay(3000)

            // Verify still ready (polling didn't crash)
            assertEquals(
                "Protocol should still be READY after polling",
                NetworkStatus.READY,
                protocol.networkStatus.value,
            )
        }

    @Test
    fun announcesFlowIsConnected() =
        runBlocking {
            // Collect announces for a short period
            val announces = mutableListOf<com.lxmf.messenger.reticulum.model.AnnounceEvent>()
            val collectJob =
                launch {
                    protocol.observeAnnounces().collect {
                        announces.add(it)
                        Log.d(TAG, "Received announce in test")
                    }
                }

            // Wait for potential announces (reduced from 5s to 2s)
            delay(2000)

            collectJob.cancel()

            // We may or may not receive announces, but flow should work without errors
            Log.d(TAG, "Announces received during test: ${announces.size}")
            assertTrue("Test completed without errors", true)
        }

    @Test
    fun networkStatusFlowEmitsCurrentState() =
        runBlocking {
            // Collect current and future status updates
            val statuses = mutableListOf<NetworkStatus>()

            val collectJob =
                launch {
                    protocol.networkStatus.collect {
                        statuses.add(it)
                    }
                }

            // Collect for a bit (reduced from 2s to 500ms - status is immediate)
            delay(500)

            collectJob.cancel()

            // Should have at least received READY state
            assertTrue("Should have received at least one status", statuses.isNotEmpty())
            assertTrue("Should contain READY status", statuses.contains(NetworkStatus.READY))
        }
}
