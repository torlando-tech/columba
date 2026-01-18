package com.lxmf.messenger.service.persistence

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
        prefs = context.getSharedPreferences(
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

    // ========== Cross-process key constants Tests ==========

    @Test
    fun `companion object exposes correct SharedPreferences name`() {
        assertEquals("cross_process_settings", ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME)
    }

    @Test
    fun `companion object exposes correct key constants`() {
        assertEquals("block_unknown_senders", ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS)
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
}
