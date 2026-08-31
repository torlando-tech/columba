package network.columba.app.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
 * that owns presentation when no app UI is visible: on [CallState.Incoming] it posts
 * the high-importance category-call notification with a full-screen intent (see
 * [IncomingCallNotifier.showIncomingCallNotification]), which brings
 * [network.columba.app.IncomingCallActivity] over the lock screen or over whatever
 * app is in the foreground.
 *
 * The notification is posted immediately on the incoming state (with the last
 * resolved name for the identity, or the formatted-hash fallback) and the name is
 * corrected once the caller-name lookup completes. The lookup is the only
 * suspending step, and the post must not wait for it: answering should not be
 * delayed by a repository read. Because the display name is a function of the
 * identity hash, updating the post while the same identity is incoming is correct
 * even if the currently ringing call is a second call from that identity: the
 * update path is what re-presents a same-identity re-call that [StateFlow]
 * conflation would otherwise hide from the collector.
 *
 * Foreground presentation (the in-app incoming call screen) is MainActivity's
 * concern. The presenter gates both posts on [MainActivityVisibility]: while
 * MainActivity is visible it owns the presentation, so the presenter neither
 * posts (no duplicate of the in-app screen) nor updates (no resurrection of the
 * notification MainActivity cancelled when it took over).
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

    /**
     * Last resolved display name per identity. Lets a re-call from a known peer
     * show its name on the immediate post instead of the formatted-hash fallback
     * while the lookup runs.
     */
    private val resolvedNames = ConcurrentHashMap<String, String>()

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
        // While MainActivity is visible it owns the presentation (the in-app
        // call screen): posting now would duplicate it, and the name update
        // below could resurrect the notification MainActivity just cancelled.
        if (mainActivityVisibility.visible.value) {
            Log.i(TAG, "MainActivity is presenting the call; skipping background presentation")
            return
        }
        Log.i(TAG, "Presenting background incoming-call UI for ${identityHash.take(16)}...")
        // Post immediately - do not delay the full-screen takeover by the
        // caller-name lookup. The name is the cached one or null (the notifier
        // falls back to a formatted hash) and is corrected below once the lookup
        // completes.
        incomingCallNotifier.showIncomingCallNotification(identityHash, resolvedNames[identityHash])
        scope.launch {
            val callerName = resolveCallerName(identityHash)
            if (callerName != null) {
                resolvedNames[identityHash] = callerName
            }
            // The lookup suspended, so the call may have changed state while it
            // was in flight. Update the post only while the same incoming call is
            // still ringing; any other state is handled by the collector (the
            // cancel, or the new call's own presentation). A same-identity re-call
            // conflates with this Incoming value in the StateFlow, so the
            // collector never re-delivers it - this update is what presents the
            // current call, and the display name is a function of the identity,
            // so it is the right name for it.
            val current = rnsTelephony.callState.value
            if (
                current is CallState.Incoming &&
                current.identityHash == identityHash &&
                // MainActivity may have taken ownership while the lookup was in
                // flight; its cancel must not be undone by the name update.
                !mainActivityVisibility.visible.value
            ) {
                incomingCallNotifier.showIncomingCallNotification(identityHash, callerName)
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
