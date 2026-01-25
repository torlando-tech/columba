package com.lxmf.messenger.ui.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Unit tests for [LifecycleGuard].
 *
 * These tests verify that the lifecycle guard correctly identifies when it's safe
 * to show window-based UI components (dialogs, bottom sheets) to prevent
 * BadTokenException crashes.
 *
 * Window-based UI requires a valid window token, which is only available when
 * the activity is at least in the STARTED state.
 */
class LifecycleGuardTest {

    // ========== State-based Tests ==========

    @Test
    fun `isStateActiveForWindows returns false for DESTROYED state`() {
        assertFalse(LifecycleGuard.isStateActiveForWindows(Lifecycle.State.DESTROYED))
    }

    @Test
    fun `isStateActiveForWindows returns false for INITIALIZED state`() {
        assertFalse(LifecycleGuard.isStateActiveForWindows(Lifecycle.State.INITIALIZED))
    }

    @Test
    fun `isStateActiveForWindows returns false for CREATED state`() {
        // CREATED means onCreate() called but not yet onStart()
        // Window token is not yet valid at this point
        assertFalse(LifecycleGuard.isStateActiveForWindows(Lifecycle.State.CREATED))
    }

    @Test
    fun `isStateActiveForWindows returns true for STARTED state`() {
        // STARTED means onStart() called - window token is valid
        assertTrue(LifecycleGuard.isStateActiveForWindows(Lifecycle.State.STARTED))
    }

    @Test
    fun `isStateActiveForWindows returns true for RESUMED state`() {
        // RESUMED is the fully active state - definitely safe to show dialogs
        assertTrue(LifecycleGuard.isStateActiveForWindows(Lifecycle.State.RESUMED))
    }

    // ========== Lifecycle-based Tests ==========

    @Test
    fun `isActiveForWindows with Lifecycle returns false when CREATED`() {
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.CREATED)

        assertFalse(LifecycleGuard.isActiveForWindows(lifecycle))
    }

    @Test
    fun `isActiveForWindows with Lifecycle returns true when STARTED`() {
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        assertTrue(LifecycleGuard.isActiveForWindows(lifecycle))
    }

    @Test
    fun `isActiveForWindows with Lifecycle returns true when RESUMED`() {
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)

        assertTrue(LifecycleGuard.isActiveForWindows(lifecycle))
    }

    // ========== LifecycleOwner-based Tests ==========

    @Test
    fun `isActiveForWindows with LifecycleOwner returns false when DESTROYED`() {
        val lifecycleOwner = mock(LifecycleOwner::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycleOwner.lifecycle).thenReturn(lifecycle)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.DESTROYED)

        assertFalse(LifecycleGuard.isActiveForWindows(lifecycleOwner))
    }

    @Test
    fun `isActiveForWindows with LifecycleOwner returns false when INITIALIZED`() {
        val lifecycleOwner = mock(LifecycleOwner::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycleOwner.lifecycle).thenReturn(lifecycle)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.INITIALIZED)

        assertFalse(LifecycleGuard.isActiveForWindows(lifecycleOwner))
    }

    @Test
    fun `isActiveForWindows with LifecycleOwner returns true when STARTED`() {
        val lifecycleOwner = mock(LifecycleOwner::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycleOwner.lifecycle).thenReturn(lifecycle)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        assertTrue(LifecycleGuard.isActiveForWindows(lifecycleOwner))
    }

    @Test
    fun `isActiveForWindows with LifecycleOwner returns true when RESUMED`() {
        val lifecycleOwner = mock(LifecycleOwner::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        `when`(lifecycleOwner.lifecycle).thenReturn(lifecycle)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)

        assertTrue(LifecycleGuard.isActiveForWindows(lifecycleOwner))
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `all lifecycle states below STARTED are inactive for windows`() {
        val inactiveStates = listOf(
            Lifecycle.State.DESTROYED,
            Lifecycle.State.INITIALIZED,
            Lifecycle.State.CREATED,
        )

        inactiveStates.forEach { state ->
            assertFalse(
                "State $state should be inactive for windows",
                LifecycleGuard.isStateActiveForWindows(state),
            )
        }
    }

    @Test
    fun `all lifecycle states at or above STARTED are active for windows`() {
        val activeStates = listOf(
            Lifecycle.State.STARTED,
            Lifecycle.State.RESUMED,
        )

        activeStates.forEach { state ->
            assertTrue(
                "State $state should be active for windows",
                LifecycleGuard.isStateActiveForWindows(state),
            )
        }
    }
}
