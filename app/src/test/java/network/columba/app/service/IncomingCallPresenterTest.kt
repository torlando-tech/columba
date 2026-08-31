package network.columba.app.service

import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.notifications.IncomingCallNotifier
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [IncomingCallPresenter]: the app-process component that presents
 * incoming calls (full-screen-intent notification) while the app is backgrounded
 * (issue #1079).
 */
class IncomingCallPresenterTest {
    /** Records notifier interactions so tests assert on real state, not mock calls. */
    private class RecordingNotifier : IncomingCallNotifier {
        val shownCalls = CopyOnWriteArrayList<Pair<String, String?>>()
        val cancelCount = java.util.concurrent.atomic.AtomicInteger(0)

        override fun showIncomingCallNotification(identityHash: String, callerName: String?) {
            shownCalls.add(identityHash to callerName)
        }

        override fun cancelIncomingCallNotification() {
            cancelCount.incrementAndGet()
        }
    }

    private val rnsTelephony: RnsTelephony = mockk()
    private val announceRepository: AnnounceRepository = mockk()
    private val contactRepository: ContactRepository = mockk()
    private val notifier = RecordingNotifier()
    private val callState = MutableStateFlow<CallState>(CallState.Idle)

    private val identityHash = "ef2f7fbc8ec20971cb9bc8f468984aaf0000"
    private val destinationHash = "aa".repeat(32)

    private lateinit var presenter: IncomingCallPresenter

    @Before
    fun setup() {
        every { rnsTelephony.callState } returns callState
        presenter =
            IncomingCallPresenter(
                rnsTelephony = rnsTelephony,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                incomingCallNotifier = notifier,
                dispatcher = UnconfinedTestDispatcher(),
            )
    }

    @After
    fun tearDown() {
        clearMocks(rnsTelephony, announceRepository, contactRepository)
    }

    private fun announce(peerName: String) =
        Announce(
            destinationHash = destinationHash,
            peerName = peerName,
            publicKey = byteArrayOf(1, 2, 3),
            appData = null,
            hops = 1,
            lastSeenTimestamp = 0L,
            nodeType = "",
        )

    private fun contact(nickname: String?) =
        ContactEntity(
            destinationHash = destinationHash,
            identityHash = "11".repeat(32),
            publicKey = null,
            customNickname = nickname,
            addedTimestamp = 0L,
            addedVia = "MANUAL",
        )

    @Test
    fun `incoming call posts notification with contact nickname`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns
                announce("Alice")
            coEvery { contactRepository.getContact(destinationHash) } returns contact("Boss")

            presenter.start()
            callState.value = CallState.Incoming(identityHash)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals(identityHash, notifier.shownCalls[0].first)
            assertEquals("Boss", notifier.shownCalls[0].second)
        }

    @Test
    fun `incoming call falls back to announce peer name when no nickname`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns
                announce("Alice")
            coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

            presenter.start()
            callState.value = CallState.Incoming(identityHash)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals(identityHash, notifier.shownCalls[0].first)
            assertEquals("Alice", notifier.shownCalls[0].second)
        }

    @Test
    fun `incoming call posts notification with null name for unknown caller`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns null

            presenter.start()
            callState.value = CallState.Incoming(identityHash)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals(identityHash, notifier.shownCalls[0].first)
            assertNull(notifier.shownCalls[0].second)
        }

    @Test
    fun `lookup failure fails open to null name`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } throws
                RuntimeException("db exploded")

            presenter.start()
            callState.value = CallState.Incoming(identityHash)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals(identityHash, notifier.shownCalls[0].first)
            assertNull(notifier.shownCalls[0].second)
        }

    @Test
    fun `transition to Active cancels the incoming notification`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns
                announce("Alice")
            coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

            presenter.start()
            callState.value = CallState.Incoming(identityHash)
            callState.value = CallState.Active(identityHash)

            // Posted once for the incoming call, cancelled once for the Active
            // transition (plus one cancel for the initial Idle on start).
            assertEquals(1, notifier.shownCalls.size)
            assertEquals("Alice", notifier.shownCalls[0].second)
            assertEquals(2, notifier.cancelCount.get())
        }

    @Test
    fun `outgoing states never post an incoming notification`() =
        runTest {
            presenter.start()
            callState.value = CallState.Connecting(identityHash)
            callState.value = CallState.Ringing(identityHash)

            assertEquals(0, notifier.shownCalls.size)
        }

    @Test
    fun `incoming state superseded during the name lookup is not posted`() =
        runTest {
            // The call is answered while the caller-name lookup is still in
            // flight: the stale Incoming state must not post the full-screen
            // notification after the call is over.
            val lookupGate = CompletableDeferred<Unit>()
            coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
                lookupGate.await()
                announce("Alice")
            }
            coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

            presenter.start()
            callState.value = CallState.Incoming(identityHash)
            // The presenter is now suspended in lookup 1; the call is answered
            // before that lookup completes.
            callState.value = CallState.Active(identityHash)
            lookupGate.complete(Unit)

            assertEquals(0, notifier.shownCalls.size)
            // Canceled once for the initial Idle on start, once for the Active
            // transition that superseded the lookup.
            assertEquals(2, notifier.cancelCount.get())
        }

    @Test
    fun `consecutive same-identity calls present the current call, not the stale lookup`() =
        runTest {
            // Call 1 arrives and its name lookup is in flight when the call is
            // answered and a second call from the SAME identity comes in. The
            // stale lookup's result must not be posted; the presentation must
            // come from the second call's own lookup (which sees the changed
            // peer name here).
            val lookupGate = CompletableDeferred<Unit>()
            val lookups = java.util.concurrent.atomic.AtomicInteger(0)
            coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
                if (lookups.incrementAndGet() == 1) {
                    lookupGate.await()
                    announce("Alice")
                } else {
                    announce("Bob")
                }
            }
            coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

            presenter.start()
            callState.value = CallState.Incoming(identityHash)
            // Lookup 1 is in flight; call 1 is answered and a second call from
            // the same identity arrives before it completes.
            callState.value = CallState.Active(identityHash)
            callState.value = CallState.Idle
            callState.value = CallState.Incoming(identityHash)
            lookupGate.complete(Unit)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals(identityHash, notifier.shownCalls[0].first)
            assertEquals("Bob", notifier.shownCalls[0].second)
        }

    @Test
    fun `start is idempotent`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns
                announce("Alice")
            coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

            presenter.start()
            presenter.start()
            callState.value = CallState.Incoming(identityHash)

            assertEquals(1, notifier.shownCalls.size)
            assertEquals("Alice", notifier.shownCalls[0].second)
        }

    @Test
    fun `resolveCallerName returns null when no announce exists`() =
        runTest {
            coEvery { announceRepository.findByIdentityHash(identityHash) } returns null

            assertNull(presenter.resolveCallerName(identityHash))
        }
}
