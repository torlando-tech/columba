package network.columba.app.ui.screens

import network.columba.app.data.util.ThreadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rename-dialog decisions (threads-spec §6.2). Pure JUnit: the dialog itself
 * nests a text field in an AlertDialog window, which never reports idle under
 * Robolectric; its on-screen behaviour is covered by
 * `maestro/threads/08-rename-title.yaml`.
 */
class ThreadRenameRulesTest {
    @Test
    fun `unchanged local title cannot be saved`() {
        val rules = threadRenameRules(initialTitle = "Trip planning", input = "Trip planning")

        assertEquals("Trip planning", rules.saveValue)
        assertFalse(rules.canSave)
        assertTrue(rules.canReset)
    }

    @Test
    fun `automatic title offers no reset`() {
        val rules = threadRenameRules(initialTitle = "", input = "")

        assertNull(rules.saveValue)
        assertFalse(rules.canSave)
        assertFalse(rules.canReset)
    }

    @Test
    fun `typed name is sanitized before saving`() {
        val rules = threadRenameRules(initialTitle = "", input = "  Mesh\nbuild  ")

        assertEquals("Mesh build", rules.saveValue)
        assertTrue(rules.canSave)
    }

    @Test
    fun `whitespace-only input cannot be saved`() {
        val rules = threadRenameRules(initialTitle = "Trip planning", input = "   \n\t ")

        assertNull(rules.saveValue)
        assertFalse(rules.canSave)
    }

    @Test
    fun `overlong input is capped at the policy limit`() {
        val rules = threadRenameRules(initialTitle = "", input = "x".repeat(ThreadPolicy.MAX_THREAD_TITLE_LENGTH + 20))

        assertEquals(ThreadPolicy.MAX_THREAD_TITLE_LENGTH, rules.saveValue?.length)
        assertTrue(rules.canSave)
    }

    @Test
    fun `renaming back to the stored value disables save`() {
        val rules = threadRenameRules(initialTitle = "Mesh build", input = " Mesh  build ")

        assertEquals("Mesh build", rules.saveValue)
        assertFalse(rules.canSave)
    }
}
