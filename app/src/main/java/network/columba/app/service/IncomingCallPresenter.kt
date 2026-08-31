package network.columba.app.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.notifications.IncomingCallNotifier
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.host.util.PeerNameResolver

/**
 * Process-lifetime presenter for background incoming-call presentation in the main
 * app process.
 *
 * Since the dual-backend architecture, the call observable surface lives on
 * [RnsTelephony] (rns-api); each backend (Python or native Kotlin) republishes
 * [CallState] into the UI process across the AIDL seam. The ringing itself is owned
 * by the :reticulum service process, so this presenter is the app-process consumer
 * that owns presentation when no app UI is visible: on [CallState.Incoming] it posts
 * the high-importance category-call notification with a full-screen intent (see
 * [IncomingCallNotifier.showIncomingCallNotification]), which brings
 * [network.columba.app.IncomingCallActivity] over the lock screen or over whatever
 * app is in the foreground.
 *
 * Foreground presentation (the in-app incoming call screen) remains MainActivity's
 * concern; it cancels this notification as soon as it takes over, so the two paths
 * never double up.
 */
@Singleton
class IncomingCallPresenter internal constructor(
    private val rnsTelephony: RnsTelephony,
    private val announceRepository: AnnounceRepository,
    private val contactRepository: ContactRepository,
    private val incomingCallNotifier: IncomingCallNotifier,
    dispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "IncomingCallPresenter"
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Counts every observed call-state transition. A suspended caller-name lookup
     * snapshots this before and after the lookup: if it advanced, the call moved
     * on while the lookup was in flight - including a consecutive call from the
     * same identity, whose equal [CallState.Incoming] value StateFlow would
     * conflate with the original one.
     */
    private val stateSequence = AtomicLong(0)

    @Volatile
    private var isStarted = false

    @Inject
    constructor(
        rnsTelephony: RnsTelephony,
        announceRepository: AnnounceRepository,
        contactRepository: ContactRepository,
        incomingCallNotifier: IncomingCallNotifier,
    ) : this(
        rnsTelephony = rnsTelephony,
        announceRepository = announceRepository,
        contactRepository = contactRepository,
        incomingCallNotifier = incomingCallNotifier,
        dispatcher = Dispatchers.IO,
    )

    /**
     * Start the call-state collector. Safe to call multiple times; the collector
     * runs for the life of the process (no stop: Hilt singletons have no destroy
     * hook and the process dies with the collector).
     */
    fun start() {
        if (isStarted) {
            Log.w(TAG, "start() called twice; ignoring")
            return
        }
        isStarted = true
        // Fast transition counter: its collection body is O(1), so it observes
        // every state change, including the ones that happen while a suspended
        // lookup spans them.
        scope.launch {
            rnsTelephony.callState.collect { stateSequence.incrementAndGet() }
        }
        scope.launch {
            rnsTelephony.callState.collect { state ->
                when (state) {
                    is CallState.Incoming -> presentIncomingCall(state.identityHash)
                    else -> incomingCallNotifier.cancelIncomingCallNotification()
                }
            }
        }
    }

    private suspend fun presentIncomingCall(initialHash: String) {
        Log.i(TAG, "Presenting background incoming-call UI for ${initialHash.take(16)}...")
        var identityHash = initialHash
        while (true) {
            val sequenceAtLookupStart = stateSequence.get()
            val callerName = resolveCallerName(identityHash)
            // The caller-name lookup suspends, so the call may have changed state
            // while it was in flight. Re-check the current state (read directly,
            // so the check sees the latest value) and the transition counter
            // before posting an obsolete lookup result.
            val current = rnsTelephony.callState.value
            when {
                current !is CallState.Incoming -> {
                    // Answered or ended: the next state event performs the normal
                    // cancel.
                    Log.i(
                        TAG,
                        "Incoming call ended before post (now ${current::class.simpleName}); skipping stale presentation",
                    )
                    return
                }
                current.identityHash != identityHash -> {
                    // A different caller is now ringing: the collector presents
                    // that call from its own state event.
                    Log.i(TAG, "Caller changed before post; the new call is presented from its own state")
                    return
                }
                stateSequence.get() == sequenceAtLookupStart -> {
                    // No transition since the lookup started: still the same call.
                    incomingCallNotifier.showIncomingCallNotification(current.identityHash, callerName)
                    return
                }
                else -> {
                    // Transitions happened during the lookup and the state is back
                    // to an incoming call: a consecutive call from the same
                    // identity. StateFlow conflates the two equal Incoming values,
                    // so the collector will not deliver the second one as a new
                    // event - present the current call with a fresh lookup instead.
                    Log.i(TAG, "Consecutive call from the same identity; re-resolving caller name")
                    identityHash = current.identityHash
                }
            }
        }
    }

    /**
     * Resolve the caller's display name from the identity hash carried by
     * [CallState.Incoming]:
     * 1. Contact custom nickname (via the announce's destination hash)
     * 2. Announce peer name
     * 3. null - the notification helper falls back to a formatted hash
     */
    internal suspend fun resolveCallerName(identityHash: String): String? =
        runCatching {
            val announce =
                announceRepository.findByIdentityHash(identityHash) ?: return@runCatching null
            val nickname =
                contactRepository.getContact(announce.destinationHash)?.customNickname
            when {
                PeerNameResolver.isValidPeerName(nickname) -> nickname
                PeerNameResolver.isValidPeerName(announce.peerName) -> announce.peerName
                else -> null
            }
        }
            .onFailure { Log.w(TAG, "Caller name lookup failed: ${it.message}") }
            .getOrNull()
}
