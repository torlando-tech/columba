package com.lxmf.messenger.service.manager

import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumServiceCallback
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CallbackBroadcaster.
 *
 * Note: RemoteCallbackList is an Android framework class that doesn't work
 * well in unit tests. These tests focus on the readiness callback logic.
 * Full broadcast testing requires instrumented tests.
 */
class CallbackBroadcasterTest {
    private lateinit var broadcaster: CallbackBroadcaster

    @Before
    fun setup() {
        broadcaster = CallbackBroadcaster()
    }

    @Test
    fun `registerReadinessCallback notifies immediately when already bound`() {
        val callback = mockk<IReadinessCallback>(relaxed = true)

        // Mark service as bound first
        broadcaster.setServiceBound(true)

        // Now register callback - should be notified immediately
        broadcaster.registerReadinessCallback(callback)

        verify { callback.onServiceReady() }
    }

    @Test
    fun `registerReadinessCallback does not notify when not bound`() {
        val callback = mockk<IReadinessCallback>(relaxed = true)

        // Service not bound
        broadcaster.registerReadinessCallback(callback)

        verify(exactly = 0) { callback.onServiceReady() }
    }

    @Test
    fun `setServiceBound true notifies pending readiness callback`() {
        val callback = mockk<IReadinessCallback>(relaxed = true)

        // Register callback first (service not bound)
        broadcaster.registerReadinessCallback(callback)
        verify(exactly = 0) { callback.onServiceReady() }

        // Now set bound - should notify
        broadcaster.setServiceBound(true)

        verify { callback.onServiceReady() }
    }

    @Test
    fun `setServiceBound false does not notify`() {
        val callback = mockk<IReadinessCallback>(relaxed = true)

        broadcaster.registerReadinessCallback(callback)
        broadcaster.setServiceBound(false)

        verify(exactly = 0) { callback.onServiceReady() }
    }

    @Test
    fun `register and unregister callbacks work without error`() {
        val callback = mockk<IReticulumServiceCallback>(relaxed = true)

        // These should not throw
        broadcaster.register(callback)
        broadcaster.unregister(callback)
    }

    @Test
    fun `kill does not throw`() {
        // Should not throw
        broadcaster.kill()
    }

    @Test
    fun `readiness callback handles RemoteException gracefully`() {
        val callback = mockk<IReadinessCallback>()
        every { callback.onServiceReady() } throws android.os.RemoteException("Test exception")

        broadcaster.registerReadinessCallback(callback)
        // This should not throw even if callback throws
        broadcaster.setServiceBound(true)
    }

    // ========== Event-Driven Broadcast Tests ==========

    @Test
    fun `broadcastBleConnectionChange does not throw when no callbacks registered`() {
        // Should not throw even with no callbacks
        broadcaster.broadcastBleConnectionChange("""[{"address": "AA:BB:CC:DD:EE:FF"}]""")
    }

    @Test
    fun `broadcastDebugInfoChange does not throw when no callbacks registered`() {
        // Should not throw even with no callbacks
        broadcaster.broadcastDebugInfoChange("""{"initialized": true}""")
    }

    @Test
    fun `broadcastInterfaceStatusChange does not throw when no callbacks registered`() {
        // Should not throw even with no callbacks
        broadcaster.broadcastInterfaceStatusChange("""{"Interface1": true}""")
    }

    @Test
    fun `broadcastBleConnectionChange handles empty JSON`() {
        // Should not throw with empty JSON
        broadcaster.broadcastBleConnectionChange("[]")
    }

    @Test
    fun `broadcastDebugInfoChange handles empty JSON`() {
        // Should not throw with empty JSON
        broadcaster.broadcastDebugInfoChange("{}")
    }

    @Test
    fun `broadcastInterfaceStatusChange handles empty JSON`() {
        // Should not throw with empty JSON
        broadcaster.broadcastInterfaceStatusChange("{}")
    }
}
