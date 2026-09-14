package network.columba.app.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsTransportAdmin

/**
 * Persists the shared-instance mode the Settings banner reads from the
 * `isSharedInstance` DataStore flag.
 *
 * That flag must reflect *client* mode specifically:
 * - shared-client (joined another app's master, e.g. Sideband on
 *   127.0.0.1:37428) -> true (banner: "Connected to Shared Instance")
 * - own-instance (Columba runs its own rnsd) -> false
 * - hosting (Columba itself is the shared master) -> false; hosting is
 *   driven by the separate `isHostingSharedInstance` UI state, not the
 *   client flag, so persisting true here would briefly label the host as
 *   a client until the delayed hosting poll corrects it.
 *
 * [RnsTransportAdmin.isSharedInstanceAvailable] is true for both the
 * client and hosting modes, so it is combined with
 * [RnsTransportAdmin.isHostingSharedInstance] to derive a client-only
 * signal without adding a new IPC surface.
 *
 * Best-effort by design: a probe or persistence failure is logged and
 * returns null rather than failing app startup or an otherwise-successful
 * restart, but [CancellationException] is rethrown so coroutine
 * cancellation (e.g. the settings ViewModel being cleared mid-restart)
 * is never swallowed.
 *
 * Shared by both production write sites — [ColumbaApplication.onCreate]
 * (cold start) and [InterfaceConfigManager.applyInterfaceChanges]
 * (toggle-off / Apply & Restart) — so the two paths cannot drift apart.
 *
 * @return the persisted client-mode value, or null if the best-effort
 *   write failed.
 */
object SharedInstanceStatus {
    private const val TAG = "SharedInstanceStatus"

    suspend fun persist(
        transportAdmin: RnsTransportAdmin,
        settingsRepository: SettingsRepository,
    ): Boolean? =
        try {
            // Short-circuits when no shared instance is present: the
            // hosting probe is only meaningful when one exists.
            val isSharedClient = transportAdmin.isSharedInstanceAvailable() &&
                !transportAdmin.isHostingSharedInstance()
            settingsRepository.saveIsSharedInstance(isSharedClient)
            Log.d(TAG, "Persisted shared instance status: isSharedClient=$isSharedClient")
            isSharedClient
        } catch (e: CancellationException) {
            // Propagate cancellation so the surrounding flow stops
            // instead of continuing post-cancellation.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist shared instance status", e)
            null
        }
}
