package network.columba.app.rns.ipc

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side registry that maps opaque [Long] handle IDs to live backend
 * objects whose identity must survive across AIDL round-trips.
 *
 * Today the only such object is the backend's `Link` instance — the UI
 * receives a [network.columba.app.rns.api.model.Link] value parcelable that
 * embeds the handle ID as its `id` string. When the UI later passes the
 * `Link` back across the seam (`closeLink`, `sendOverLink`), the server uses
 * the handle to look up the live object.
 *
 * Lifetime model: process-scoped. Handles are released either explicitly
 * (`release`) when the backend signals the underlying object is gone, or
 * automatically via `linkToDeath` cleanup when the client process binder
 * dies — see [trackClient]. The registry deliberately does NOT track
 * reference counts; the host knows when an underlying object goes away
 * (e.g., RNS Link state machine reaches CLOSED) and is responsible for
 * calling [release] at that point.
 *
 * Thread-safe: backed by [ConcurrentHashMap] + [AtomicLong]. All operations
 * are O(1) under contention.
 */
internal class HandleRegistry<T : Any> {
    private val nextId = AtomicLong(1)
    private val objects = ConcurrentHashMap<Long, T>()

    /** Per-client (binder identity) handle ownership for death-cleanup. */
    private val ownership = ConcurrentHashMap<IBinder, MutableSet<Long>>()

    /**
     * Register [value] under a freshly allocated handle ID and return it.
     * The caller is responsible for either releasing the handle when the
     * underlying object goes away, or transferring ownership to a client
     * via [trackClient] so the registry can clean up on client death.
     */
    fun register(value: T): Long {
        val id = nextId.getAndIncrement()
        objects[id] = value
        return id
    }

    /** Look up the live object, or `null` if the handle is unknown or released. */
    fun lookup(id: Long): T? = objects[id]

    /** Release [id] and return the removed object, or `null` if absent. */
    fun release(id: Long): T? = objects.remove(id)

    /**
     * Attribute [handleIds] to [clientBinder]. When the binder dies, all
     * still-registered handles owned by that client are released.
     *
     * Idempotent — calling twice with the same binder adds to the set
     * rather than re-linking; the death-recipient is hooked exactly once
     * per binder.
     */
    fun trackClient(clientBinder: IBinder, handleIds: Collection<Long>) {
        if (handleIds.isEmpty()) return
        val owned = ownership.getOrPut(clientBinder) {
            // Synchronized wrapper around HashSet so concurrent register/release
            // calls from different threads don't ConcurrentModificationException
            // mid-iteration during death cleanup.
            java.util.Collections.synchronizedSet(HashSet<Long>())
                .also { hookDeath(clientBinder) }
        }
        owned.addAll(handleIds)
    }

    /** Snapshot count of live handles, primarily for tests/diagnostics. */
    fun size(): Int = objects.size

    private fun hookDeath(clientBinder: IBinder) {
        val recipient = IBinder.DeathRecipient {
            val owned = ownership.remove(clientBinder) ?: return@DeathRecipient
            // Snapshot to avoid CME if a concurrent register also touches the set.
            val snapshot = synchronized(owned) { owned.toList() }
            snapshot.forEach { release(it) }
        }
        runCatching { clientBinder.linkToDeath(recipient, 0) }
    }
}
