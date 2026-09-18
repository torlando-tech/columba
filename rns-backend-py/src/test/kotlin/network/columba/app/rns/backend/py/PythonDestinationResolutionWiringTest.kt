package network.columba.app.rns.backend.py

import android.content.Context
import com.chaquo.python.PyObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PythonDestinationResolutionWiringTest {
    @Test
    fun `all delivery destination surfaces share one construction`() {
        val runtime = PythonRnsRuntime(mockk<Context>())
        val events = mockk<PythonEventBridge>()
        every { events.locationTelemetry } returns MutableSharedFlow()
        val lxmf = PythonRnsLxmf(runtime, events)
        val telemetry = PythonRnsTelemetry(runtime, events)
        val core = PythonRnsCore(runtime, events)
        val destinationHash = ByteArray(16) { it.toByte() }
        val destinationHex = destinationHash.toHex()
        val destination = checkNotNull(PyObject.getInstance(0x71L))
        val identity = checkNotNull(PyObject.getInstance(0x72L))
        val destinationClass = checkNotNull(PyObject.getInstance(0x73L))
        val constructionStarted = CountDownLatch(1)
        val remainingSurfacesJoined = CountDownLatch(4)
        val allowConstructionToFinish = CountDownLatch(1)
        val constructionCount = AtomicInteger()
        val executor = Executors.newFixedThreadPool(5)

        runtime.testDestinationHashResolver = { name, resolvedIdentity ->
            assertEquals("lxmf.delivery", name)
            assertTrue(resolvedIdentity === identity)
            destinationHex
        }
        runtime.testDestinationFactory = {
            constructionCount.incrementAndGet()
            constructionStarted.countDown()
            check(allowConstructionToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            destination
        }
        runtime.testDestinationJoinObserver = { remainingSurfacesJoined.countDown() }

        try {
            val lxmfResult = executor.submit<PyObject> {
                lxmf.resolveRecipientDestination(destinationHash)
            }
            assertTrue(constructionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val telemetryResult = executor.submit<PyObject> {
                telemetry.resolveDeliveryDestination(destinationHash)
            }
            val conversationResult = executor.submit<PyObject> {
                core.resolveConversationDestination(identity, destinationClass)
            }
            val pythonBridgeResult = executor.submit<PyObject> {
                KotlinDestinationResolverBridge(runtime).resolve(identity)
            }
            val genericApiResult = executor.submit<PyObject> {
                core.resolveCreatedDestination(
                    Direction.OUT,
                    DestinationType.SINGLE,
                    "lxmf",
                    listOf("delivery"),
                    identity,
                ) { error("coordinator must use the in-flight destination") }
            }
            assertTrue(
                "all delivery surfaces must join LXMF's in-flight construction",
                remainingSurfacesJoined.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            allowConstructionToFinish.countDown()

            assertTrue(lxmfResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertTrue(telemetryResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertTrue(conversationResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertTrue(pythonBridgeResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertTrue(genericApiResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertEquals(1, constructionCount.get())
            assertTrue(runtime.cachedDestination(destinationHex) === destination)
        } finally {
            allowConstructionToFinish.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
