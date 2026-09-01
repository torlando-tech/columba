package network.columba.app.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import network.columba.app.MainActivityVisibility
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
 * that owns presentation when no app UI is visible: on [CallState.Incoming] it
 * resolves the caller display name and posts the high-importance category-call
 * notification with a full-screen intent (see
 * [IncomingCallNotifier.showIncomingCallNotification]), which brings
 * [network.columba.app.IncomingCallActivity] over the lock screen or over whatever
 * app is in the foreground. Any other state cancels the notification.
 *
 * The presenter is the single writer of the incoming-call notification: one post
 * per incoming call, made after the caller-name lookup (a repository read of a few
 * milliseconds, which keeps the full-screen UI's name accurate without delaying
 * takeover by more than a lookup). There is deliberately no asynchronous
 * "name correction" post: a second, out-of-band writer would have to coordinate
 * with every component that cancels the notification (MainActivity, the call
 * screen, user actions) and could resurrect a dismissed notification.
 *
 * Ownership with the foreground UI: while MainActivity is visible it presents the
 * call in-app, so the presenter checks [MainActivityVisibility] before the lookup
 * (skip work the main UI will do) and posts through
 * [MainActivityVisibility.postWhileBackground] after it: the flag check and the
 * post are one locked section, atomic against MainActivity's claim (flag flip plus
 * its cancel, also one locked section). Either the claim wins (this post is
 * skipped) or the post wins (the claim's cancel removes it); no interleaving
 * leaves a background post outliving the foreground takeover.
 *
 * Known, accepted residual: if the call is answered and the same peer calls again
 * while the name lookup is still in flight, the post carries that lookup's result
 * for the new call. The display name is a function of the identity hash (the same
 * database rows), so the presented name is the right name for the ringing call.
 */
@Singleton
class IncomingCallPresenter internal constructor(
    private val rnsTelephony: RnsTelephony,
    private val announceRepository: AnnounceRepository,
    private val contactRepository: ContactRepository,
    private val incomingCallNotifier: IncomingCallNotifier,
    private val mainActivityVisibility: MainActivityVisibility,
    dispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "IncomingCallPresenter"
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var isStarted = false

    @Inject
    constructor(
        rnsTelephony: RnsTelephony,
        announceRepository: AnnounceRepository,
        contactRepository: ContactRepository,
        incomingCallNotifier: IncomingCallNotifier,
        mainActivityVisibility: MainActivityVisibility,
    ) : this(
        rnsTelephony = rnsTelephony,
        announceRepository = announceRepository,
        contactRepository = contactRepository,
        incomingCallNotifier = incomingCallNotifier,
        mainActivityVisibility = mainActivityVisibility,
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
        scope.launch {
            rnsTelephony.callState.collect { state ->
                when (state) {
                    is CallState.Incoming -> presentIncomingCall(state.identityHash)
                    else -> incomingCallNotifier.cancelIncomingCallNotification()
                }
            }
        }
    }

    private suspend fun presentIncomingCall(identityHash: String) {
        // While the main UI is visible it presents the call in-app; skip the
        // lookup the main UI will do for the user.
        if (mainActivityVisibility.visible.value) {
            Log.i(TAG, "MainActivity is presenting the call; skipping background presentation")
            return
        }
        Log.i(TAG, "Presenting background incoming-call UI for ${identityHash.take(16)}...")
        val callerName = resolveCallerName(identityHash)
        // The lookup suspended, so the call may have changed state (answered,
        // ended, or a different caller) or the main UI may have taken over
        // (posting now would undo its cancel). Re-read the state directly
        // before the single post; the visibility check happens inside the
        // atomic post below.
        val current = rnsTelephony.callState.value
        if (current !is CallState.Incoming || current.identityHash != identityHash) {
            Log.i(TAG, "Call state changed during caller lookup; skipping stale presentation")
            return
        }
        // Atomic against MainActivity's claim: skipped if the foreground took
        // over during the lookup, removed by the claim's cancel if it takes
        // over in the same instant as this post.
        mainActivityVisibility.postWhileBackground {
            incomingCallNotifier.showIncomingCallNotification(identityHash, callerName)
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
