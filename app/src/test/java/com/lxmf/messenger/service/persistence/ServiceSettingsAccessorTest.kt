package com.lxmf.messenger.service.persistence

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceSettingsAccessor.
 *
 * Tests cross-process SharedPreferences access for settings that need to be
 * read by the service process but written by the app process.
 */
@Suppress("DEPRECATION") // MODE_MULTI_PROCESS is deprecated but necessary for cross-process reads
class ServiceSettingsAccessorTest {
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var settingsAccessor: ServiceSettingsAccessor

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)

        // Mock getSharedPreferences to return our mock
        every {
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )
        } returns sharedPreferences

        settingsAccessor = ServiceSettingsAccessor(context)
    }

    @Test
    fun `getBlockUnknownSenders returns false by default`() {
        every {
            sharedPreferences.getBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, false)
        } returns false

        val result = settingsAccessor.getBlockUnknownSenders()

        assertFalse(result)
    }

    @Test
    fun `getBlockUnknownSenders returns true when enabled`() {
        every {
            sharedPreferences.getBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, false)
        } returns true

        val result = settingsAccessor.getBlockUnknownSenders()

        assertTrue(result)
    }

    @Test
    fun `getBlockUnknownSenders gets fresh SharedPreferences each call`() {
        // First call returns false
        every {
            sharedPreferences.getBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, false)
        } returns false

        val firstResult = settingsAccessor.getBlockUnknownSenders()
        assertFalse(firstResult)

        // Simulate the app process changing the value
        every {
            sharedPreferences.getBoolean(ServiceSettingsAccessor.KEY_BLOCK_UNKNOWN_SENDERS, false)
        } returns true

        val secondResult = settingsAccessor.getBlockUnknownSenders()
        assertTrue(secondResult)

        // Verify getSharedPreferences was called twice (fresh instance each time)
        verify(exactly = 2) {
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )
        }
    }

    @Test
    fun `getBlockUnknownSenders uses MODE_MULTI_PROCESS for cross-process access`() {
        every {
            sharedPreferences.getBoolean(any(), any())
        } returns false

        settingsAccessor.getBlockUnknownSenders()

        // Verify MODE_MULTI_PROCESS is used (critical for cross-process reads)
        verify {
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )
        }
    }
}
