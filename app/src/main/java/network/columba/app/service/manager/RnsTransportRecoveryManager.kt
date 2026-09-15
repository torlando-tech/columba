package network.columba.app.service.manager

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.rns.host.manager.ridesOnIpCarrier
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.startup.ConfigApplyFlagManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-recovery for columba#1127: "RNS READY but 0 interfaces" after a
 * profile-swap / network-gap process restart.
 *
 * ## Root cause this manager closes
 *
 * A user-profile switch (or any OS process reap that overlaps a default-network
 * drop) can cold-start the app while `CurrentTransport` is `NONE`. The startup
 * filter (`filterByTransport`) drops every IP-riding interface for `NONE`, so RNS
 * initializes READY with an empty live interface set and every send dies with
 * "No interfaces could process the outbound packet". On the python backend
 * `RnsTransportAdmin.reloadInterfaces` is a documented no-op (upstream RNS has no
 * live interface reload), so the observer's reload on the NONE→live transition is
 * a silent no-op and the interface set is never re-applied until the user taps
 * Restart manually.
 *
 * ## Strategy
 *
 * Backends that can hot-reload (`hotReloadInterfaces = true`, kotlin-native) heal
 * through the observer's real `reloadInterfaces` call, so this manager is dormant
 * for them. For restart-gated backends (python, `hotReloadInterfaces = false`) it
 * watches the main-process transport stream. On any non-NONE transport it begins
 * a bounded poll: every [CHECK_INTERVAL_MS] it verifies that every enabled
 * IP-riding interface is present in the live RNS transport, and stops once the set
 * is [isIntactNow intact]. When one or more are missing it runs the proven
 * recovery primitive — [InterfaceConfigManager.applyInterfaceChanges], the same
 * full service restart the UI "Restart" button performs: re-read the DB, re-filter
 * against the CURRENT transport, fresh `:reticulum` process, refresh the persisted
 * snapshot.
 *
 * The poll is bounded and short-lived (a few minutes) rather than a perpetual
 * ticker: it is only scheduled in response to a real transport transition and
 * exits as soon as the set is intact, so a healthy stack pays nothing.
 *
 * ## Guards (each independently prevents a restart loop)
 *
 * - capability gate: restart-gated backends only (hot-reload backends are skipped);
 * - transport gate: non-NONE only (a real default network must exist);
 * - RNS gate: READY only (never interrupt INITIALIZING/CONNECTING/SHUTDOWN);
 * - in-apply flag: a `applyInterfaceChanges` already in flight (manual or this
 *   manager's own restart) blocks a second restart;
 * - in-flight mutex + cooldown: at most one recovery at a time, spaced out.
 *
 * Lives in the main process (like [InterfaceTransportObserver]) because it needs
 * the Hilt-injected repository/backend bindings and drives the same restart the
 * main process drives. Started from [network.columba.app.ColumbaApplication]
 * alongside the transport observer; a no-op until `start()` is called.
 */
@Singleton
class RnsTransportRecoveryManager
    @Inject
    constructor(
        private val rnsCore: RnsCore,
        private val rnsTransportAdmin: RnsTransportAdmin,
        private val rnsBackend: RnsBackend,
        private val interfaceRepository: InterfaceRepository,
        private val interfaceConfigManager: InterfaceConfigManager,
        private val configApplyFlagManager: ConfigApplyFlagManager,
        private val transportObserver: InterfaceTransportObserver,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "RnsTransportRecovery"

            /** Interval between recovery checks after a transport transition. The first
             * check fires this long after the transition; it is deliberately short because a
             * python cold start reaches READY (base interfaces present) well before
             * AutoDiscovery *peers* populate, so a READY-with-0-live-interfaces state is the
             * dead signature, not a transient. */
            const val CHECK_INTERVAL_MS: Long = 6_000L

            /** Maximum number of checks per transition (~36s coverage window). */
            const val MAX_CHECKS: Int = 6

            /** Minimum spacing between two recovery restarts. */
            const val RECOVERY_COOLDOWN_MS: Long = 30_000L
        }

        private val mutex = Mutex()
        @Volatile
        private var lastRecoveryStartMs = 0L
        private var job: Job? = null

        /** The transport stream the recovery logic reacts to (main process view). */
        val currentTransport: StateFlow<CurrentTransport> = transportObserver.currentTransport

        /**
         * Begin observing. Idempotent; further calls are ignored. Called from
         * [network.columba.app.ColumbaApplication.onCreate] after the transport
         * observer starts.
         */
        fun start() {
            if (job != null) {
                Log.d(TAG, "Already started — skipping duplicate start()")
                return
            }
            job =
                applicationScope.launch {
                    // First emission is the construction seed (current transport);
                    // every subsequent emission is a real transition.
                    transportObserver.currentTransport.collect { transport ->
                        Log.d(TAG, "Transport -> $transport; scheduling recovery checks")
                        scheduleChecks(transport)
                    }
                }
        }

        /** Idempotent stop for teardown. */
        fun stop() {
            job?.cancel()
            job = null
        }

        private fun scheduleChecks(transport: CurrentTransport) {
            if (transport == CurrentTransport.NONE) return
            applicationScope.launch {
                repeat(MAX_CHECKS) {
                    delay(CHECK_INTERVAL_MS)
                    maybeRecover()
                    if (isIntactNow()) {
                        Log.d(TAG, "Interface set intact after recovery window — stopping checks")
                        return@launch
                    }
                }
            }
        }

        /**
         * Pure liveness check: RNS is READY and every enabled IP-riding interface
         * is present in the live transport set. Public so unit tests can drive the
         * invariant directly. Returns false when the live set cannot be read (so an
         * unknown state is never mistaken for "intact").
         */
        suspend fun isIntactNow(): Boolean {
            if (rnsCore.networkStatus.value !is NetworkStatus.READY) return false
            val enabled = interfaceRepository.enabledInterfaces.first()
            val ipRiding = enabled.filter { it.ridesOnIpCarrier() }
            if (ipRiding.isEmpty()) return true
            val live = readLiveInterfaceNames() ?: return false
            val missing = ipRiding.map { it.name }.toSet() - live
            return missing.isEmpty()
        }

        /**
         * Single recovery evaluation. Public so unit tests can drive the decision
         * table directly without the coroutine/timer plumbing.
         *
         * @return true when a full interface re-apply (restart) was executed.
         */
        suspend fun maybeRecover(): Boolean {
            // Capability gate: hot-reload backends heal via the observer's real
            // reloadInterfaces; a full restart there would be pure churn.
            if (rnsBackend.capabilities.value.interfaces.hotReloadInterfaces) return false

            if (rnsCore.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Skip recovery: RNS not READY (${rnsCore.networkStatus.value})")
                return false
            }

            if (configApplyFlagManager.isApplyingConfig()) {
                Log.d(TAG, "Skip recovery: interface apply already in flight")
                return false
            }

            if (System.currentTimeMillis() - lastRecoveryStartMs < RECOVERY_COOLDOWN_MS) {
                Log.d(TAG, "Skip recovery: cooldown active")
                return false
            }

            return mutex.withLock {
                // Re-check under the lock: a concurrent manual Restart may have
                // started between the outer checks and now.
                if (configApplyFlagManager.isApplyingConfig()) return@withLock false
                val transport = transportObserver.currentTransport.value
                if (transport == CurrentTransport.NONE) {
                    Log.d(TAG, "Skip recovery: transport NONE (network down)")
                    return@withLock false
                }
                if (rnsCore.networkStatus.value !is NetworkStatus.READY) return@withLock false
                if (isIntactNow()) {
                    Log.d(TAG, "Interface set intact; no recovery needed")
                    return@withLock false
                }

                Log.i(TAG, "#1127 recovery: transport=$transport, RNS READY but interfaces " +
                    "missing; running full interface re-apply (service restart)")
                lastRecoveryStartMs = System.currentTimeMillis()
                interfaceConfigManager.applyInterfaceChanges()
                    .onSuccess { Log.i(TAG, "#1127 recovery: interface re-apply succeeded") }
                    .onFailure { e ->
                        Log.e(TAG, "#1127 recovery: interface re-apply failed", e)
                        // Reset the cooldown on failure so the next poll can retry
                        // promptly instead of waiting out the full window.
                        lastRecoveryStartMs = 0L
                    }
                true
            }
        }

        /**
         * Read the live RNS transport interface names. Mirrors the debug `LIVE_STATE`
         * hook: `getDebugInfo()["interfaces"]` is a list of maps each carrying a
         * `name`. Returns null on any failure so a broken read degrades to "unknown"
         * (treated as not-intact, retried next poll) rather than to a false "intact".
         */
        @Suppress("UNCHECKED_CAST")
        private suspend fun readLiveInterfaceNames(): Set<String>? =
            try {
                val debug = rnsTransportAdmin.getDebugInfo()
                val interfaces = debug["interfaces"] as? List<Map<String, Any>> ?: return null
                interfaces
                    .mapNotNull { it["name"]?.toString() }
                    .filter { it.isNotBlank() && it != "?" }
                    .toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getDebugInfo failed while checking live interfaces", e)
                null
            }
    }
