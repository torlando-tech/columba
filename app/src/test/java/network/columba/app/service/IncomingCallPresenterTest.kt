package network.columba.app.service

import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import network.columba.app.MainActivityVisibility
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
 *
 * The presenter runs on a [StandardTestDispatcher] backed by a shared
 * [TestCoroutineScheduler], so each test drives it deterministically: a state
 * change is only processed when [runCurrent] advances the scheduler. Grouping
 * several state changes before a [runCurrent] models the production case where
 * the whole churn collapses into the collector's suspension and the StateFlow
 * conflates the intermediate values.
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

    private val scheduler = TestCoroutineScheduler()
    private val mainActivityVisibility = MainActivityVisibility()
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
                mainActivityVisibility = mainActivityVisibility,
                dispatcher = StandardTestDispatcher(scheduler),
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

    /** Processes the collector and any in-flight lookup work for the current state. */
    private fun settle() {
        scheduler.runCurrent()
        scheduler.runCurrent()
    }

    @Test
    fun `incoming call posts immediately and corrects the name after the lookup`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact("Boss")

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        // First post carries no name yet (fresh process, empty cache); the
        // lookup update post carries the resolved nickname.
        assertEquals(2, notifier.shownCalls.size)
        assertEquals(identityHash to null, notifier.shownCalls[0])
        assertEquals(identityHash to "Boss", notifier.shownCalls[1])
    }

    @Test
    fun `incoming call falls back to announce peer name when no nickname`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(identityHash to "Alice", notifier.shownCalls.last())
    }

    @Test
    fun `incoming call keeps null name for unknown caller`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns null

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(identityHash, notifier.shownCalls.last().first)
        assertNull(notifier.shownCalls.last().second)
    }

    @Test
    fun `lookup failure fails open to null name`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } throws
            RuntimeException("db exploded")

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(identityHash, notifier.shownCalls.last().first)
        assertNull(notifier.shownCalls.last().second)
    }

    @Test
    fun `transition to Active cancels the incoming notification`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        callState.value = CallState.Active(identityHash)
        scheduler.runCurrent()

        // Posted immediately, name corrected by the lookup, then cancelled for
        // the Active transition (plus one cancel for the initial Idle on start).
        assertEquals(identityHash to "Alice", notifier.shownCalls.last())
        assertEquals(2, notifier.cancelCount.get())
    }

    @Test
    fun `outgoing states never post an incoming notification`() {
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Connecting(identityHash)
        scheduler.runCurrent()
        callState.value = CallState.Ringing(identityHash)
        scheduler.runCurrent()

        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `superseded lookup does not post after the call ended`() {
        // The call is answered while the caller-name lookup is still in flight:
        // the stale lookup result must not update or resurrect the notification
        // after the call is over.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        // The immediate post is up; the lookup coroutine is parked at the gate.
        callState.value = CallState.Active(identityHash)
        scheduler.runCurrent()
        lookupGate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(1, notifier.shownCalls.size)
        assertEquals(identityHash to null, notifier.shownCalls[0])
        // Canceled once for the initial Idle on start, once for the Active
        // transition that superseded the lookup.
        assertEquals(2, notifier.cancelCount.get())
    }

    @Test
    fun `consecutive same-identity call hidden by state conflation is still presented`() {
        // Call 1 arrives and its lookup is in flight. Call 1 is then answered and
        // a second call from the SAME identity comes in before the collector can
        // process the intermediate states: the whole round trip collapses into
        // the collector's suspension, and the second Incoming equals the one it
        // already delivered, so the StateFlow conflates it away and the collector
        // never sees the new call (verified: a collector suspended across a
        // V -> X -> V round trip observes nothing). Only the first lookup's
        // update post can present the second call - and it must, with the
        // resolved name for that identity.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        // The churn happens while the collector is suspended; no scheduler
        // advance in between, so the collector only ever sees the final value.
        callState.value = CallState.Active(identityHash)
        callState.value = CallState.Idle
        callState.value = CallState.Incoming(identityHash)

        lookupGate.complete(Unit)
        scheduler.runCurrent()

        // The immediate post plus the update post that presents the second call.
        assertEquals(2, notifier.shownCalls.size)
        assertEquals(identityHash to null, notifier.shownCalls[0])
        assertEquals(identityHash to "Alice", notifier.shownCalls[1])
        // The collector never saw the intermediate states (conflated away): only
        // the initial Idle on start produced a cancel.
        assertEquals(1, notifier.cancelCount.get())
    }

    @Test
    fun `consecutive same-identity call with visible transitions is re-presented`() {
        // Same scenario with the collector keeping up: it sees every state and
        // delivers the second Incoming as its own event. Both the collector's
        // immediate post and the lookup update posts must land for the new call.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        callState.value = CallState.Active(identityHash)
        scheduler.runCurrent()
        callState.value = CallState.Idle
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        scheduler.runCurrent()
        lookupGate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(identityHash to "Alice", notifier.shownCalls.last())
        // Initial Idle, Active, and Idle cancels.
        assertEquals(3, notifier.cancelCount.get())
    }

    @Test
    fun `incoming call is not posted while MainActivity is visible`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        // The app is in the foreground: MainActivity shows the in-app call
        // screen, so the background presenter must stay silent.
        mainActivityVisibility.setVisible(true)
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `name update does not resurrect the notification after MainActivity took over`() {
        // The call arrives while backgrounded (immediate post goes out). The
        // user then opens the app while the lookup is in flight: MainActivity
        // takes ownership and cancels the notification. The lookup's update
        // must not post it back.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        // The user opens the app: MainActivity becomes visible (its in-app
        // effect cancels the notification).
        mainActivityVisibility.setVisible(true)
        lookupGate.complete(Unit)
        scheduler.runCurrent()

        // Only the immediate post happened; the update was suppressed.
        assertEquals(1, notifier.shownCalls.size)
        assertEquals(identityHash to null, notifier.shownCalls[0])
    }

    @Test
    fun `name update is posted for the FSI activity flow`() {
        // The full-screen intent launches IncomingCallActivity, which does not
        // make MainActivity visible: the name update must still land so the
        // call UI shows the resolved name, not the hash fallback.
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(2, notifier.shownCalls.size)
        assertEquals(identityHash to null, notifier.shownCalls[0])
        assertEquals(identityHash to "Alice", notifier.shownCalls[1])
    }

    @Test
    fun `start is idempotent`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        // One collector: exactly the immediate post and the lookup update post.
        assertEquals(2, notifier.shownCalls.size)
        assertEquals(identityHash to "Alice", notifier.shownCalls[1])
    }

    @Test
    fun `resolveCallerName returns null when no announce exists`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns null

        assertNull(runBlocking { presenter.resolveCallerName(identityHash) })
    }
}
