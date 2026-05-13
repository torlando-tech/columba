package network.columba.app.rns.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RnsErrorTest {
    @Test
    fun `Generic preserves message and stack trace text`() {
        val err = RnsError.Generic(message = "boom", stackTraceText = "at foo(bar.kt:1)")
        assertEquals("boom", err.message)
        assertEquals("at foo(bar.kt:1)", err.stackTraceText)
    }

    @Test
    fun `BackendNotReady is a singleton`() {
        // data object guarantees identity equality.
        assertEquals(RnsError.BackendNotReady, RnsError.BackendNotReady)
        assertTrue(RnsError.BackendNotReady === RnsError.BackendNotReady)
    }

    @Test
    fun `IdentityNotFound carries hex hash verbatim`() {
        val err = RnsError.IdentityNotFound(hashHex = "deadbeef")
        assertEquals("deadbeef", err.hashHex)
    }

    @Test
    fun `TimeoutExceeded carries operation and millis`() {
        val err = RnsError.TimeoutExceeded(operation = "sendLxmfMessage", timeoutMs = 5000)
        assertEquals("sendLxmfMessage", err.operation)
        assertEquals(5000L, err.timeoutMs)
    }

    @Test
    fun `FeatureUnsupported names the capability path`() {
        val err = RnsError.FeatureUnsupported(feature = "performance.batteryProfileTuning")
        assertEquals("performance.batteryProfileTuning", err.feature)
    }

    @Test
    fun `CallStateInvalid distinguishes expected and actual`() {
        val err = RnsError.CallStateInvalid(expected = "ESTABLISHED", actual = "RINGING")
        assertEquals("ESTABLISHED", err.expected)
        assertEquals("RINGING", err.actual)
    }

    @Test
    fun `NomadnetPageNotFound carries dest hash and path`() {
        val err = RnsError.NomadnetPageNotFound(destHash = "abc123", path = "/page/index.mu")
        assertEquals("abc123", err.destHash)
        assertEquals("/page/index.mu", err.path)
    }

    @Test
    fun `equality is structural for data classes`() {
        val a = RnsError.IdentityNotFound("aa")
        val b = RnsError.IdentityNotFound("aa")
        val c = RnsError.IdentityNotFound("bb")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `RnsException wraps RnsError and exposes a sensible message`() {
        val err = RnsError.IdentityNotFound("deadbeef")
        val ex = RnsException(err)
        assertEquals(err, ex.error)
        assertNotNull(ex.message)
        assertTrue("message includes hash", ex.message!!.contains("deadbeef"))
    }

    @Test
    fun `RnsException describes BackendNotReady`() {
        val ex = RnsException(RnsError.BackendNotReady)
        assertNotNull(ex.message)
        assertTrue("Backend not ready" in ex.message!!)
    }

    @Test
    fun `RnsException describes TimeoutExceeded with operation name`() {
        val ex = RnsException(RnsError.TimeoutExceeded("sendLxmfMessage", 5000))
        assertTrue("sendLxmfMessage" in ex.message!!)
        assertTrue("5000" in ex.message!!)
    }
}
