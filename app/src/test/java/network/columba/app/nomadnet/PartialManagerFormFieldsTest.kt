package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NomadNet `partial` markup can declare which form fields its auto-refresh
 * request re-submits (`fieldNames`). Upstream TUI only forwards the declared
 * names, so an undeclared form field must never leak into a partial refresh;
 * `*` is the explicit "all fields" declaration (columba#1202-era regression,
 * fixed by 56143d4ba).
 */
class PartialManagerFormFieldsTest {
    private val available =
        mapOf(
            "msg" to "hello",
            "password" to "hunter2",
            "submit" to "send",
        )

    @Test
    fun `empty declaration forwards no fields at all`() {
        assertTrue(filterDeclaredFormFields(available, emptyList()).isEmpty())
    }

    @Test
    fun `declared subset forwards only the declared names`() {
        val filtered = filterDeclaredFormFields(available, listOf("msg", "submit"))

        assertEquals(mapOf("msg" to "hello", "submit" to "send"), filtered)
        assertFalse("undeclared field must not be forwarded", filtered.containsKey("password"))
    }

    @Test
    fun `wildcard declaration forwards every field`() {
        assertEquals(available, filterDeclaredFormFields(available, listOf("*")))
    }

    @Test
    fun `wildcard alongside named fields still forwards every field`() {
        assertEquals(available, filterDeclaredFormFields(available, listOf("*", "msg")))
    }

    @Test
    fun `declared name with no matching form field yields nothing`() {
        assertTrue(filterDeclaredFormFields(available, listOf("unknown")).isEmpty())
    }

    @Test
    fun `no available form fields yields nothing regardless of declaration`() {
        assertTrue(filterDeclaredFormFields(emptyMap(), listOf("*")).isEmpty())
        assertTrue(filterDeclaredFormFields(emptyMap(), listOf("msg")).isEmpty())
    }

    @Test
    fun `field-name matching is exact and case-sensitive`() {
        assertTrue(filterDeclaredFormFields(available, listOf("MSG")).isEmpty())
    }
}
