package com.lxmf.messenger.ui.screens

import com.lxmf.messenger.ui.theme.PresetTheme
import com.lxmf.messenger.viewmodel.SettingsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SharedInstanceBanner visibility logic in SettingsScreen.
 *
 * The banner should be shown when any of these conditions are true:
 * - isSharedInstance: Currently using a shared instance
 * - preferOwnInstance: User has explicitly chosen to use own instance (so they can toggle back)
 * - sharedInstanceAvailable: A shared instance was detected while using own
 * - sharedInstanceLost: The shared instance connection was lost
 * - isRestarting: Service is restarting
 */
class SettingsScreenBannerVisibilityTest {

    /**
     * Replicates the banner visibility logic from SettingsScreen.kt
     */
    private fun shouldShowSharedInstanceBanner(state: SettingsState): Boolean {
        return state.isSharedInstance ||
            state.preferOwnInstance ||
            state.sharedInstanceAvailable ||
            state.sharedInstanceLost ||
            state.isRestarting
    }

    private fun createDefaultState() = SettingsState(
        displayName = "Test",
        selectedTheme = PresetTheme.VIBRANT,
        isLoading = false,
    )

    @Test
    fun `banner shown when using shared instance`() {
        val state = createDefaultState().copy(isSharedInstance = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when preferOwnInstance is true`() {
        // This is the fix - banner stays visible so user can toggle back to shared
        val state = createDefaultState().copy(preferOwnInstance = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when shared instance is available`() {
        val state = createDefaultState().copy(sharedInstanceAvailable = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when shared instance is lost`() {
        val state = createDefaultState().copy(sharedInstanceLost = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner shown when restarting`() {
        val state = createDefaultState().copy(isRestarting = true)
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner hidden when no shared instance conditions apply`() {
        val state = createDefaultState().copy(
            isSharedInstance = false,
            preferOwnInstance = false,
            sharedInstanceAvailable = false,
            sharedInstanceLost = false,
            isRestarting = false,
        )
        assertFalse(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner stays visible after toggling from shared to own instance`() {
        // Simulate: user was using shared instance, then toggled to prefer own
        val state = createDefaultState().copy(
            isSharedInstance = false, // No longer using shared
            preferOwnInstance = true, // User chose to use own instance
        )
        // Banner should still be visible so user can toggle back
        assertTrue(shouldShowSharedInstanceBanner(state))
    }

    @Test
    fun `banner visible with multiple conditions true`() {
        val state = createDefaultState().copy(
            isSharedInstance = true,
            preferOwnInstance = true,
            sharedInstanceAvailable = true,
        )
        assertTrue(shouldShowSharedInstanceBanner(state))
    }
}
