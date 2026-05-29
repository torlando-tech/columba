package network.columba.app.rns.ipc.server

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side helper that fans a single upstream [Flow] out to many AIDL
 * observer callbacks with lifecycle and `linkToDeath` cleanup wired in.
 *
 * Lazy collection: the upstream collector is started on first
 * [registerObserver] and cancelled when the last observer unregisters or dies.
 * Eager collection would waste CPU/IPC when no UI is listening (e.g. the
 * device is asleep but the host process is still alive).
 *
 * Observers are keyed by their [IBinder] identity so `register/unregister`
 * pairs cleanly across the AIDL boundary, where each `IRnsXCallback` proxy
 * the client passes in is a fresh AIDL-generated wrapper around the same
 * remote binder.
 *
 * Thread-safety: all observer-set mutations and collector lifecycle
 * transitions go through [stateLock]. The emission path snapshots the
 * observer collection before iterating so a concurrent unregister doesn't
 * CME mid-broadcast.
 *
 * @param T the upstream Flow element type
 * @param C the AIDL callback type
 */
internal class ObserverHub<T, C : Any>(
    private val scope: CoroutineScope,
    private val upstream: () -> Flow<T>,
    private val callbackBinder: (C) -> IBinder,
    private val emit: (C, T) -> Unit,
) {
    private val observers = ConcurrentHashMap<IBinder, C>()
    private val deathRecipients = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()
    private var collectorJob: Job? = null
    private val stateLock = Any()

    private companion object {
        const val TAG = "ObserverHub"
    }

    fun registerObserver(cb: C) {
        val binder = callbackBinder(cb)
        observers[binder] = cb
        hookDeath(binder)
        synchronized(stateLock) {
            if (collectorJob?.isActive != true) {
                collectorJob = scope.launch {
                    upstream().collect { value ->
                        // Snapshot to avoid CME if a concurrent unregister fires.
                        for ((binder, observer) in observers.entries.toList()) {
                            try {
                                emit(observer, value)
                            } catch (e: DeadObjectException) {
                                // The client process is genuinely gone. Clean up
                                // now in case the linkToDeath recipient hasn't
                                // fired yet.
                                Log.d(TAG, "Observer client is dead; detaching", e)
                                detach(binder)
                            } catch (e: TransactionTooLargeException) {
                                // This ONE payload overflowed the Binder buffer;
                                // the client is alive and still subscribed.
                                // Detaching here would silently kill ALL future
                                // delivery for the session — the client never
                                // learns it was dropped and never re-registers
                                // (it subscribes once via a callbackFlow whose
                                // awaitClose only fires on cancel). That is how a
                                // single oversized inbound message used to take
                                // out every subsequent message, including small
                                // text. Skip this payload and keep the observer;
                                // large payloads must cross out-of-band (see
                                // AttachmentBlob), not inline.
                                Log.e(
                                    TAG,
                                    "Observer payload exceeded the Binder transaction " +
                                        "limit; dropping this message but keeping the " +
                                        "observer subscribed",
                                    e,
                                )
                            } catch (e: RemoteException) {
                                // Other transient remote failures: keep the
                                // observer (genuine death is handled by the
                                // linkToDeath recipient) so one bad emission can't
                                // silently end the stream.
                                Log.w(TAG, "Observer emit failed transiently; keeping observer", e)
                            }
                        }
                    }
                }
            }
        }
    }

    fun unregisterObserver(cb: C) {
        detach(callbackBinder(cb))
    }

    private fun detach(binder: IBinder) {
        observers.remove(binder)
        deathRecipients.remove(binder)?.let { recipient ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        synchronized(stateLock) {
            if (observers.isEmpty()) {
                collectorJob?.cancel()
                collectorJob = null
            }
        }
    }

    private fun hookDeath(binder: IBinder) {
        if (deathRecipients.containsKey(binder)) return
        val recipient = IBinder.DeathRecipient { detach(binder) }
        runCatching { binder.linkToDeath(recipient, 0) }
            .onSuccess { deathRecipients[binder] = recipient }
    }
}
