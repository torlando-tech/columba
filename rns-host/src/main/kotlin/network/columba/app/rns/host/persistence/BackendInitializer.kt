package network.columba.app.rns.host.persistence

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.model.ReticulumConfig

/**
 * Self-initialization driver for the `:reticulum` foreground service.
 *
 * When Android `START_STICKY`-restarts the FGS after an OOM kill (or a force-
 * stop), the UI process may not be alive to call `rnsCore.initialize(config)`.
 * This initializer reads the [ReticulumConfigSnapshot] written by the last
 * successful UI-driven initialize, decrypts the identity key via Keystore +
 * Room (the same `IdentityKeyProvider`/`IdentityRepository` pipeline the UI
 * uses, both reachable from `:data`), and invokes `rnsBackend.core.initialize`
 * locally.
 *
 * Idempotent: if the UI happens to be alive and races us to initialize,
 * [PythonRnsRuntime.start] / `NativeRnsBackendImpl.initialize` already
 * early-return when `running.get()` is true. The loser logs and returns
 * Result.success(Unit).
 *
 * Lives in `:rns-host/persistence` because its Hilt deps (IdentityRepository,
 * IdentityKeyProvider) are in `:data` — `:rns-host` already depends on it
 * for `ServicePersistenceManager`.
 */
@Singleton
class BackendInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityRepository: IdentityRepository,
    private val identityKeyProvider: IdentityKeyProvider,
) {
    private companion object {
        const val TAG = "BackendInitializer"
    }

    /**
     * Attempt to initialize [backend] from the on-disk snapshot.
     *
     * @return `true` if a snapshot was found and `initialize()` was called
     *   (regardless of whether the call started a fresh stack or short-
     *   circuited on already-running); `false` if no snapshot was available
     *   or the snapshot/key restore failed.
     */
    suspend fun initializeFromSnapshot(backend: RnsBackend): Boolean {
        val snapshot = ReticulumConfigSnapshot.read(context)
        if (snapshot == null) {
            Log.i(TAG, "No config snapshot — waiting for UI to drive initialize()")
            return false
        }

        val deliveryKey = resolveDeliveryKey(snapshot.identityHashHex)
        val resolvedConfig = snapshot.configWithoutKey.copy(
            deliveryIdentityKey = deliveryKey,
        )

        Log.i(
            TAG,
            "Self-initializing :reticulum from snapshot: " +
                "${resolvedConfig.enabledInterfaces.size} interface(s), " +
                "identity=${snapshot.identityHashHex?.take(8) ?: "none"}…, " +
                "key=${deliveryKey?.size?.let { "$it bytes" } ?: "none"}",
        )

        return runCatching {
            backend.core.initialize(resolvedConfig).fold(
                onSuccess = {
                    Log.i(TAG, "Self-init completed — backend ready")
                    true
                },
                onFailure = { error ->
                    Log.w(TAG, "Self-init failed: ${error.message}", error)
                    false
                },
            )
        }.getOrElse { error ->
            Log.w(TAG, "Self-init crashed: ${error.message}", error)
            false
        }
    }

    /**
     * Decrypt the active identity's delivery key, or return null if there is
     * no active identity yet (first launch — the native stack will create a
     * default) or the decrypt fails (Keystore wipe, etc. — same fallback the
     * UI path takes).
     */
    private suspend fun resolveDeliveryKey(identityHashHex: String?): ByteArray? {
        val hashHex = identityHashHex
            ?: identityRepository.getActiveIdentitySync()?.identityHash
            ?: run {
                Log.i(TAG, "No active identity — :reticulum will create a default")
                return null
            }
        return identityKeyProvider.getDecryptedKeyData(hashHex).fold(
            onSuccess = { bytes ->
                Log.d(TAG, "Decrypted delivery key for $hashHex (${bytes.size} bytes)")
                bytes
            },
            onFailure = { error ->
                Log.w(TAG, "Failed to decrypt delivery key for $hashHex: $error")
                null
            },
        )
    }
}
