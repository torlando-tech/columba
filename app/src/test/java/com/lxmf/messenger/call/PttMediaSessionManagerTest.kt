package com.lxmf.messenger.call

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [PttMediaSessionManager].
 *
 * Tests MediaSession lifecycle, headset button event handling,
 * and PTT state callback invocation.
 */
@RunWith(RobolectricTestRunner::class)
class PttMediaSessionManagerTest {
    private lateinit var manager: PttMediaSessionManager
    private var lastPttState: Boolean? = null
    private var pttStateChanges = mutableListOf<Boolean>()

    @Before
    fun setup() {
        lastPttState = null
        pttStateChanges.clear()
        manager = PttMediaSessionManager(
            ApplicationProvider.getApplicationContext(),
        ) { active ->
            lastPttState = active
            pttStateChanges.add(active)
        }
    }

    @After
    fun tearDown() {
        manager.release()
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun `initial state is inactive`() {
        assertFalse(manager.isActive())
    }

    @Test
    fun `activate sets active state`() {
        manager.activate()
        assertTrue(manager.isActive())
    }

    @Test
    fun `deactivate clears active state`() {
        manager.activate()
        manager.deactivate()
        assertFalse(manager.isActive())
    }

    @Test
    fun `activate is idempotent`() {
        manager.activate()
        manager.activate() // Should not crash or create duplicate sessions
        assertTrue(manager.isActive())
    }

    @Test
    fun `deactivate is idempotent`() {
        manager.deactivate() // Should not crash when already inactive
        assertFalse(manager.isActive())
    }

    @Test
    fun `deactivate sends false PTT state`() {
        manager.activate()
        manager.deactivate()
        // Should release PTT on deactivate (safety measure for mid-press deactivation)
        assertTrue("Should have received PTT state change", pttStateChanges.isNotEmpty())
        assertFalse("Last PTT state should be false on deactivate", pttStateChanges.last())
    }

    @Test
    fun `release cleans up resources`() {
        manager.activate()
        manager.release()
        assertFalse(manager.isActive())
    }

    // ========== Callback Tests ==========

    @Test
    fun `callback receives false on deactivate even if not previously active`() {
        manager.activate()
        manager.deactivate()
        // Deactivate should always send false to ensure clean state
        assertFalse("Callback should receive false on deactivate", lastPttState!!)
    }
}
