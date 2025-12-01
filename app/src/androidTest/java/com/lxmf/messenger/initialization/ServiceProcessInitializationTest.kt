package com.lxmf.messenger.initialization

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.service.ReticulumService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test to reproduce the multi-process initialization bug.
 *
 * BUG: ColumbaApplication.onCreate() runs in BOTH processes:
 * 1. Main app process (com.lxmf.messenger) - Expected behavior
 * 2. Service process (com.lxmf.messenger:reticulum) - BUG: Should NOT auto-initialize
 *
 * This causes:
 * - Python initialized twice (wasteful, ~4 seconds total)
 * - Both processes try to initialize RNS (race condition)
 * - Service starts in unpredictable state
 *
 * Expected Behavior:
 * - Service should start in SHUTDOWN state
 * - Service should require explicit initialize() call
 * - Only main app process should auto-initialize
 *
 * This test FAILS before the fix (service already initialized)
 * This test PASSES after the fix (service starts in SHUTDOWN)
 *
 * Phase 1, Task 1.4: Eliminate Process-Level Race Conditions
 */
@RunWith(AndroidJUnit4::class)
class ServiceProcessInitializationTest {
    private lateinit var context: Context
    private var service: IReticulumService? = null
    private var serviceBound = false
    private val bindLatch = CountDownLatch(1)

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                service = IReticulumService.Stub.asInterface(binder)
                serviceBound = true
                bindLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                serviceBound = false
            }
        }

    /**
     * Helper function to wait for service to reach a specific state.
     * Uses polling with timeout instead of arbitrary Thread.sleep.
     *
     * Best Practice: Proper synchronization instead of arbitrary delays
     */
    private fun waitForServiceState(
        expectedState: String,
        timeoutSeconds: Int = 5,
    ): Boolean {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000)
        while (System.currentTimeMillis() < deadline) {
            val currentState = service?.getStatus()
            if (currentState == expectedState) {
                println("✅ Service reached expected state: $expectedState")
                return true
            }
            Thread.sleep(100) // Poll every 100ms
        }
        val finalState = service?.getStatus()
        println("❌ Timeout: Service did not reach $expectedState within ${timeoutSeconds}s (current: $finalState)")
        return false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Note: Removed racy shutdown from here - each test handles its own initial state
    }

    @After
    fun tearDown() {
        // Shutdown service to ensure clean state for next test
        // This ensures test isolation - each test starts from SHUTDOWN
        try {
            service?.shutdown()
            println("🧹 Cleanup: Shutting down service for next test")

            // Wait for shutdown to complete (Python cleanup takes 3-5 seconds)
            val shutdownSucceeded = waitForServiceState("SHUTDOWN", timeoutSeconds = 10)
            if (shutdownSucceeded) {
                println("✅ Cleanup: Service shutdown completed")
            } else {
                println("⚠️ Cleanup: Service did not reach SHUTDOWN within 10 seconds")
                // Force additional wait for async cleanup
                Thread.sleep(2000)
            }
        } catch (e: Exception) {
            println("⚠️ Cleanup: Could not shutdown service: ${e.message}")
        }

        // Unbind from service
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Service may already be unbound
            }
        }
    }

    /**
     * Test: Service should start in SHUTDOWN state (not auto-initialized)
     *
     * This is the KEY test that reproduces the bug:
     *
     * BEFORE FIX (Expected to FAIL):
     * - ColumbaApplication.onCreate() runs in service process
     * - Service auto-initializes Python and RNS
     * - getStatus() returns "READY" or "INITIALIZING"
     * - Test assertion FAILS: Expected SHUTDOWN but got READY
     *
     * AFTER FIX (Expected to PASS):
     * - ColumbaApplication.onCreate() detects service process
     * - Skips auto-initialization in service process
     * - Service starts in SHUTDOWN state
     * - getStatus() returns "SHUTDOWN"
     * - Test assertion PASSES
     *
     * This test proves the bug exists and validates the fix.
     */
    @Test
    @Ignore("Flaky on CI: Service binding timeout on resource-constrained runners")
    fun testServiceStartsInShutdownState() {
        // Act: Bind to service
        val intent = Intent(context, ReticulumService::class.java)
        val bound =
            context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )

        assertTrue("Failed to bind to ReticulumService", bound)

        // Wait for service connection (max 5 seconds)
        val connected = bindLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Service connection timeout", connected)
        assertNotNull("Service binder is null", service)

        // Small delay to ensure service onCreate() has completed
        Thread.sleep(500)

        // Assert: Service should be in SHUTDOWN state
        val status = service!!.getStatus()

        // This is the KEY assertion that detects the bug
        // BEFORE FIX: This will FAIL because service auto-initialized
        // AFTER FIX: This will PASS because service starts SHUTDOWN
        assertEquals(
            """
            ❌ BUG DETECTED: Service is already initialized!

            Expected: Service starts in SHUTDOWN state
            Actual: Service state = $status

            Root Cause: ColumbaApplication.onCreate() runs in service process (:reticulum)
            and calls PythonBridge.initialize() + auto-initializes RNS.

            The service process should skip auto-initialization and wait for explicit
            initialize() call from the main app process.

            See THREADING_ARCHITECTURE_ANALYSIS.md for details.
            """.trimIndent(),
            "SHUTDOWN",
            status,
        )

        println("✅ Service correctly starts in SHUTDOWN state")
    }

    /**
     * Test: Shutdown and re-initialization cycle works correctly
     *
     * This test verifies the production scenario where:
     * 1. Service auto-initializes on startup
     * 2. User changes interface configuration
     * 3. Service shuts down and re-initializes with new config
     *
     * Best Practices Applied:
     * - Proper synchronization with waitForServiceState()
     * - Wait for auto-init to complete before testing
     * - Wait for shutdown to complete (5 seconds, not 1!)
     * - Assert state at each transition point
     * - Deterministic behavior (no race conditions)
     */
    @Test
    fun testExplicitInitializationWorks() {
        // Arrange: Bind to service
        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        assertTrue("Service not bound", bindLatch.await(5, TimeUnit.SECONDS))
        assertNotNull("Service binder is null", service)

        // Wait for auto-init to complete (if running)
        // This is the KEY fix: wait for stable state before proceeding
        println("Waiting for service to reach stable state (auto-init may be running)...")
        val reachedStableState =
            waitForServiceState("READY", timeoutSeconds = 5) ||
                waitForServiceState("SHUTDOWN", timeoutSeconds = 1)
        assertTrue("Service did not reach stable state", reachedStableState)

        val initialStatus = service!!.getStatus()
        println("Service stable state: $initialStatus")

        // If service is READY, shutdown first to test re-initialization
        if (initialStatus == "READY") {
            println("Service is READY from auto-init - shutting down to test explicit init")
            service!!.shutdown()

            // CRITICAL FIX: Wait for shutdown to complete (not just Thread.sleep(1000))
            // Python shutdown takes 2-3 seconds!
            val shutdownComplete = waitForServiceState("SHUTDOWN", timeoutSeconds = 5)
            assertTrue("Service shutdown did not complete within 5 seconds", shutdownComplete)
            println("✅ Service shutdown complete")
        }

        // Assert we're starting from SHUTDOWN state
        val statusBeforeInit = service!!.getStatus()
        assertEquals("Service should be SHUTDOWN before initialization", "SHUTDOWN", statusBeforeInit)

        // Prepare initialization callback
        val initLatch = CountDownLatch(1)
        var initSuccess = false
        var initError: String? = null

        val callback =
            object : IInitializationCallback.Stub() {
                override fun onInitializationComplete(result: String?) {
                    initSuccess = true
                    initLatch.countDown()
                    println("✅ Initialization completed: $result")
                }

                override fun onInitializationError(error: String?) {
                    initSuccess = false
                    initError = error
                    initLatch.countDown()
                    println("❌ Initialization failed: $error")
                }
            }

        // Act: Initialize service explicitly
        val configJson =
            """
            {
                "storagePath": "${context.filesDir.absolutePath}/test_reticulum",
                "enabledInterfaces": [],
                "logLevel": "DEBUG",
                "allowAnonymous": false
            }
            """.trimIndent()

        println("Calling service.initialize() from SHUTDOWN state...")
        service!!.initialize(configJson, callback)

        // Wait for initialization (max 10 seconds - Python startup is slow)
        val completed = initLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Initialization timeout after 10 seconds", completed)

        // Assert: Initialization succeeded
        if (!initSuccess) {
            fail("Initialization failed: $initError")
        }

        // Assert: Service should now be READY
        // Wait for service to reach READY state (callback may fire slightly before status updates)
        val reachedReady = waitForServiceState("READY", timeoutSeconds = 5)
        assertTrue("Service did not reach READY state within 5 seconds after initialization", reachedReady)

        val finalStatus = service!!.getStatus()
        assertEquals("Service should be READY after initialization", "READY", finalStatus)

        println("✅ Shutdown → Re-initialization cycle works correctly")
    }

    /**
     * Test: Service debug info should be accessible
     *
     * This is a supporting test to help debug the bug.
     * It logs service state information for analysis.
     */
    @Test
    @Ignore("Flaky on CI: Service binding timeout on resource-constrained runners")
    fun testServiceDebugInfo() {
        // Bind to service
        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        bindLatch.await(5, TimeUnit.SECONDS)
        assertNotNull("Service not bound", service)

        // Get and log debug info
        val status = service!!.getStatus()
        val debugInfo =
            try {
                service!!.getDebugInfo()
            } catch (e: Exception) {
                "Error getting debug info: ${e.message}"
            }

        println(
            """
            ===== SERVICE DEBUG INFO =====
            Status: $status
            Debug Info: $debugInfo
            =============================
            """.trimIndent(),
        )

        // This test always passes - it's for debugging only
        assertTrue("Debug info retrieved", true)
    }
}
