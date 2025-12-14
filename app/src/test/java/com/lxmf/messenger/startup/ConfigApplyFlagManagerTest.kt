package com.lxmf.messenger.startup

import android.content.Context
import android.content.SharedPreferences
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ConfigApplyFlagManager.
 */
class ConfigApplyFlagManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: ConfigApplyFlagManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor

        manager = ConfigApplyFlagManager(context)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== isApplyingConfig Tests ==========

    @Test
    fun `isApplyingConfig returns false when flag not set`() {
        every { prefs.getBoolean(ConfigApplyFlagManager.KEY_IS_APPLYING_CONFIG, false) } returns false

        assertFalse(manager.isApplyingConfig())
    }

    @Test
    fun `isApplyingConfig returns true when flag is set`() {
        every { prefs.getBoolean(ConfigApplyFlagManager.KEY_IS_APPLYING_CONFIG, false) } returns true

        assertTrue(manager.isApplyingConfig())
    }

    // ========== clearFlag Tests ==========

    @Test
    fun `clearFlag sets flag to false`() {
        manager.clearFlag()

        verify { editor.putBoolean(ConfigApplyFlagManager.KEY_IS_APPLYING_CONFIG, false) }
        verify { editor.apply() }
    }

    // ========== setFlag Tests ==========

    @Test
    fun `setFlag sets flag to true`() {
        manager.setFlag()

        verify { editor.putBoolean(ConfigApplyFlagManager.KEY_IS_APPLYING_CONFIG, true) }
        verify { editor.apply() }
    }

    // ========== isStaleFlag Tests ==========

    @Test
    fun `isStaleFlag returns true for SHUTDOWN status`() {
        assertTrue(manager.isStaleFlag("SHUTDOWN"))
    }

    @Test
    fun `isStaleFlag returns true for null status`() {
        assertTrue(manager.isStaleFlag(null))
    }

    @Test
    fun `isStaleFlag returns true for ERROR prefix status`() {
        assertTrue(manager.isStaleFlag("ERROR: Service crashed"))
        assertTrue(manager.isStaleFlag("ERROR:"))
        assertTrue(manager.isStaleFlag("ERROR: Unknown error"))
    }

    @Test
    fun `isStaleFlag returns false for READY status`() {
        assertFalse(manager.isStaleFlag("READY"))
    }

    @Test
    fun `isStaleFlag returns false for INITIALIZING status`() {
        assertFalse(manager.isStaleFlag("INITIALIZING"))
    }

    @Test
    fun `isStaleFlag returns false for RESTARTING status`() {
        assertFalse(manager.isStaleFlag("RESTARTING"))
    }

    @Test
    fun `isStaleFlag returns false for empty string`() {
        assertFalse(manager.isStaleFlag(""))
    }

    // ========== SharedPreferences Access Tests ==========

    @Test
    fun `uses correct SharedPreferences name`() {
        // Trigger lazy initialization by calling a method that uses prefs
        every { prefs.getBoolean(any(), any()) } returns false
        manager.isApplyingConfig()

        verify { context.getSharedPreferences(ConfigApplyFlagManager.PREFS_NAME, Context.MODE_PRIVATE) }
    }
}
