package com.lxmf.messenger.test

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.service.ReticulumService
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JUnit Rule for managing ReticulumService lifecycle in instrumented tests.
 *
 * This rule provides:
 * - Automatic service binding/unbinding
 * - Proper cleanup after each test
 * - State verification utilities
 * - Test isolation guarantees
 *
 * Usage:
 * ```kotlin
 * @get:Rule
 * val serviceRule = ServiceLifecycleRule()
 *
 * @Test
 * fun myTest() {
 *     val service = serviceRule.getService()
 *     val status = service.getStatus()
 *     // ... test code
 * }
 * ```
 */
class ServiceLifecycleRule : TestWatcher() {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
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
                println("✅ ServiceLifecycleRule: Service connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                serviceBound = false
                println("⚠️ ServiceLifecycleRule: Service disconnected")
            }
        }

    /**
     * Called before each test method.
     * Binds to the ReticulumService.
     */
    override fun starting(description: Description) {
        super.starting(description)
        println("🔧 ServiceLifecycleRule: Starting - ${description.methodName}")

        // Bind to service
        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Wait for service to bind (max 5 seconds)
        val bound = bindLatch.await(5, TimeUnit.SECONDS)
        if (!bound) {
            throw IllegalStateException("Failed to bind to ReticulumService within 5 seconds")
        }

        println("✅ ServiceLifecycleRule: Service bound successfully")
    }

    /**
     * Called after each test method.
     * Ensures service is shut down and unbound properly.
     */
    override fun finished(description: Description) {
        println("🧹 ServiceLifecycleRule: Cleaning up - ${description.methodName}")

        try {
            // Attempt graceful shutdown
            service?.let {
                it.shutdown()
                println("🧹 ServiceLifecycleRule: Shutdown initiated")

                // Wait for shutdown to complete (max 10 seconds)
                val shutdownSucceeded = waitForServiceState("SHUTDOWN", timeoutSeconds = 10)
                if (shutdownSucceeded) {
                    println("✅ ServiceLifecycleRule: Service shutdown completed")
                } else {
                    println("⚠️ ServiceLifecycleRule: Service did not reach SHUTDOWN within 10 seconds")
                    // Allow extra time for async cleanup
                    Thread.sleep(2000)
                }
            }
        } catch (e: Exception) {
            println("⚠️ ServiceLifecycleRule: Error during shutdown: ${e.message}")
        }

        // Unbind from service
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
                serviceBound = false
                println("✅ ServiceLifecycleRule: Service unbound")
            } catch (e: Exception) {
                println("⚠️ ServiceLifecycleRule: Error unbinding service: ${e.message}")
            }
        }

        super.finished(description)
    }

    /**
     * Get the bound service instance.
     * @throws IllegalStateException if service is not bound
     */
    fun getService(): IReticulumService {
        return service ?: throw IllegalStateException("Service not bound. Did you forget @get:Rule?")
    }

    /**
     * Check if service is currently bound.
     */
    fun isServiceBound(): Boolean = serviceBound

    /**
     * Wait for service to reach a specific state.
     *
     * @param expectedState The state to wait for (e.g., "READY", "SHUTDOWN")
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return true if the state was reached, false if timeout occurred
     */
    fun waitForServiceState(
        expectedState: String,
        timeoutSeconds: Int,
    ): Boolean {
        val service = this.service ?: return false
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000)

        while (System.currentTimeMillis() < endTime) {
            try {
                val currentState = service.getStatus()
                if (currentState == expectedState) {
                    return true
                }
                Thread.sleep(200) // Poll every 200ms
            } catch (e: Exception) {
                println("⚠️ Error checking service state: ${e.message}")
                return false
            }
        }

        return false
    }

    /**
     * Get current service status.
     * @return Current status string or null if service not bound
     */
    fun getStatus(): String? {
        return try {
            service?.getStatus()
        } catch (e: Exception) {
            println("⚠️ Error getting service status: ${e.message}")
            null
        }
    }
}
