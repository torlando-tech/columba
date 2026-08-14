package network.columba.app.rns.backend.py

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PythonRnsTransportAdminRNodeStatusTest {
    private fun createAdmin(): PythonRnsTransportAdmin {
        val events = mockk<PythonEventBridge>()
        every { events.reactionReceived } returns MutableSharedFlow()
        return PythonRnsTransportAdmin(runtime = mockk(), events = events)
    }

    @Test
    fun `RNode status published before subscription is replayed`() =
        runTest {
            val admin = createAdmin()

            admin.publishRNodeOnlineStatus("Test RNode", true)

            admin.interfaceStatusFlow.test {
                assertEquals(
                    """{"updates":{"Test RNode":true}}""",
                    awaitItem(),
                )
            }
        }

    @Test
    fun `latest RNode status replaces stale replay while no observer is attached`() =
        runTest {
            val admin = createAdmin()

            admin.publishRNodeOnlineStatus("Test RNode", false)
            admin.publishRNodeOnlineStatus("Test RNode", true)

            admin.interfaceStatusFlow.test {
                assertEquals(
                    """{"updates":{"Test RNode":true}}""",
                    awaitItem(),
                )
            }
        }
}
