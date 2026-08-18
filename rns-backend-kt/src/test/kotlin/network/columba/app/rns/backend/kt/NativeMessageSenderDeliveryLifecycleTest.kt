package network.columba.app.rns.backend.kt

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.DeliveryStatusEventStream
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMessageSenderDeliveryLifecycleTest {
    @Test
    fun `native fallback publishes retry before submission throw failure`() = runBlocking {
        val stream = DeliveryStatusEventStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sender =
            NativeMessageSender(
                routerProvider = { null },
                deliveryIdentityProvider = { null },
                deliveryDestinationProvider = { null },
                deliveryStatusEvents = stream,
                scopeProvider = { scope },
            )
        val message = mockk<LXMessage>(relaxed = true)
        val router = mockk<LXMRouter>()
        val failedCallback = slot<(LXMessage) -> Unit>()
        every { message.failedCallback = capture(failedCallback) } just Runs
        every { message.deliveryCallback = any() } just Runs
        every { message.hash } returns ByteArray(32) { it.toByte() }
        every { message.desiredMethod } returns NativeDeliveryMethod.DIRECT
        every { router.getActivePropagationNode() } returns mockk()
        coEvery { router.handleOutbound(message) } throws IllegalStateException("submission failed")

        sender.installDeliveryCallbacks(
            message,
            router,
            tryPropagationOnFail = true,
            lxmfMethod = NativeDeliveryMethod.DIRECT,
        )
        val updates = mutableListOf<DeliveryStatus>()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                stream.events.take(2).toList().mapTo(updates) { it.status }
            }

        failedCallback.captured(message)
        withTimeout(5_000L) { collector.join() }

        assertEquals(listOf(DeliveryStatus.RETRYING_PROPAGATED, DeliveryStatus.FAILED), updates)
        scope.cancel()
    }
}
