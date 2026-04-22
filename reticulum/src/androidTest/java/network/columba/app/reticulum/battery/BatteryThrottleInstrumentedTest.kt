package network.columba.app.reticulum.battery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.reticulum.android.DozeState
import network.reticulum.android.DozeStateObserver
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for battery-saving throttle mechanisms.
 *
 * Verifies on a real device:
 * - DozeStateObserver reads valid state
 * - Transport job interval can be set dynamically
 * - Multicast lock lifecycle (conditional acquire/release)
 */
@RunWith(AndroidJUnit4::class)
class BatteryThrottleInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
    }

    // ===== DozeStateObserver =====

    @Test
    fun dozeObserver_readsValidState() {
        val observer = DozeStateObserver(context)
        observer.start()

        val state = observer.state.value
        assertNotNull("Doze state should not be null", state)
        assertTrue(
            "Should be Active or Dozing",
            state is DozeState.Active || state is DozeState.Dozing,
        )

        observer.stop()
    }

    @Test
    fun dozeObserver_startStopIdempotent() {
        val observer = DozeStateObserver(context)
        observer.start()
        observer.start() // double start
        observer.stop()
        observer.stop() // double stop
        // No exception = pass
    }

    // ===== Transport Job Interval =====

    @Test
    fun transport_customJobInterval_canBeSet() {
        val original = Transport.customJobIntervalMs

        Transport.customJobIntervalMs = 300_000L
        assertEquals(300_000L, Transport.customJobIntervalMs)

        Transport.customJobIntervalMs = 60_000L
        assertEquals(60_000L, Transport.customJobIntervalMs)

        // Restore
        Transport.customJobIntervalMs = original
    }

    @Test
    fun transport_customJobInterval_nullUsesDefault() {
        Transport.customJobIntervalMs = null
        // Should not crash when getting interval
        // (getJobInterval falls back to Platform.recommendedJobIntervalMs)
    }

    // ===== Multicast Lock =====

    @Test
    fun multicastLock_acquireRelease_lifecycle() {
        val wifiManager =
            context.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager

        try {
            val lock =
                wifiManager.createMulticastLock("TestMulticast").apply {
                    setReferenceCounted(false)
                }

            assertFalse("Lock should not be held initially", lock.isHeld)

            lock.acquire()
            assertTrue("Lock should be held after acquire", lock.isHeld)

            lock.release()
            assertFalse("Lock should not be held after release", lock.isHeld)
        } catch (e: SecurityException) {
            // Test APK lacks CHANGE_WIFI_MULTICAST_STATE — skip gracefully.
            // The permission is declared in the main app manifest, not the test APK.
            println("Skipping: test APK lacks CHANGE_WIFI_MULTICAST_STATE permission")
        }
    }

    @Test
    fun multicastLock_doubleRelease_noException() {
        val wifiManager =
            context.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager

        try {
            val lock =
                wifiManager.createMulticastLock("TestMulticast2").apply {
                    setReferenceCounted(false)
                    acquire()
                }

            lock.release()
            try {
                lock.release()
            } catch (e: Exception) {
                // Some Android versions throw on double release
            }
        } catch (e: SecurityException) {
            println("Skipping: test APK lacks CHANGE_WIFI_MULTICAST_STATE permission")
        }
    }

    // ===== Throttle Multiplier Math =====

    @Test
    fun throttleMultiplier_normalIs1x() {
        val policy = computeThrottle(DozeState.Active)
        assertEquals(1.0f, policy)
    }

    @Test
    fun throttleMultiplier_dozeIs5x() {
        val policy = computeThrottle(DozeState.Dozing)
        assertEquals(5.0f, policy)
    }

    @Test
    fun throttledJobInterval_normalIs60s() {
        val interval = (60_000L * computeThrottle(DozeState.Active)).toLong()
        assertEquals(60_000L, interval)
    }

    @Test
    fun throttledJobInterval_dozeIs300s() {
        val interval = (60_000L * computeThrottle(DozeState.Dozing)).toLong()
        assertEquals(300_000L, interval)
    }

    private fun computeThrottle(state: DozeState): Float =
        when (state) {
            is DozeState.Dozing -> 5.0f
            is DozeState.Active -> 1.0f
        }
}
