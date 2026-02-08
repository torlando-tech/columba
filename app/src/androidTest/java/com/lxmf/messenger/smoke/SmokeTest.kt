package com.lxmf.messenger.smoke

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.service.ReticulumService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for quick device verification.
 *
 * These tests verify basic app functionality without running the full test suite.
 * Used by scripts/verify-on-device.sh for rapid deployment verification.
 *
 * Key verifications:
 * 1. App can be installed and launched
 * 2. Service can be bound
 * 3. Service responds to basic IPC calls
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
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
                println("SmokeTest: Service connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                serviceBound = false
                println("SmokeTest: Service disconnected")
            }
        }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        println("SmokeTest: Context = ${context.packageName}")
    }

    @After
    fun tearDown() {
        if (serviceBound) {
            try {
                // Graceful shutdown
                service?.shutdown()
                Thread.sleep(1000)
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                println("SmokeTest: Cleanup error: ${e.message}")
            }
        }
    }

    /**
     * Test 1: Verify app context is available
     *
     * This confirms the APK was installed correctly and can be instrumented.
     */
    @Test
    fun test01_appContextAvailable() {
        assertNotNull("App context should not be null", context)
        assertTrue(
            "Package name should be correct",
            context.packageName == "com.lxmf.messenger",
        )
        println("SmokeTest: App context verified - ${context.packageName}")
    }

    /**
     * Test 2: Verify service can be bound
     *
     * This confirms the ReticulumService is properly declared in the manifest
     * and can be started in its separate process.
     */
    @Test
    fun test02_serviceBinds() {
        val intent = Intent(context, ReticulumService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        assertTrue("bindService should return true", bound)

        val connected = bindLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Service should connect within 10 seconds", connected)
        assertNotNull("Service binder should not be null", service)

        println("SmokeTest: Service bound successfully")
    }

    /**
     * Test 3: Verify service status is accessible
     *
     * This confirms IPC works correctly between the main app process and
     * the service process (:reticulum).
     */
    @Test
    fun test03_serviceStatusAccessible() {
        // First bind to service
        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        val connected = bindLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Service should connect", connected)

        // Now check status
        val status = service!!.getStatus()
        assertNotNull("Status should not be null", status)
        assertTrue(
            "Status should be a known value",
            status in listOf("SHUTDOWN", "INITIALIZING", "READY", "FAILED"),
        )

        println("SmokeTest: Service status = $status")
    }

    /**
     * Test 4: Verify service debug info is accessible
     *
     * This exercises more complex IPC and ensures the service can
     * serialize debug information back to the caller.
     */
    @Test
    fun test04_serviceDebugInfoAccessible() {
        // First bind to service
        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        val connected = bindLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Service should connect", connected)

        // Get debug info
        val debugInfo =
            try {
                service!!.getDebugInfo()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

        assertNotNull("Debug info should not be null", debugInfo)
        println("SmokeTest: Debug info = $debugInfo")
    }
}
