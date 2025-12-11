package com.lxmf.messenger.reticulum.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FailedInterface data class.
 *
 * FailedInterface represents an interface that failed to initialize,
 * such as AutoInterface with a port conflict.
 */
class FailedInterfaceTest {

    @Test
    fun `FailedInterface stores name and error correctly`() {
        val failed = FailedInterface(
            name = "AutoInterface",
            error = "Port 29716 already in use",
            recoverable = true,
        )

        assertEquals("AutoInterface", failed.name)
        assertEquals("Port 29716 already in use", failed.error)
        assertTrue(failed.recoverable)
    }

    @Test
    fun `FailedInterface defaults recoverable to true`() {
        val failed = FailedInterface(
            name = "AutoInterface",
            error = "Some error",
        )

        assertTrue(failed.recoverable)
    }

    @Test
    fun `FailedInterface can be non-recoverable`() {
        val failed = FailedInterface(
            name = "CriticalInterface",
            error = "Hardware failure",
            recoverable = false,
        )

        assertFalse(failed.recoverable)
    }

    @Test
    fun `FailedInterface equality works correctly`() {
        val failed1 = FailedInterface(
            name = "AutoInterface",
            error = "Port conflict",
            recoverable = true,
        )
        val failed2 = FailedInterface(
            name = "AutoInterface",
            error = "Port conflict",
            recoverable = true,
        )
        val failed3 = FailedInterface(
            name = "AutoInterface",
            error = "Different error",
            recoverable = true,
        )

        assertEquals(failed1, failed2)
        assertFalse(failed1 == failed3)
    }

    @Test
    fun `typical AutoInterface port conflict scenario`() {
        // This is the actual use case: AutoInterface fails due to Sideband
        val failed = FailedInterface(
            name = "AutoInterface",
            error = "Port 29716 already in use (another Reticulum app may be running)",
            recoverable = true,
        )

        assertEquals("AutoInterface", failed.name)
        assertTrue(failed.error.contains("Port 29716"))
        assertTrue(failed.error.contains("already in use"))
        assertTrue(failed.recoverable) // User can close other app and restart
    }
}
