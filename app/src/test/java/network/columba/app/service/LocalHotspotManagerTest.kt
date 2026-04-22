package network.columba.app.service

import android.app.Application
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for LocalHotspotManager.
 *
 * Under Robolectric, WifiManager.startLocalOnlyHotspot is not fully simulated,
 * so these tests focus on the manager's state management and API-level checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalHotspotManagerTest {
    @Test
    fun `isSupported returns true on API 26+`() {
        assertTrue(LocalHotspotManager.isSupported())
    }

    @Test
    fun `MIN_API_LEVEL is 26`() {
        assertEquals(Build.VERSION_CODES.O, LocalHotspotManager.MIN_API_LEVEL)
    }

    @Test
    fun `isActive is false initially`() {
        val manager = LocalHotspotManager(RuntimeEnvironment.getApplication())
        assertFalse(manager.isActive)
    }

    @Test
    fun `stop is safe to call when not active`() {
        val manager = LocalHotspotManager(RuntimeEnvironment.getApplication())
        manager.stop()
        assertFalse(manager.isActive)
    }

    @Test
    fun `HotspotInfo data class holds ssid and password`() {
        val info = LocalHotspotManager.HotspotInfo(
            ssid = "TestNetwork",
            password = "password123",
        )
        assertEquals("TestNetwork", info.ssid)
        assertEquals("password123", info.password)
    }

    @Test
    fun `HotspotException includes reason code`() {
        val ex = LocalHotspotManager.HotspotException("test error", 42)
        assertEquals("test error", ex.message)
        assertEquals(42, ex.reason)
    }
}
