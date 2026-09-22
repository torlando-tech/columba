package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelephony
import network.columba.app.rns.ipc.callback.IRnsBoolEventCallback
import network.columba.app.rns.ipc.callback.IRnsCallStateCallback
import network.columba.app.rns.ipc.callback.IRnsNullableStringEventCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * UI-side adapter that translates the Kotlin [RnsTelephony] surface into
 * `oneway` [IRnsTelephony] AIDL calls. Each suspend method is wrapped in
 * [suspendCancellableCoroutine] (via [awaitResult]) so the call shape on the
 * UI matches what the legacy in-process implementation exposed.
 *
 * Observable StateFlow surfaces (callState/remoteIdentity/isMuted/isSpeakerOn/
 * isPttMode/isPttActive) are bridged via the snapshot+observer pattern from
 * [ClientRnsCore.networkStatus]: an init-time observer registration seeds an
 * internal [MutableStateFlow] from host emissions; a snapshot fetch races
 * the first observer emission for fast initial value availability.
 */
internal class ClientRnsTelephony(
    private val remote: IRnsTelephony,
    private val scope: CoroutineScope,
) : RnsTelephony {
    // ==================== Observable surface ====================

    private val callStateState = MutableStateFlow<CallState>(CallState.Idle)
    private val remoteIdentityState = MutableStateFlow<String?>(null)
    private val isMutedState = MutableStateFlow(false)
    private val isSpeakerOnState = MutableStateFlow(false)
    private val isPttModeState = MutableStateFlow(false)
    private val isPttActiveState = MutableStateFlow(false)
    private val activeProfileState = MutableStateFlow<String?>(null)
    private val callModeState = MutableStateFlow<String?>(null)

    // The init snapshot races the activeProfile observer's first emission.
    // The profile can transition to null when the call ends, so a snapshot
    // captured mid-call must not restore a stale non-null profile after the
    // observer has already reported the newer value. Set to true on the
    // observer's first emission; the snapshot only assigns while this is
    // still false (observer always wins once it has fired).
    @Volatile
    private var activeProfileObserverStarted = false

    // Same snapshot-vs-observer race for callMode (transitions to null on
    // call-end). Set to true on the callMode observer's first emission; the
    // snapshot only assigns while this is still false.
    @Volatile
    private var callModeObserverStarted = false

    init {
        // CallState observer + snapshot.
        callbackFlow<CallState> {
            val cb = object : IRnsCallStateCallback.Stub() {
                override fun onState(state: CallState?) { if (state != null) trySend(state) }
            }
            if (!registerObserverOrClose { remote.registerCallStateObserver(cb) }) return@callbackFlow
            awaitClose { runCatching { remote.unregisterCallStateObserver(cb) } }
        }.onEach { callStateState.value = it }.launchIn(scope)

        // Bool observers: isMuted, isSpeakerOn, isPttMode, isPttActive.
        registerBoolObserver(isMutedState, remote::registerIsMutedObserver, remote::unregisterIsMutedObserver)
        registerBoolObserver(isSpeakerOnState, remote::registerIsSpeakerOnObserver, remote::unregisterIsSpeakerOnObserver)
        registerBoolObserver(isPttModeState, remote::registerIsPttModeObserver, remote::unregisterIsPttModeObserver)
        registerBoolObserver(isPttActiveState, remote::registerIsPttActiveObserver, remote::unregisterIsPttActiveObserver)

        // Nullable-string observer: remoteIdentity.
        callbackFlow<String?> {
            val cb = object : IRnsNullableStringEventCallback.Stub() {
                override fun onString(value: String?) { trySend(value) }
            }
            if (!registerObserverOrClose { remote.registerRemoteIdentityObserver(cb) }) return@callbackFlow
            awaitClose { runCatching { remote.unregisterRemoteIdentityObserver(cb) } }
        }.onEach { remoteIdentityState.value = it }.launchIn(scope)

        // Nullable-string observer: activeProfile (null when no call active).
        callbackFlow<String?> {
            val cb = object : IRnsNullableStringEventCallback.Stub() {
                override fun onString(value: String?) { trySend(value) }
            }
            if (!registerObserverOrClose { remote.registerActiveProfileObserver(cb) }) return@callbackFlow
            awaitClose { runCatching { remote.unregisterActiveProfileObserver(cb) } }
        }.onEach {
            activeProfileObserverStarted = true
            activeProfileState.value = it
        }.launchIn(scope)

        // Nullable-string observer: callMode (null when no call active).
        callbackFlow<String?> {
            val cb = object : IRnsNullableStringEventCallback.Stub() {
                override fun onString(value: String?) { trySend(value) }
            }
            if (!registerObserverOrClose { remote.registerCallModeObserver(cb) }) return@callbackFlow
            awaitClose { runCatching { remote.unregisterCallModeObserver(cb) } }
        }.onEach {
            callModeObserverStarted = true
            callModeState.value = it
        }.launchIn(scope)

        // Snapshot fetches race the observers' first emissions. Observer wins
        // on contention; snapshot just covers the case where the observer
        // hasn't fired yet.
        scope.launch {
            runCatching {
                awaitCallState { cb -> remote.getCurrentCallState(cb) }
                    ?.let { callStateState.value = it }
            }
            runCatching {
                awaitNullableStringEvent { cb -> remote.getCurrentRemoteIdentity(cb) }
                    ?.let { remoteIdentityState.value = it }
            }
            isMutedState.value = runCatching { awaitBoolEvent { cb -> remote.getCurrentIsMuted(cb) } }.getOrDefault(false)
            isSpeakerOnState.value = runCatching { awaitBoolEvent { cb -> remote.getCurrentIsSpeakerOn(cb) } }.getOrDefault(false)
            isPttModeState.value = runCatching { awaitBoolEvent { cb -> remote.getCurrentIsPttMode(cb) } }.getOrDefault(false)
            isPttActiveState.value = runCatching { awaitBoolEvent { cb -> remote.getCurrentIsPttActive(cb) } }.getOrDefault(false)
            // Snapshot only seeds the initial value while the observer has not
            // yet emitted; once the observer has fired it is authoritative (the
            // profile can transition to null on call-end, so a late snapshot
            // must not restore a stale non-null profile).
            if (!activeProfileObserverStarted) {
                val snapshot =
                    runCatching { awaitNullableStringEvent { cb -> remote.getCurrentActiveProfile(cb) } }
                        .getOrNull()
                // Re-check after the async fetch: the observer may have started
                // while the binder call was in flight, in which case it is now
                // authoritative and must win.
                if (!activeProfileObserverStarted) {
                    snapshot?.let { activeProfileState.value = it }
                }
            }
            // callMode snapshot: same observer-started guard (the mode can
            // transition to null on call-end, so a late snapshot must not
            // restore a stale non-null mode).
            if (!callModeObserverStarted) {
                val snapshot =
                    runCatching { awaitNullableStringEvent { cb -> remote.getCurrentCallMode(cb) } }
                        .getOrNull()
                if (!callModeObserverStarted) {
                    snapshot?.let { callModeState.value = it }
                }
            }
        }
    }

    override val callState: StateFlow<CallState> get() = callStateState.asStateFlow()
    override val remoteIdentity: StateFlow<String?> get() = remoteIdentityState.asStateFlow()
    override val isMuted: StateFlow<Boolean> get() = isMutedState.asStateFlow()
    override val isSpeakerOn: StateFlow<Boolean> get() = isSpeakerOnState.asStateFlow()
    override val isPttMode: StateFlow<Boolean> get() = isPttModeState.asStateFlow()
    override val isPttActive: StateFlow<Boolean> get() = isPttActiveState.asStateFlow()
    override val activeProfile: StateFlow<String?> get() = activeProfileState.asStateFlow()
    override val callMode: StateFlow<String?> get() = callModeState.asStateFlow()

    private fun registerBoolObserver(
        state: MutableStateFlow<Boolean>,
        register: (IRnsBoolEventCallback) -> Unit,
        unregister: (IRnsBoolEventCallback) -> Unit,
    ) {
        callbackFlow<Boolean> {
            val cb = object : IRnsBoolEventCallback.Stub() {
                override fun onBool(value: Boolean) { trySend(value) }
            }
            if (!registerObserverOrClose { register(cb) }) return@callbackFlow
            awaitClose { runCatching { unregister(cb) } }
        }.onEach { state.value = it }.launchIn(scope)
    }

    // ==================== Call control (IPC actions) ====================

    override suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int?,
    ): Result<Unit> = runCatching {
        awaitResult { cb ->
            remote.initiateCall(
                destinationHash,
                profileCode ?: 0,
                profileCode != null,
                cb,
            )
        }
        Unit
    }

    override suspend fun answerCall(): Result<Unit> = runCatching {
        awaitResult { cb -> remote.answerCall(cb) }
        Unit
    }

    override suspend fun hangupCall() {
        awaitResult { cb -> remote.hangupCall(cb) }
    }

    override suspend fun declineCall() {
        awaitResult { cb -> remote.declineCall(cb) }
    }

    override suspend fun setCallMuted(muted: Boolean) {
        awaitResult { cb -> remote.setCallMuted(muted, cb) }
    }

    override suspend fun setCallSpeaker(speakerOn: Boolean) {
        awaitResult { cb -> remote.setCallSpeaker(speakerOn, cb) }
    }

    override suspend fun cycleCallProfile() {
        awaitResult { cb -> remote.cycleCallProfile(cb) }
    }

    override suspend fun cycleCallMode() {
        awaitResult { cb -> remote.cycleCallMode(cb) }
    }

    override suspend fun setPttActive(active: Boolean) {
        awaitResult { cb -> remote.setPttActive(active, cb) }
    }

    override suspend fun getCallState(): Result<VoiceCallState> = runCatching {
        val bundle = awaitResult { cb -> remote.getCallState(cb) }
        bundle.classLoader = VoiceCallState::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<VoiceCallState>(BundleKeys.CALL_STATE)
            ?: throw RnsException(RnsError.Generic("getCallState payload missing 'state'", null))
    }

    // ==================== Local-state mutators ====================

    override suspend fun setConnecting(destinationHash: String) {
        awaitResult { cb -> remote.setConnecting(destinationHash, cb) }
    }

    override suspend fun setEnded() {
        awaitResult { cb -> remote.setEnded(cb) }
    }

    override suspend fun setMutedLocally(muted: Boolean) {
        awaitResult { cb -> remote.setMutedLocally(muted, cb) }
    }

    override suspend fun setSpeakerLocally(enabled: Boolean) {
        awaitResult { cb -> remote.setSpeakerLocally(enabled, cb) }
    }

    override suspend fun setPttModeLocally(enabled: Boolean) {
        awaitResult { cb -> remote.setPttModeLocally(enabled, cb) }
    }

    override suspend fun setPttActiveLocally(active: Boolean) {
        awaitResult { cb -> remote.setPttActiveLocally(active, cb) }
    }

    override suspend fun setIncomingEnabled(enabled: Boolean) {
        awaitResult { cb -> remote.setIncomingEnabled(enabled, cb) }
    }
}

/** Snapshot read for the [CallState] StateFlow. */
private suspend inline fun awaitCallState(
    crossinline call: (IRnsCallStateCallback) -> Unit,
): CallState? = suspendCancellableCoroutine { cont ->
    val delivered = AtomicBoolean(false)
    val cb = object : IRnsCallStateCallback.Stub() {
        override fun onState(state: CallState?) {
            if (delivered.compareAndSet(false, true)) cont.resume(state)
        }
    }
    try {
        call(cb)
    } catch (e: android.os.RemoteException) {
        if (delivered.compareAndSet(false, true)) cont.resume(null)
    }
}

/** Snapshot read for a Boolean StateFlow. */
private suspend inline fun awaitBoolEvent(
    crossinline call: (IRnsBoolEventCallback) -> Unit,
): Boolean = suspendCancellableCoroutine { cont ->
    val delivered = AtomicBoolean(false)
    val cb = object : IRnsBoolEventCallback.Stub() {
        override fun onBool(value: Boolean) {
            if (delivered.compareAndSet(false, true)) cont.resume(value)
        }
    }
    try {
        call(cb)
    } catch (e: android.os.RemoteException) {
        if (delivered.compareAndSet(false, true)) cont.resume(false)
    }
}

/** Snapshot read for a nullable-String StateFlow (IRnsNullableStringEventCallback variant). */
private suspend inline fun awaitNullableStringEvent(
    crossinline call: (IRnsNullableStringEventCallback) -> Unit,
): String? = suspendCancellableCoroutine { cont ->
    val delivered = AtomicBoolean(false)
    val cb = object : IRnsNullableStringEventCallback.Stub() {
        override fun onString(value: String?) {
            if (delivered.compareAndSet(false, true)) cont.resume(value)
        }
    }
    try {
        call(cb)
    } catch (e: android.os.RemoteException) {
        if (delivered.compareAndSet(false, true)) cont.resume(null)
    }
}
