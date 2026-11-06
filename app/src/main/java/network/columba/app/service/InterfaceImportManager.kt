package network.columba.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.host.manager.filterByTransport
import network.columba.app.service.manager.InterfaceTransportObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists imported interface configurations and pushes them to the running
 * stack.
 *
 * Shared by every entry point that adds interfaces from config text —
 * the paste dialog and the in-chat suggestion card. Keeping the DB write and
 * the hot-reload/pending hand-off here avoids the two paths drifting apart.
 *
 * On backends that support live interface reload (both today) a successful
 * import is reflected without restarting Reticulum. If the backend does not
 * support it, or the live reload throws, the IDs are handed to
 * [InterfaceConfigManager.setPendingChanges] so the management screen can
 * surface its existing "Apply Changes" action. Disabled interfaces never need
 * an apply, so they do not mark the stack pending.
 */
@Singleton
class InterfaceImportManager
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val rnsBackend: RnsBackend,
        private val transportAdmin: RnsTransportAdmin,
        private val transportObserver: InterfaceTransportObserver,
        private val configManager: InterfaceConfigManager,
    ) {
        data class ImportApplyResult(
            val insertedIds: List<Long>,
            val appliedLive: Boolean,
            val applyErrorMessage: String? = null,
        )

        /**
         * Insert [configs] (whose names and duplicates were already resolved by
         * the caller) and apply them to the live stack when possible.
         */
        suspend fun importInterfaces(configs: List<InterfaceConfig>): ImportApplyResult {
            val ids = configs.map { interfaceRepository.insertInterface(it) }
            val enabledIds =
                ids
                    .zip(configs)
                    .filter { (_, config) -> config.enabled }
                    .map { (id, _) -> id }

            if (enabledIds.isEmpty()) {
                return ImportApplyResult(insertedIds = ids, appliedLive = true)
            }

            if (!rnsBackend.capabilities.value.interfaces.hotReloadInterfaces) {
                markPending(enabledIds)
                return ImportApplyResult(insertedIds = ids, appliedLive = false)
            }

            return try {
                val enabled = interfaceRepository.enabledInterfaces.first()
                val filtered = filterByTransport(enabled, transportObserver.snapshotTransport())
                transportAdmin.reloadInterfaces(filtered)
                ImportApplyResult(insertedIds = ids, appliedLive = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markPending(enabledIds)
                ImportApplyResult(
                    insertedIds = ids,
                    appliedLive = false,
                    applyErrorMessage = e.message ?: "Failed to apply interfaces to the running stack",
                )
            }
        }

        private fun markPending(ids: List<Long>) {
            ids.forEach { configManager.setPendingChanges(true, it) }
        }
    }
