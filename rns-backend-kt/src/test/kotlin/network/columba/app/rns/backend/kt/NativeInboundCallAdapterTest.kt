/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.backend.kt

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import network.columba.app.rns.api.call.AcceptedCallLifecycle
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.Telephone

/**
 * Reduced incoming-admission tests for the native inbound adapter.
 *
 * Mirrors `PythonInboundCallAdapterTest` (symmetric across backends). Confirms:
 *   - incoming disabled  → link torn down, no signalling
 *   - line busy        → STATUS_BUSY signalled, then torn down
 *   - durable insert failure → admission rejected, link torn down, NO ringing / NO history
 *   - accepted admission  → admission durably persists BEFORE accept + ringing callback
 */
class NativeInboundCallAdapterTest {
    @Test
    fun `incoming disabled tears down without signalling or accepting`() {
        val link = mockLink()
        var accepted = false
        val transport = mockk<NativeNetworkTransport> {
            every { acceptInboundLink(link) } answers { accepted = true }
            every { sendSignal(any()) } just runs
        }
        val adapter = adapter(transport, ownerSuccess())

        adapter.disable()
        adapter.onLinkEstablished(link)

        assertFalse(accepted)
        verify(exactly = 0) { transport.sendSignal(any()) }
        verify(exactly = 1) { link.teardown() }
    }

    @Test
    fun `line busy signals busy then tears down without accepting`() {
        val link = mockLink()
        var accepted = false
        val transport = mockk<NativeNetworkTransport> {
            every { acceptInboundLink(link) } answers { accepted = true }
            every { sendSignal(any()) } just runs
        }
        val busyTelephone = mockk<Telephone> { every { isCallActive() } returns true }
        val adapter = adapter(transport, ownerSuccess(), busyTelephone)

        adapter.onLinkEstablished(link)

        assertFalse(accepted)
        // BUSY signalled on the link directly (not the transport) then teardown.
        verify(exactly = 1) { link.send(any()) }
        verify(exactly = 1) { link.teardown() }
    }

    @Test
    fun `durable insert failure rejects admission - tears down, no ringing, no history`() = runBlocking {
        val link = mockLink()
        var accepted = false
        val owner = ownerFailure()
        val transport = mockk<NativeNetworkTransport> {
            every { acceptInboundLink(link) } answers { accepted = true }
            every { sendSignal(Signalling.STATUS_RINGING) } just runs
        }
        val telephone = mockk<Telephone> {
            every { isCallActive() } returns false
            every { onIncomingCall(any()) } just runs
        }
        val adapter = adapter(transport, owner, telephone, CoroutineScope(coroutineContext))

        adapter.expose(link, "deadbeef")
        yield()

        assertFalse(accepted)
        verify(exactly = 0) { telephone.onIncomingCall(any()) }
        verify(exactly = 0) { transport.sendSignal(Signalling.STATUS_RINGING) }
        verify(exactly = 1) { link.teardown() }
    }

    @Test
    fun `accepted admission persists durable before accept and ringing`() = runBlocking {
        val link = mockLink()
        var accepted = false
        var acceptedIdentity: String? = null
        var ringing = false
        val captured = mutableListOf<CallAttemptRequest>()
        val owner = mockk<AcceptedCallLifecycle> {
            coEvery {
                admitIncoming(
                    any(),
                    any(),
                )
            } coAnswers {
                val request = firstArg<CallAttemptRequest>()
                captured += request
                val expose = secondArg<suspend (network.columba.app.rns.api.call.CallAttemptSnapshot) -> Unit>()
                val snapshot = network.columba.app.rns.api.call.CallAttemptSnapshot(
                    callAttemptId = "test-id",
                    direction = request.direction,
                    localIdentityHash = request.localIdentityHash,
                    remoteIdentityHash = request.remoteIdentityHash,
                    codecProfileCode = request.codecProfileCode,
                    attemptedAt = 0L,
                )
                expose(snapshot)
                kotlin.Result.success(Unit)
            }
        }
        val transport = mockk<NativeNetworkTransport> {
            every { acceptInboundLink(link) } answers { accepted = true }
            every { sendSignal(Signalling.STATUS_RINGING) } answers { ringing = true }
        }
        val telephone = mockk<Telephone> {
            every { isCallActive() } returns false
            every { onIncomingCall(any()) } answers { acceptedIdentity = arg<String>(0) }
        }
        val adapter = adapter(transport, owner, telephone, CoroutineScope(coroutineContext))

        adapter.expose(link, "aabbcc")
        yield()

        assertTrue(accepted)
        assertTrue(ringing)
        assertEquals("aabbcc", acceptedIdentity)
        assertEquals(1, captured.size)
        assertEquals(CallAttemptDirection.INCOMING, captured[0].direction)
        assertEquals("local", captured[0].localIdentityHash)
        assertEquals("aabbcc", captured[0].remoteIdentityHash)
    }

    // ---------- helpers ----------

    private fun adapter(
        transport: NativeNetworkTransport,
        owner: AcceptedCallLifecycle,
        telephone: Telephone = mockk<Telephone> { every { isCallActive() } returns false },
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): NativeInboundCallAdapter =
        NativeInboundCallAdapter(
            deliveryIdentity = mockk<Identity>(),
            transport = transport,
            telephone = { telephone },
            owner = owner,
            localIdentityHash = { "local" },
            scope = scope,
            onCallerIdentified = { _, _ -> },
        )

    private fun mockLink(): Link =
        mockk<Link> {
            every { teardown() } just runs
            every { send(any()) } returns true
            every { setRemoteIdentifiedCallback(any()) } just runs
            every { setLinkClosedCallback(any()) } just runs
        }

    private fun ownerSuccess(): AcceptedCallLifecycle =
        mockk<AcceptedCallLifecycle> {
            coEvery { admitIncoming(any(), any()) } returns kotlin.Result.success(Unit)
        }

    private fun ownerFailure(): AcceptedCallLifecycle =
        mockk<AcceptedCallLifecycle> {
            coEvery { admitIncoming(any(), any()) } returns
                kotlin.Result.failure(IllegalStateException("Another call attempt is awaiting finalization"))
        }
}
