package network.columba.app.rns.backend.kt

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.columba.app.rns.api.call.CallAttemptSnapshot
import network.columba.app.rns.api.call.CallLifecycleRecorder
import network.reticulum.identity.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter

/**
 * Regression test for the outgoing-call history wiring.
 *
 * The shared lifecycle owner exposes admitOutgoing, but a prior implementation never
 * wired it into the call managers' call() methods, so outgoing calls were placed
 * without ever creating a durable call-history row. This test pins that
 * [NativeCallManager.call] durably admits an OUTGOING attempt (with the correct
 * local/remote identities and codec profile) BEFORE signalling starts.
 */
class NativeCallManagerOutgoingAdmissionTest {

    @Before
    fun mockSingletons() {
        // NativeCallManager's property init calls AudioDevice / PacketRouter /
        // CallCoordinator getInstance(). Mock them so a mock Context doesn't need a real
        // AudioManager (which throws ClassCastException on a relaxed Context).
        mockkObject(AudioDevice)
        mockkObject(PacketRouter)
        mockkObject(CallCoordinator)
        every { AudioDevice.getInstance(any()) } returns mockk(relaxed = true)
        every { PacketRouter.getInstance(any()) } returns mockk(relaxed = true)
        every { CallCoordinator.getInstance() } returns mockk(relaxed = true)
    }

    @After
    fun unmockSingletons() {
        unmockkObject(CallCoordinator)
        unmockkObject(PacketRouter)
        unmockkObject(AudioDevice)
    }

    @Test
    fun `outgoing call admits an OUTGOING attempt before signalling`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val identity = mockk<Identity>(relaxed = true)
        every { identity.hexHash } returns "aabbccddaabbccdd"
        val transport = mockk<NativeNetworkTransport>(relaxed = true)
        val recorder = mockk<CallLifecycleRecorder>(relaxed = true)

        // Capture the admitted request and accept it so admitOutgoing proceeds to the
        // launch lambda (which would start telephone.call()).
        val captured = mutableListOf<CallAttemptRequest>()
        coEvery { recorder.acceptCallAttempt(any()) } answers {
            val req = firstArg<CallAttemptRequest>()
            captured += req
            Result.success(
                CallAttemptSnapshot(
                    callAttemptId = "call-1",
                    direction = req.direction,
                    localIdentityHash = req.localIdentityHash,
                    remoteIdentityHash = req.remoteIdentityHash,
                    codecProfileCode = req.codecProfileCode,
                    attemptedAt = 1L,
                ),
            )
        }

        val manager =
            NativeCallManager(
                context = context,
                deliveryIdentity = identity,
                transport = transport,
                recorder = recorder,
            )

        // call() launches on Dispatchers.IO; wait for the admitted attempt to land.
        manager.call("deadbeefdeadbeef", 0x40)
        val deadline = System.currentTimeMillis() + 2_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
            delay(10)
        }

        assertTrue("Outgoing attempt was not admitted", captured.isNotEmpty())
        assertEquals(CallAttemptDirection.OUTGOING, captured[0].direction)
        assertEquals("deadbeefdeadbeef", captured[0].remoteIdentityHash)
        assertEquals("aabbccddaabbccdd", captured[0].localIdentityHash)
        assertEquals(0x40, captured[0].codecProfileCode)
    }
}
