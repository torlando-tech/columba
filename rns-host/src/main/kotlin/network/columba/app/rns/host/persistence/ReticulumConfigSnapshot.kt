package network.columba.app.rns.host.persistence

import android.content.Context
import android.os.Parcel
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import network.columba.app.rns.api.model.ReticulumConfig

/**
 * Persists a sanitized [ReticulumConfig] to the app's `filesDir` so the
 * `:reticulum` foreground service can self-initialize the RNS stack after an
 * OOM/force-stop restart (when `BoundRnsBackend` in the UI process isn't
 * around to call `initialize(config)`).
 *
 * Snapshot contents are EVERY field of [ReticulumConfig] **except**
 * [ReticulumConfig.deliveryIdentityKey] — plus the hex hash of the active
 * identity so the reader can look up the encrypted key in Room and decrypt
 * via the Keystore-backed `IdentityKeyProvider`. Keeping the plaintext
 * private key out of on-disk state preserves the Keystore-migration
 * guarantee that the only place the 64-byte key ever lives is in-memory
 * after a successful decrypt.
 *
 * Wire format: Android [Parcel] bytes. We already trust Parcel for the
 * AIDL boundary (UI ↔ `:reticulum`), and [ReticulumConfig] is `@Parcelize`,
 * so reusing the same marshalling shape avoids introducing a second
 * serialization scheme. Versioned with a leading int so a format change can
 * fall back to "no snapshot" instead of crashing on stale bytes.
 *
 * File layout (`<filesDir>/rns_config_snapshot.bin`):
 * ```
 * int     version   (= [VERSION])
 * String  identityHashHex  (or empty string for the identity-less case)
 * Parcel  ReticulumConfig  (with deliveryIdentityKey forced to null)
 * ```
 */
object ReticulumConfigSnapshot {
    private const val TAG = "ReticulumConfigSnapshot"
    private const val FILE_NAME = "rns_config_snapshot.bin"
    private const val LOCK_FILE_NAME = "$FILE_NAME.lock"
    // Bumped to 4 when ReticulumConfig.autoconnectInterfaceMode was added.
    // The field sits mid-layout (before interfaceDiscoverySources /
    // requiredDiscoveryValue / incomingMessageSizeLimitKb), so V3 snapshots
    // predate it and would unmarshal with a torn parcel layout from this
    // version onward. We explicitly discard them; the UI re-initialises on
    // next launch and writes V4. (V3 bumped for incomingMessageSizeLimitKb;
    // V2 -> V1 discard for shareInstanceHosting: see git history of this
    // constant.)
    private const val VERSION = 4

    /**
     * The deserialized snapshot: a config without an identity key plus the
     * hex hash of the identity to look up.
     */
    data class Snapshot(
        val configWithoutKey: ReticulumConfig,
        val identityHashHex: String?,
    )

    private fun snapshotFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * Decodes the [ReticulumConfig] payload. Swappable only so tests can force a
     * [LinkageError] (the COLUMBA-AT failure mode) on the decode path.
     */
    @Suppress("DEPRECATION")
    internal var configDecoder: (Parcel) -> ReticulumConfig? = { parcel ->
        // readParcelable(ClassLoader) works across the whole minSdk-24 fleet. The typed
        // (ClassLoader, Class) overload is API 33+ and throws NoSuchMethodError below it.
        // (Mirrors ReticulumConfig.kt / LinkEvent.kt.)
        parcel.readParcelable<ReticulumConfig>(ReticulumConfig::class.java.classLoader)
    }

    /**
     * Runs [block] while holding an exclusive lock on the snapshot, shared by the UI
     * process (writer) and `:reticulum` (reader). This makes "read bytes -> decode fails ->
     * delete file" atomic with respect to writes, so cleanup can never delete a fresh
     * snapshot that a concurrent [write] just put in place. `synchronized` covers
     * threads in the same process, because a JVM can't hold two overlapping
     * [java.nio.channels.FileLock]s.
     */
    private fun <T> withSnapshotLock(context: Context, block: () -> T): T =
        synchronized(this) {
            RandomAccessFile(File(context.filesDir, LOCK_FILE_NAME), "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }

    /**
     * Write [config] (with [ReticulumConfig.deliveryIdentityKey] stripped) and
     * [identityHashHex] to the snapshot file. Best-effort: a write failure is
     * logged and swallowed — the next successful write overwrites cleanly,
     * and a missing snapshot just means `:reticulum` waits for UI to drive
     * initialize.
     */
    fun write(
        context: Context,
        config: ReticulumConfig,
        identityHashHex: String?,
    ) {
        try {
            withSnapshotLock(context) { writeLocked(context, config, identityHashHex) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write config snapshot: ${e.message}", e)
        }
    }

    private fun writeLocked(
        context: Context,
        config: ReticulumConfig,
        identityHashHex: String?,
    ) {
        val sanitized = config.copy(deliveryIdentityKey = null)
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(VERSION)
            parcel.writeString(identityHashHex.orEmpty())
            // writeParcelable writes the class name + delegates to writeToParcel.
            // The reader uses readParcelable to symmetrically decode (and reuse the
            // CREATOR for InterfaceConfig list rehydration). Raw writeToParcel
            // would omit the class name, breaking readParcelable's classloader lookup.
            parcel.writeParcelable(sanitized, 0)
            val bytes = parcel.marshall()
            // Write to a temp file first then rename so a crash mid-write
            // can't leave a half-marshalled file the reader will choke on.
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(snapshotFile(context))) {
                // Fallback for filesystems where atomic rename across
                // existing target fails — overwrite the destination directly.
                snapshotFile(context).writeBytes(bytes)
                tmp.delete()
            }
            Log.d(
                TAG,
                "Wrote snapshot: ${bytes.size} bytes, ${config.enabledInterfaces.size} interfaces, " +
                    "identity=${identityHashHex?.take(8)}…",
            )
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Read the snapshot from `filesDir`. Returns null if the file is absent,
     * the version is unrecognised, or the bytes fail to unmarshal — all of
     * which are non-fatal (the caller falls back to "wait for UI to drive
     * initialize").
     */
    fun read(context: Context): Snapshot? =
        try {
            withSnapshotLock(context) { readLocked(context) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to lock/read snapshot: ${e.message}", e)
            null
        }

    /** Caller must hold [withSnapshotLock]. */
    private fun readLocked(context: Context): Snapshot? {
        val file = snapshotFile(context)
        if (!file.exists()) {
            Log.d(TAG, "No snapshot at ${file.absolutePath} — first run or never initialized")
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read snapshot file: ${e.message}", e)
            return null
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val version = parcel.readInt()
            if (version != VERSION) {
                Log.w(TAG, "Snapshot version $version != expected $VERSION — discarding")
                return null
            }
            val identityHashHex = parcel.readString().orEmpty().takeIf { it.isNotEmpty() }
            configDecoder(parcel)?.let {
                Snapshot(
                    configWithoutKey = it,
                    identityHashHex = identityHashHex,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unmarshal snapshot: ${e.message}", e)
            discardUnreadable(file)
            null
        } catch (e: LinkageError) {
            // NoSuchMethodError / NoClassDefFoundError etc. are Errors, not Exceptions, so
            // the catch above misses them. Left unhandled they crash :reticulum on FGS start,
            // and START_STICKY restarts straight back into the same crash (COLUMBA-AT).
            // Degrade to "no snapshot" — the UI re-initializes and writes a fresh one.
            Log.e(TAG, "Snapshot read hit an API/linkage error: ${e.message}", e)
            discardUnreadable(file)
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Delete a snapshot that failed to decode so a restarted `:reticulum` doesn't
     * re-read the same bad bytes on every start. The next successful UI-driven
     * initialize writes a fresh snapshot. Only called under [withSnapshotLock], so the
     * file deleted is guaranteed to be the one whose bytes just failed to decode.
     */
    private fun discardUnreadable(file: File) {
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete unreadable snapshot at ${file.absolutePath}")
        }
    }

    /**
     * Update only [ReticulumConfig.incomingMessageSizeLimitKb] on the
     * existing snapshot, without re-initializing the backend.
     *
     * The user can change the incoming-message size limit at runtime; the
     * settings path then updates DataStore and the live router, but the
     * on-disk restart snapshot would otherwise keep the initialization-time
     * value. A `:reticulum`-only recovery (UI process dead) reads this
     * snapshot, so it would resurrect a stale delivery gate - DIRECT
     * transfers incorrectly accepted or rejected until the UI re-applies
     * the setting (columba#1106).
     *
     * No-op (debug-logged) when no snapshot exists yet; the next full
     * initialization writes a fresh one with the current value.
     */
    fun updateIncomingMessageSizeLimitKb(context: Context, limitKb: Long?) {
        try {
            withSnapshotLock(context) {
                val snapshot = readLocked(context)
                if (snapshot == null) {
                    Log.d(TAG, "No snapshot to update for incoming limit ($limitKb KB)")
                    return@withSnapshotLock
                }
                writeLocked(
                    context = context,
                    config = snapshot.configWithoutKey.copy(incomingMessageSizeLimitKb = limitKb),
                    identityHashHex = snapshot.identityHashHex,
                )
                Log.d(TAG, "Snapshot incoming limit updated: $limitKb KB")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update snapshot incoming limit: ${e.message}", e)
        }
    }

    /** Delete the snapshot — used after `shutdown()` to force fresh wiring on next start. */
    fun clear(context: Context) {
        try {
            withSnapshotLock(context) { snapshotFile(context).delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear snapshot: ${e.message}", e)
        }
    }
}
