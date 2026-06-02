package network.columba.app.nomadnet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared NomadNet link-field helpers: splitting a path from its
 * trailing backtick field block, and turning link fields into the request
 * `data` JSON the node expects. These back both deep-link / URL-bar page loads
 * and in-page link taps.
 */
class NomadNetLinkFieldsTest {
    // ── splitNomadNetPathFields ──

    @Test
    fun `path without backtick has no fields`() {
        val (path, fields) = splitNomadNetPathFields("/page/index.mu")
        assertEquals("/page/index.mu", path)
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `backtick block is split into clean path and pipe-separated fields`() {
        // The reported repro.
        val (path, fields) = splitNomadNetPathFields("/page/forum/thread.mu`cat=help|thread=how-to-rngit")
        assertEquals("/page/forum/thread.mu", path)
        assertEquals(listOf("cat=help", "thread=how-to-rngit"), fields)
    }

    @Test
    fun `single field after backtick is captured`() {
        val (path, fields) = splitNomadNetPathFields("/page/x.mu`cat=help")
        assertEquals("/page/x.mu", path)
        assertEquals(listOf("cat=help"), fields)
    }

    @Test
    fun `empty field tokens are dropped`() {
        val (_, fields) = splitNomadNetPathFields("/page/x.mu`a=1||b=2")
        assertEquals(listOf("a=1", "b=2"), fields)
    }

    @Test
    fun `trailing backtick with no fields yields clean path and no fields`() {
        val (path, fields) = splitNomadNetPathFields("/page/x.mu`")
        assertEquals("/page/x.mu", path)
        assertTrue(fields.isEmpty())
    }

    // ── buildNomadNetRequestData ──

    @Test
    fun `no fields produces null`() {
        assertNull(buildNomadNetRequestData(emptyList(), emptyMap()))
    }

    @Test
    fun `key=value fields become var_-prefixed request data`() {
        val json = buildNomadNetRequestData(listOf("cat=help", "thread=how-to-rngit"), emptyMap())
        val data = JSONObject(json!!)
        assertEquals("help", data.getString("var_cat"))
        assertEquals("how-to-rngit", data.getString("var_thread"))
    }

    @Test
    fun `value containing equals splits only on the first equals`() {
        val json = buildNomadNetRequestData(listOf("token=a=b=c"), emptyMap())
        assertEquals("a=b=c", JSONObject(json!!).getString("var_token"))
    }

    @Test
    fun `bare field name pulls value from form fields`() {
        val json = buildNomadNetRequestData(listOf("username"), mapOf("username" to "alice"))
        assertEquals("alice", JSONObject(json!!).getString("username"))
    }

    @Test
    fun `bare field with no form value submits empty string`() {
        val json = buildNomadNetRequestData(listOf("username"), emptyMap())
        assertEquals("", JSONObject(json!!).getString("username"))
    }

    @Test
    fun `star submits all form fields`() {
        val json = buildNomadNetRequestData(listOf("*"), mapOf("a" to "1", "b" to "2"))
        val data = JSONObject(json!!)
        assertEquals("1", data.getString("a"))
        assertEquals("2", data.getString("b"))
    }

    @Test
    fun `star adds form fields alongside explicit inline vars`() {
        // An inline "name=Bob" is keyed as var_name, so the "*" form snapshot of
        // the bare "name" field coexists with it rather than colliding.
        val json = buildNomadNetRequestData(listOf("name=Bob", "*"), mapOf("name" to "Alice"))
        val data = JSONObject(json!!)
        assertEquals("Bob", data.getString("var_name"))
        assertEquals("Alice", data.getString("name"))
    }
}
