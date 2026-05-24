package network.columba.app.rns.host.persistence

import android.content.Context
import android.os.Parcel
import android.util.Log
import java.io.File
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
    // Bumped to 2 when ReticulumConfig.shareInstanceHosting was added. Old
    // V1 snapshots don't include the new boolean and would unmarshal with
    // a torn parcel layout from this version onward, so we explicitly
    // discard them; the UI re-initialises on next launch and writes V2.
    private const val VERSION = 2

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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write config snapshot: ${e.message}", e)
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
    fun read(context: Context): Snapshot? {
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
            // readParcelable(ClassLoader) works across the whole minSdk-24 fleet. The typed
            // (ClassLoader, Class) overload is API 33+ and throws NoSuchMethodError below it —
            // and that's an Error, NOT an Exception, so the catch below would NOT save us; it
            // crashes :reticulum on FGS start. (Mirrors ReticulumConfig.kt / LinkEvent.kt.)
            @Suppress("DEPRECATION")
            val config = parcel.readParcelable<ReticulumConfig>(ReticulumConfig::class.java.classLoader)
            config?.let {
                Snapshot(
                    configWithoutKey = it,
                    identityHashHex = identityHashHex,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unmarshal snapshot: ${e.message}", e)
            null
        } finally {
            parcel.recycle()
        }
    }

    /** Delete the snapshot — used after `shutdown()` to force fresh wiring on next start. */
    fun clear(context: Context) {
        snapshotFile(context).delete()
    }
}
