package network.columba.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    // ========== resolveDark ==========

    @Test
    fun `system mode follows device theme`() {
        assertTrue(ThemeMode.SYSTEM.resolveDark(isSystemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDark(isSystemDark = false))
    }

    @Test
    fun `light mode always renders light`() {
        assertFalse(ThemeMode.LIGHT.resolveDark(isSystemDark = true))
        assertFalse(ThemeMode.LIGHT.resolveDark(isSystemDark = false))
    }

    @Test
    fun `dark mode always renders dark`() {
        assertTrue(ThemeMode.DARK.resolveDark(isSystemDark = true))
        assertTrue(ThemeMode.DARK.resolveDark(isSystemDark = false))
    }

    // ========== fromIdentifier ==========

    @Test
    fun `fromIdentifier parses valid names`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromIdentifier("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromIdentifier("DARK"))
    }

    @Test
    fun `fromIdentifier falls back to system for null`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier(null))
    }

    @Test
    fun `fromIdentifier falls back to system for unknown values`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier("AUTO"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier("dark-mode"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromIdentifier("123"))
    }

    @Test
    fun `fromIdentifier round trips through name`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromIdentifier(mode.name))
        }
    }
}
