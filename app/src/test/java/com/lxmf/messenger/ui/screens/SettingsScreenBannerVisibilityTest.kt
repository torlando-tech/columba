package com.lxmf.messenger.ui.screens

import com.lxmf.messenger.ui.theme.PresetTheme
import com.lxmf.messenger.viewmodel.SettingsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SharedInstanceBanner visibility and toggle logic.
 *
 * Banner Visibility (matches SettingsScreen.kt):
 * The banner should be shown when any of these conditions are true:
 * - isSharedInstance: Currently using a shared instance
 * - sharedInstanceOnline: A shared instance is available (from service query)
 * - wasUsingSharedInstance: Was using shared instance but it went offline (informational)
 * - isRestarting: Service is restarting
 *
 * Toggle Enable Logic (matches SharedInstanceBannerCard.kt):
 * - Can always switch TO own instance (preferOwnInstance = true)
 * - Can only switch TO shared instance if it's online or already using it
 */
class SettingsScreenBannerVisibilityTest {
    /**
     * Replicates the banner visibility logic from SettingsScreen.kt
     */
    private fun shouldShowSharedInstanceBanner(state: SettingsState): Boolean {
        return state.isSharedInstance ||
            state.sharedInstanceOnline ||
            state.wasUsingSharedInstance ||
            state.isRestarting
    }

    /**
     * Replicates the toggle enable logic from SharedInstanceBannerCard.kt
     * - When using shared (toggle OFF): can always switch to own (toggle ON)
     * - When using own (toggle ON): can only switch to shared if it's available
     */
    private fun isToggleEnabled(state: SettingsState): Boolean {
        return state.isSharedInstance || state.sharedInstanceOnline
    }

    /**
     * Replicates the toggle checked logic from SharedInstanceBannerCard.kt
     * Shows actual state (!isSharedInstance), not preference
     */
    private fun computeToggleChecked(state: SettingsState): Boolean {
        return !state.isSharedInstance // Toggle ON when using own instance
    }

    private fun createDefaultState() =
        SettingsState(
            displayName = "Test",
            selectedTheme = PresetTheme.VIBRANT,
            isLoading = false,
        )

    // ==========================================
    // Banner Visibility Tests
    // ==========================================

    @Test
    fun `banner shown when using shared instance`() {
        val state = createDefaultState().copy(isSharedInstance = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when shared instance online`() {
        val state = createDefaultState().copy(sharedInstanceOnline = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when wasUsingSharedInstance is true`() {
        // Informational state - was using shared instance but it went offline
        val state = createDefaultState().copy(wasUsingSharedInstance = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when restarting`() {
        val state = createDefaultState().copy(isRestarting = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner hidden when no shared instance conditions apply`() {
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                sharedInstanceOnline = false,
                wasUsingSharedInstance = false,
                isRestarting = false,
            )
        assertFalse(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner hidden when only preferOwnInstance is true`() {
        // preferOwnInstance alone should NOT cause banner to show
        // This prevents the banner from always appearing after user toggles to own instance
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                sharedInstanceOnline = false,
                preferOwnInstance = true,
            )
        assertFalse(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner visible with multiple conditions true`() {
        val state =
            createDefaultState().copy(
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    // ==========================================
    // Toggle Enable Tests
    // ==========================================

    @Test
    fun `toggle enabled when using shared instance`() {
        // Can always switch to own
        val state =
            createDefaultState().copy(
                isSharedInstance = true,
                preferOwnInstance = false,
            )
        assertTrue(isToggleEnabled(state))
    }

    @Test
    fun `toggle enabled when preferOwnInstance and shared online`() {
        // Can switch back to shared because it's available
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                preferOwnInstance = true,
                sharedInstanceOnline = true,
            )
        assertTrue(isToggleEnabled(state))
    }

    @Test
    fun `toggle disabled when preferOwnInstance and shared offline`() {
        // Can't switch back to shared because it's not available
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                preferOwnInstance = true,
                sharedInstanceOnline = false,
            )
        assertFalse(isToggleEnabled(state))
    }

    @Test
    fun `toggle enabled when not preferring own and shared available`() {
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                preferOwnInstance = false,
                sharedInstanceOnline = true,
            )
        assertTrue(isToggleEnabled(state))
    }

    // ==========================================
    // Scenario Integration Tests
    // ==========================================

    @Test
    fun `scenario 1 - shared at start shows card`() {
        // At app start with shared instance detected
        val state =
            createDefaultState().copy(
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        assertTrue("Banner should show when shared instance is active", shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `scenario 2 - no shared at start hides card`() {
        // At app start with NO shared instance detected
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                sharedInstanceOnline = false,
                preferOwnInstance = false,
            )
        assertFalse("Banner should be hidden when no shared instance", shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `scenario 3a - using own, shared dies, toggle disabled`() {
        // Was using own instance (by preference), shared goes offline
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                preferOwnInstance = true,
                sharedInstanceOnline = false, // Shared died
            )
        // Banner hidden (no shared instance online)
        assertFalse("Banner should hide when shared offline", shouldShowSharedInstanceBanner(state))
        // Toggle would be disabled IF banner was shown
        assertFalse("Toggle should be disabled", isToggleEnabled(state))
    }

    @Test
    fun `scenario 3b - using shared, shared dies, informational state shown`() {
        // Was using shared instance, it goes offline, RNS auto-switches to own
        // isSharedInstance is now false (RNS switched), wasUsingSharedInstance is true
        val state =
            createDefaultState().copy(
                isSharedInstance = false, // RNS auto-switched to own
                preferOwnInstance = false,
                wasUsingSharedInstance = true, // Tracking that we were using shared
                sharedInstanceOnline = false, // Shared is offline
            )
        assertTrue("Banner should show with informational state", shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `scenario 4 - no shared at start, shared starts later, card shown`() {
        // Started without shared, then Sideband started
        val state =
            createDefaultState().copy(
                isSharedInstance = false,
                sharedInstanceOnline = true, // Shared became available
                sharedInstanceAvailable = true, // Notification triggered
            )
        assertTrue("Banner should show when shared becomes available", shouldShowSharedInstanceBanner(state))
        assertTrue("Toggle should be enabled to switch to shared", isToggleEnabled(state))
    }

    // ==========================================
    // Toggle Checked State Tests (Bug Fix)
    // ==========================================

    @Test
    fun `toggle shows ON when using own instance by necessity`() {
        // BUG SCENARIO: Columba started first (no shared available), now Sideband is online
        // We are using own instance (isSharedInstance=false)
        // but didn't explicitly choose it (preferOwnInstance=false)
        val state =
            createDefaultState().copy(
                isSharedInstance = false, // Actually using own instance
                preferOwnInstance = false, // Didn't explicitly choose own (default)
                sharedInstanceOnline = true, // Sideband is now available
            )

        // Banner should show (shared is online)
        assertTrue("Banner should show", shouldShowSharedInstanceBanner(state))

        // The toggle should show the ACTUAL state (using own), not preference
        val toggleChecked = computeToggleChecked(state)
        assertTrue("Toggle should be ON when using own instance", toggleChecked)
    }

    @Test
    fun `toggle shows OFF when using shared instance`() {
        val state =
            createDefaultState().copy(
                isSharedInstance = true, // Using shared
                preferOwnInstance = false,
                sharedInstanceOnline = true,
            )

        val toggleChecked = computeToggleChecked(state)
        assertFalse("Toggle should be OFF when using shared instance", toggleChecked)
    }

    @Test
    fun `toggle shows ON when using own instance by choice`() {
        val state =
            createDefaultState().copy(
                isSharedInstance = false, // Using own
                preferOwnInstance = true, // Explicitly chose own
                sharedInstanceOnline = true,
            )

        val toggleChecked = computeToggleChecked(state)
        assertTrue("Toggle should be ON when using own instance by choice", toggleChecked)
    }
}
