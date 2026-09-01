package network.columba.app.service

import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import network.columba.app.AppMainThread
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [IncomingCallPresenter]: the app-process component that presents
 * incoming calls (full-screen-intent notification) while the app is backgrounded
 * (issue #1079).
 *
 * The presenter is the single writer of the notification: one post per incoming
 * call, made after the caller-name lookup. The presenter runs on a
 * [kotlinx.coroutines.test.StandardTestDispatcher] backed by a shared
 * [TestCoroutineScheduler], so each test drives it deterministically: a state
 * change is only processed when [TestCoroutineScheduler.runCurrent] advances the
 * scheduler. Grouping several state changes before a [TestCoroutineScheduler.runCurrent]
 * models the production case where the whole churn collapses into the
 * collector's suspension in the lookup and the StateFlow conflates the
 * intermediate values.
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
        // Fake the main-thread confinement seam: the test thread plays the main
        // thread, and posts run inline. Inline execution models the production
        // atomicity exactly - a claim can neither interleave inside a
        // postWhileBackground sequence nor be missed by it; each runs whole.
        AppMainThread.post = { runnable -> runnable.run() }
        AppMainThread.isMainThread = { true }
        every { rnsTelephony.callState } returns callState
        presenter =
            IncomingCallPresenter(
                rnsTelephony = rnsTelephony,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                incomingCallNotifier = notifier,
                mainActivityVisibility = mainActivityVisibility,
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler),
            )
    }

    @After
    fun tearDown() {
        AppMainThread.post = { runnable -> runnable.run() }
        AppMainThread.isMainThread = { true }
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

    private fun assertSinglePost(hash: String, name: String?) {
        assertEquals(1, notifier.shownCalls.size)
        assertEquals(hash to name, notifier.shownCalls[0])
    }

    @Test
    fun `incoming call posts notification with the resolved name after the lookup`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact("Boss")

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        // One post, after the lookup, carrying the resolved nickname.
        assertSinglePost(identityHash, "Boss")
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

        assertSinglePost(identityHash, "Alice")
    }

    @Test
    fun `incoming call keeps null name for unknown caller`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns null

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertSinglePost(identityHash, null)
    }

    @Test
    fun `lookup failure fails open to null name`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } throws
            RuntimeException("db exploded")

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertSinglePost(identityHash, null)
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
        assertSinglePost(identityHash, "Alice")

        callState.value = CallState.Active(identityHash)
        scheduler.runCurrent()

        // One cancel for the initial Idle on start, one for the Active transition.
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
    fun `incoming call is not posted while MainActivity is visible`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        // The app is in the foreground: MainActivity shows the in-app call
        // screen, so the background presenter must stay silent.
        mainActivityVisibility.claimForeground { }
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `lookup completing after MainActivity took over does not post`() {
        // The call arrives while backgrounded (the lookup starts). The user then
        // opens the app while the lookup is in flight: MainActivity takes
        // ownership and cancels (nothing is up yet, but the cancel runs
        // regardless). The post made after the lookup must not land over the
        // in-app screen or undo that cancel.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        // The collector is now parked inside the lookup.
        settle()
        assertEquals(0, notifier.shownCalls.size)

        mainActivityVisibility.claimForeground { }
        settle()

        lookupGate.complete(Unit)
        settle()

        // No post: the atomic check-then-post saw the claimed foreground.
        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `superseded lookup does not post after the call ended`() {
        // The call is answered while the caller-name lookup is still in flight:
        // the stale lookup result must not be posted after the call is over.
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
        assertEquals(0, notifier.shownCalls.size)

        // The collector is parked in the lookup; the call is answered and ends.
        callState.value = CallState.Active(identityHash)
        settle()
        callState.value = CallState.Idle
        settle()

        lookupGate.complete(Unit)
        settle()

        // No post: the re-check saw the call is no longer the incoming one, and
        // a never-posted notification needs no cancel.
        assertEquals(0, notifier.shownCalls.size)
        // One cancel for the initial Idle on start, one for the Active
        // transition the collector processes after the lookup returns.
        assertEquals(2, notifier.cancelCount.get())
    }

    @Test
    fun `same-identity re-call during the lookup is presented with the lookup result`() {
        // Call 1 arrives and its lookup is in flight. Call 1 is then answered and
        // a second call from the SAME identity comes in before the collector can
        // process the intermediate states: the whole round trip collapses into
        // the collector's suspension in the lookup, and the second Incoming
        // equals the one it already delivered, so the StateFlow conflates it
        // away and the collector never sees the new call (verified: a collector
        // suspended across a V -> X -> V round trip observes nothing). The post
        // made after the lookup is therefore the presentation of the second
        // call. The display name is a function of the identity hash, so the
        // lookup result is the right name for the ringing call.
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

        assertSinglePost(identityHash, "Alice")
        // The collector never saw the intermediate states (conflated away): only
        // the initial Idle on start produced a cancel.
        assertEquals(1, notifier.cancelCount.get())
    }

    @Test
    fun `foreground claim after the post cancels it in the same atomic section`() {
        // The normal hand-off: the presenter posts while backgrounded, then the
        // user opens the app. MainActivity's claim (flag flip + cancel in one
        // locked section) removes the post, and the presenter can never post
        // again for this call (one post per call), so the UI is single-owned.
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        assertSinglePost(identityHash, "Alice")
        val cancelsBeforeClaim = notifier.cancelCount.get()

        mainActivityVisibility.claimForeground { notifier.cancelIncomingCallNotification() }

        assertEquals(cancelsBeforeClaim + 1, notifier.cancelCount.get())
        assertEquals(1, notifier.shownCalls.size)
    }

    @Test
    fun `backgrounding mid-ring hands presentation back to the presenter`() {
        // The call arrives while the app is visible: MainActivity presents it
        // in-app and the presenter stays quiet. The user then presses home
        // while the call is still ringing - no new callState emission will
        // ever arrive - so releasing foreground ownership must make the
        // presenter post the full-screen-intent notification.
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        mainActivityVisibility.claimForeground { }
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        assertEquals(0, notifier.shownCalls.size)

        // Home gesture: onStop without a configuration change.
        mainActivityVisibility.releaseForeground()
        settle()

        assertSinglePost(identityHash, "Alice")
    }

    @Test
    fun `rotation mid-ring does not post the background notification`() {
        // A configuration change never releases ownership (MainActivity passes
        // isChangingConfigurations), so the STOP/START churn of a rotation
        // cannot flash the background notification mid-call.
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        mainActivityVisibility.claimForeground { }
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()
        assertEquals(0, notifier.shownCalls.size)

        // Rotation: onStop with isChangingConfigurations = true.
        mainActivityVisibility.releaseForeground(changingConfigurations = true)
        settle()
        assertEquals(0, notifier.shownCalls.size)
        // The claim survives the rotation.
        assertTrue(mainActivityVisibility.visible.value)
    }

    @Test
    fun `backgrounding mid-ring with the app reopened before the lookup does not post`() {
        // Same as the recovery case, but the user reopens the app while the
        // recovery's name lookup is still in flight: the claim wins and the
        // presenter's post must not land over the in-app screen.
        val lookupGate = CompletableDeferred<Unit>()
        coEvery { announceRepository.findByIdentityHash(identityHash) } coAnswers {
            lookupGate.await()
            announce("Alice")
        }
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        mainActivityVisibility.claimForeground { }
        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        // Home: recovery starts the lookup...
        mainActivityVisibility.releaseForeground()
        settle()
        assertEquals(0, notifier.shownCalls.size)

        // ...and the app is back in the foreground before it completes.
        mainActivityVisibility.claimForeground { notifier.cancelIncomingCallNotification() }
        lookupGate.complete(Unit)
        settle()

        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `backgrounding while idle posts nothing`() {
        // Release with no ringing call must not post: recovery only acts on
        // CallState.Incoming.
        mainActivityVisibility.claimForeground { }
        presenter.start()
        settle()

        mainActivityVisibility.releaseForeground()
        settle()

        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `queued post is dropped when the call ends before the main-thread runnable runs`() {
        // The post enqueues onto the main thread, so the call can end (and the
        // collector's cancel run) between the presenter's state check and the
        // runnable executing. The runnable re-checks the call state and must
        // not resurrect the cancelled notification.
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        AppMainThread.post = { runnable -> queue.add(runnable) }

        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        scheduler.runCurrent()
        callState.value = CallState.Incoming(identityHash)
        settle()

        // The lookup completed and the post is enqueued, not yet run.
        assertEquals(1, queue.size)
        assertEquals(0, notifier.shownCalls.size)

        // The call is answered and the collector processes the transition
        // (cancel) before the queued post runs.
        callState.value = CallState.Active(identityHash)
        scheduler.runCurrent()
        assertEquals(0, notifier.shownCalls.size)

        // Now the main thread drains its queue: the re-check inside the post
        // sees the call is no longer Incoming and drops it.
        while (true) {
            val runnable = queue.poll() ?: break
            runnable.run()
        }
        assertEquals(0, notifier.shownCalls.size)
    }

    @Test
    fun `start is idempotent`() {
        coEvery { announceRepository.findByIdentityHash(identityHash) } returns
            announce("Alice")
        coEvery { contactRepository.getContact(destinationHash) } returns contact(null)

        presenter.start()
        settle()
        presenter.start()
        settle()

        callState.value = CallState.Incoming(identityHash)
        settle()

        assertSinglePost(identityHash, "Alice")
    }
}
