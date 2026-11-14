package network.columba.app.rns.host.persistence

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import network.columba.app.rns.api.notify.MessageNotificationBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ServiceSettingsAccessor.
 *
 * Tests SharedPreferences-based cross-process settings that are written by the
 * service process and read by the main app process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceSettingsAccessorTest {
    private lateinit var context: Context
    private lateinit var accessor: ServiceSettingsAccessor
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        accessor = ServiceSettingsAccessor(context)

        // Get direct access to SharedPreferences for verification
        @Suppress("DEPRECATION")
        prefs =
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )

        // Clear prefs before each test
        prefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().apply()
    }

    // ========== saveNetworkChangeAnnounceTime() Tests ==========

    @Test
    fun `saveNetworkChangeAnnounceTime writes timestamp to SharedPreferences`() {
        val timestamp = 1234567890L

        accessor.saveNetworkChangeAnnounceTime(timestamp)

        val savedValue = prefs.getLong(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME, -1L)
        assertEquals(timestamp, savedValue)
    }

    @Test
    fun `saveNetworkChangeAnnounceTime overwrites previous value`() {
        val timestamp1 = 1000L
        val timestamp2 = 2000L

        accessor.saveNetworkChangeAnnounceTime(timestamp1)
        accessor.saveNetworkChangeAnnounceTime(timestamp2)

        val savedValue = prefs.getLong(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME, -1L)
        assertEquals(timestamp2, savedValue)
    }

    // ========== saveLastAutoAnnounceTime() Tests ==========

    @Test
    fun `saveLastAutoAnnounceTime writes timestamp to SharedPreferences`() {
        val timestamp = 9876543210L

        accessor.saveLastAutoAnnounceTime(timestamp)

        val savedValue = prefs.getLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, -1L)
        assertEquals(timestamp, savedValue)
    }

    @Test
    fun `saveLastAutoAnnounceTime overwrites previous value`() {
        val timestamp1 = 3000L
        val timestamp2 = 4000L

        accessor.saveLastAutoAnnounceTime(timestamp1)
        accessor.saveLastAutoAnnounceTime(timestamp2)

        val savedValue = prefs.getLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, -1L)
        assertEquals(timestamp2, savedValue)
    }

    // ========== getBlockUnknownSenders() Tests ==========

    @Test
    fun `getBlockUnknownSenders returns false by default`() {
        val result = accessor.getBlockUnknownSenders()

        assertFalse(result)
    }

    @Test
    fun `getBlockUnknownSenders returns true when set`() {
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, true).apply()

        val result = accessor.getBlockUnknownSenders()

        assertTrue(result)
    }

    @Test
    fun `getBlockUnknownSenders returns false when explicitly set to false`() {
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, false).apply()

        val result = accessor.getBlockUnknownSenders()

        assertFalse(result)
    }

    @Test
    fun `getBlockUnknownSenders reflects changes between calls`() {
        // First call returns false (default)
        assertFalse(accessor.getBlockUnknownSenders())

        // Simulate the app process changing the value
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, true).apply()

        // Second call should see the updated value
        assertTrue(accessor.getBlockUnknownSenders())
    }

    // ========== getAllowCallsFromContactsOnly() Tests ==========

    @Test
    fun `getAllowCallsFromContactsOnly returns false by default`() {
        assertFalse(accessor.getAllowCallsFromContactsOnly())
    }

    @Test
    fun `getAllowCallsFromContactsOnly returns true when set`() {
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY, true).apply()

        assertTrue(accessor.getAllowCallsFromContactsOnly())
    }

    @Test
    fun `getAllowCallsFromContactsOnly reflects changes between calls`() {
        assertFalse(accessor.getAllowCallsFromContactsOnly())

        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY, true).apply()

        assertTrue(accessor.getAllowCallsFromContactsOnly())
    }

    // ========== getAllowVoiceCalls() Tests ==========

    @Test
    fun `getAllowVoiceCalls returns true by default`() {
        // Default is true so an upgrading user still receives calls without
        // touching the toggle.
        assertTrue(accessor.getAllowVoiceCalls())
    }

    @Test
    fun `getAllowVoiceCalls returns false when explicitly set to false`() {
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_ALLOW_VOICE_CALLS, false).apply()

        assertFalse(accessor.getAllowVoiceCalls())
    }

    @Test
    fun `getAllowVoiceCalls reflects changes between calls`() {
        // First call returns the true default
        assertTrue(accessor.getAllowVoiceCalls())

        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_ALLOW_VOICE_CALLS, false).apply()

        assertFalse(accessor.getAllowVoiceCalls())
    }

    // ========== Cross-process key constants Tests ==========

    @Test
    fun `companion object exposes correct SharedPreferences name`() {
        assertEquals("cross_process_settings", ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME)
    }

    @Test
    fun `companion object exposes correct key constants`() {
        assertEquals("block_unknown_senders", ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS)
        assertEquals("allow_calls_from_contacts_only", ServiceSettingsAccessor.KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY)
        assertEquals("allow_voice_calls", ServiceSettingsAccessor.KEY_ALLOW_VOICE_CALLS)
        assertEquals("network_change_announce_time", ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME)
        assertEquals("last_auto_announce_time", ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME)
    }

    // ========== Integration Tests ==========

    @Test
    fun `multiple settings can be saved independently`() {
        val networkTime = 111L
        val announceTime = 222L

        accessor.saveNetworkChangeAnnounceTime(networkTime)
        accessor.saveLastAutoAnnounceTime(announceTime)
        prefs.edit().putBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, true).apply()

        assertEquals(networkTime, prefs.getLong(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME, -1L))
        assertEquals(announceTime, prefs.getLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, -1L))
        assertTrue(accessor.getBlockUnknownSenders())
    }

    // ========== Message notification mirror + foreground heartbeat ==========

    @Test
    fun `notification mirror is absent until the app process writes it`() {
        assertNull(
            "an unmapped mirror must not fall back to permissive defaults: the " +
                "upgrade-then-reboot case would leak previews for a NONE-privacy user",
            accessor.getNotificationMirror(),
        )
    }

    @Test
    fun `notification mirror reflects keys written by the app process`() {
        prefs
            .edit()
            .putBoolean(MessageNotificationBridge.KEY_MIRROR_WRITTEN, true)
            .putBoolean(MessageNotificationBridge.KEY_NOTIFICATIONS_ENABLED, false)
            .putBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE, true)
            .putBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE_FAVORITE, false)
            .putBoolean(MessageNotificationBridge.KEY_REVEAL_PREVIEW, false)
            .putBoolean(MessageNotificationBridge.KEY_REVEAL_SENDER, false)
            .apply()

        val mirror = requireNotNull(accessor.getNotificationMirror())

        assertFalse(mirror.enabled)
        assertTrue(mirror.receivedMessage)
        assertFalse(mirror.favoriteAllowed)
        assertFalse(mirror.revealPreview)
        assertFalse(mirror.revealSender)
    }

    @Test
    fun `foreground heartbeat is stale when absent zero or older than the window`() {
        assertFalse("no heartbeat ever written", accessor.isUiForeground(now = 10_000L))

        prefs.edit().putLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, 0L).apply()
        assertFalse("explicitly backgrounded", accessor.isUiForeground(now = 10_000L))

        val now = 1_000_000L
        prefs.edit().putLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, now - 1_000L).apply()
        assertTrue("fresh tick", accessor.isUiForeground(now = now))

        prefs
            .edit()
            .putLong(
                MessageNotificationBridge.KEY_UI_FOREGROUND_AT,
                now - MessageNotificationBridge.UI_FOREGROUND_STALE_AFTER_MS - 1,
            ).apply()
        assertFalse("frozen/cached app", accessor.isUiForeground(now = now))

        // A value from before a reboot (clock went backwards) fails closed.
        prefs.edit().putLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, now + 60_000L).apply()
        assertFalse("future timestamp", accessor.isUiForeground(now = now))
    }
}
