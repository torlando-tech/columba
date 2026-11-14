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
 * When Android `START_STICKY`-restarts the FGS after an OOM kill (or the boot
 * receiver starts it after a reboot), the UI process may not be alive to call
 * `rnsCore.initialize(config)`. This initializer reads the
 * [ReticulumConfigSnapshot] written by the last successful UI-driven
 * initialize, decrypts the identity key via Keystore + Room (the same
 * `IdentityKeyProvider`/`IdentityRepository` pipeline the UI uses, both
 * reachable from `:data`), and invokes `rnsBackend.core.initialize` locally.
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

    /** Result of one [initializeFromSnapshot] attempt. */
    enum class InitOutcome {
        /** `initialize()` ran, either starting a fresh stack or short-circuiting on a running one. */
        INITIALIZED,

        /** Never-configured install: nothing on disk to initialize from. */
        NO_SNAPSHOT,

        /**
         * The snapshot names an identity whose key cannot be decrypted here
         * (password-protected and locked, or Keystore/DB failure). Initializing
         * anyway would make the native stack mint a *fresh* identity and orphan
         * the real one — the UI must drive initialize() after unlock instead.
         */
        KEY_UNAVAILABLE,

        /** `initialize()` ran but reported failure, or it crashed. */
        FAILED,
    }

    /**
     * Attempt to initialize [backend] from the on-disk snapshot.
     *
     * @return [InitOutcome.INITIALIZED] if `initialize()` was called (regardless
     *   of whether it started a fresh stack or short-circuited on
     *   already-running), [InitOutcome.NO_SNAPSHOT] on a never-configured
     *   install, [InitOutcome.KEY_UNAVAILABLE] when the snapshot's identity
     *   key cannot be decrypted/used here (see [InitOutcome.KEY_UNAVAILABLE]),
     *   [InitOutcome.FAILED] when `initialize()` failed.
     */
    suspend fun initializeFromSnapshot(backend: RnsBackend): InitOutcome {
        val snapshot =
            ReticulumConfigSnapshot.read(context)
                ?: run {
                    Log.i(TAG, "No config snapshot — waiting for UI to drive initialize()")
                    return InitOutcome.NO_SNAPSHOT
                }

        val deliveryKey =
            when (val resolution = resolveDeliveryKey(snapshot.identityHashHex)) {
                is KeyResolution.Unavailable -> {
                    Log.w(
                        TAG,
                        "Skipping snapshot self-init — identity key unavailable " +
                            "(locked identity or Keystore failure). UI will drive initialize() " +
                            "after unlock; initializing now would create a divergent identity.",
                    )
                    return InitOutcome.KEY_UNAVAILABLE
                }
                is KeyResolution.Available -> resolution.key
            }

        val resolvedConfig =
            snapshot.configWithoutKey.copy(
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
                    InitOutcome.INITIALIZED
                },
                onFailure = { error ->
                    Log.w(TAG, "Self-init failed: ${error.message}", error)
                    InitOutcome.FAILED
                },
            )
        }.getOrElse { error ->
            Log.w(TAG, "Self-init crashed: ${error.message}", error)
            InitOutcome.FAILED
        }
    }

    /** Outcome of the delivery-key lookup that precedes [initializeFromSnapshot]'s `initialize()`. */
    private sealed interface KeyResolution {
        /** A key, or `null` when there is no identity yet (native stack creates a default). */
        data class Available(val key: ByteArray?) : KeyResolution

        /** The identity exists but its key cannot be decrypted in this process/state. */
        data object Unavailable : KeyResolution
    }

    /**
     * Resolve the delivery key for [identityHashHex], falling back to the
     * active identity when the snapshot predates identity tracking.
     *
     * Password-protected identities and decrypt failures both resolve to
     * [KeyResolution.Unavailable] rather than a null key: the UI path
     * (`ColumbaApplication`, which bails out on `requiresPassword`) is the only
     * thing allowed to decide the identity is unusable, and it does so with the
     * user present. Fail-safe on a DB/Keystore error too — treating an
     * unreadable password status as "no password" is exactly the silent
     * divergent-identity boot this guard exists to prevent.
     */
    private suspend fun resolveDeliveryKey(identityHashHex: String?): KeyResolution {
        val hashHex =
            identityHashHex
                ?: identityRepository.getActiveIdentitySync()?.identityHash
                ?: run {
                    Log.i(TAG, "No active identity — :reticulum will create a default")
                    return KeyResolution.Available(null)
                }
        return identityKeyProvider.getDecryptedKeyData(hashHex).fold(
            onSuccess = { bytes ->
                Log.d(TAG, "Decrypted delivery key for ${hashHex.take(8)}… (${bytes.size} bytes)")
                KeyResolution.Available(bytes)
            },
            onFailure = { error ->
                Log.w(TAG, "Failed to decrypt delivery key for ${hashHex.take(8)}…: ${error::class.simpleName}")
                KeyResolution.Unavailable
            },
        )
    }
}
