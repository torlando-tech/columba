package network.columba.app.rns.backend.kt

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
    fun `recipient proof before fallback admission prevents enqueue`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val releaseWorker = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        executor.submit {
            workerStarted.countDown()
            releaseWorker.await()
        }
        check(workerStarted.await(5, TimeUnit.SECONDS))
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val fixture = fixture(scope, fallbackDispatcher = dispatcher)
            val updates = collect(fixture.stream, 1)

            fixture.failed(fixture.message)
            fixture.delivered(fixture.message)
            releaseWorker.countDown()
            withTimeout(5_000L) { updates.job.join() }

            assertEquals(listOf(DeliveryStatus.DELIVERED), updates.values)
            coVerify(exactly = 0) { fixture.router.handleOutbound(any()) }
        } finally {
            releaseWorker.countDown()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed evidence can be promoted by delayed recipient proof`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fixture = fixture(scope, propagationAvailable = false)
            val updates = collect(fixture.stream, 2)

            fixture.failed(fixture.message)
            fixture.delivered(fixture.message)
            withTimeout(5_000L) { updates.job.join() }

            assertEquals(listOf(DeliveryStatus.FAILED, DeliveryStatus.DELIVERED), updates.values)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `duplicate primary failure callbacks own one fallback enqueue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val submitted = CountDownLatch(1)
        try {
            val fixture = fixture(scope)
            coEvery { fixture.router.handleOutbound(fixture.message) } coAnswers {
                submitted.countDown()
            }
            val updates = collect(fixture.stream, 1)

            fixture.failed(fixture.message)
            fixture.failed(fixture.message)
            check(submitted.await(5, TimeUnit.SECONDS))
            withTimeout(5_000L) { updates.job.join() }

            assertEquals(listOf(DeliveryStatus.RETRYING_PROPAGATED), updates.values)
            coVerify(exactly = 1) { fixture.router.handleOutbound(fixture.message) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `native fallback publishes retry before submission throw failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fixture = fixture(scope)
            coEvery { fixture.router.handleOutbound(fixture.message) } throws
                IllegalStateException("submission failed")
            val updates = collect(fixture.stream, 2)

            fixture.failed(fixture.message)
            withTimeout(5_000L) { updates.job.join() }

            assertEquals(listOf(DeliveryStatus.RETRYING_PROPAGATED, DeliveryStatus.FAILED), updates.values)
        } finally {
            scope.cancel()
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        propagationAvailable: Boolean = true,
        fallbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    ): Fixture {
        val stream = DeliveryStatusEventStream()
        val sender =
            NativeMessageSender(
                routerProvider = { null },
                deliveryIdentityProvider = { null },
                deliveryDestinationProvider = { null },
                deliveryStatusEvents = stream,
                scopeProvider = { scope },
                fallbackDispatcher = fallbackDispatcher,
            )
        val message = mockk<LXMessage>(relaxed = true)
        val router = mockk<LXMRouter>()
        val failedCallback = slot<(LXMessage) -> Unit>()
        val deliveryCallback = slot<(LXMessage) -> Unit>()
        every { message.failedCallback = capture(failedCallback) } just Runs
        every { message.deliveryCallback = capture(deliveryCallback) } just Runs
        every { message.hash } returns ByteArray(32) { it.toByte() }
        every { message.desiredMethod } returns NativeDeliveryMethod.DIRECT
        every { message.method } returns NativeDeliveryMethod.DIRECT
        every { message.state } returns network.reticulum.lxmf.MessageState.DELIVERED
        every { router.getActivePropagationNode() } returns if (propagationAvailable) mockk() else null
        coEvery { router.handleOutbound(message) } just Runs

        sender.installDeliveryCallbacks(
            message,
            router,
            tryPropagationOnFail = true,
            lxmfMethod = NativeDeliveryMethod.DIRECT,
        )
        return Fixture(
            message = message,
            router = router,
            stream = stream,
            failed = failedCallback.captured,
            delivered = deliveryCallback.captured,
        )
    }

    private fun CoroutineScope.collect(stream: DeliveryStatusEventStream, count: Int): Updates {
        val values = mutableListOf<DeliveryStatus>()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                stream.events.take(count).toList().mapTo(values) { it.status }
            }
        return Updates(values, job)
    }

    private data class Fixture(
        val message: LXMessage,
        val router: LXMRouter,
        val stream: DeliveryStatusEventStream,
        val failed: (LXMessage) -> Unit,
        val delivered: (LXMessage) -> Unit,
    )

    private data class Updates(
        val values: List<DeliveryStatus>,
        val job: kotlinx.coroutines.Job,
    )
}
