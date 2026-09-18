package network.columba.app.service.manager

import android.os.SystemClock
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
import network.columba.app.rns.host.manager.filterByTransport
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
 * a bounded poll: every [CHECK_INTERVAL_MS] it verifies that every interface the
 * current transport filter would apply is present in the live RNS transport, and
 * stops once the set is [isIntactNow intact]. When one or more are missing it runs
 * the proven recovery primitive - [InterfaceConfigManager.applyInterfaceChanges], the
 * same full service restart the UI "Restart" button performs: re-read the DB, re-filter
 * against the CURRENT transport, fresh `:reticulum` process, refresh the persisted
 * snapshot.
 *
 * The poll is bounded rather than a perpetual ticker: it is only scheduled in
 * response to a real transport transition and exits as soon as the set is intact
 * (or a bounded not-READY grace / READY-anchored window elapses), so a healthy
 * stack pays nothing. The recovery window is anchored to the moment RNS first
 * reports READY (not the transport transition) because the dead signature is only
 * observable once READY, and it then stays active across the re-initialization a
 * recovery restart causes. All timing is monotonic ([SystemClock.elapsedRealtime])
 * so a device-clock correction cannot expire the window early.
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

            /** Interval between recovery checks inside the window. */
            const val CHECK_INTERVAL_MS: Long = 6_000L

            /** Recovery window, measured from the moment RNS first reports READY
             * (not from the transport transition). The dead signature - "READY but
             * interfaces missing" - cannot be observed until RNS is READY, so a window
             * anchored to the transition would be partly or wholly spent in the
             * not-READY cold-start phase and expire before the dead state is ever seen.
             * The window then stays active across the re-initialization a recovery
             * restart causes. A python cold start reaches READY ~20-30s in, and a
             * recovery restart re-inits RNS for another ~30s, so 3 min covers both. */
            const val READY_RECOVERY_WINDOW_MS: Long = 180_000L

            /** How long the poll keeps running while RNS is not READY before it gives
             * up (bounded, so it is not a perpetual ticker). Covers a python cold start
             * and the re-init following a recovery restart. */
            const val NOT_READY_GRACE_MS: Long = 90_000L

            /** Minimum spacing between two recovery restarts (monotonic clock). */
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
                Log.d(TAG, "Already started - skipping duplicate start()")
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
                var firstReadyMs: Long? = null
                var notReadySinceMs: Long? = null
                while (true) {
                    delay(CHECK_INTERVAL_MS)
                    maybeRecover()
                    if (isIntactNow()) {
                        Log.d(TAG, "Interface set intact - stopping recovery window")
                        return@launch
                    }
                    // The dead state is only observable once RNS is READY, so the
                    // recovery window is anchored to the FIRST READY, not to the
                    // transport transition. Before that, allow a bounded grace period
                    // (covers a python cold start + the re-init a recovery restart
                    // causes); after that, the READY-anchored window applies. All timing
                    // is monotonic so a device-clock jump cannot expire the window.
                    val ready = rnsCore.networkStatus.value is NetworkStatus.READY
                    val now = SystemClock.elapsedRealtime()
                    if (ready) {
                        notReadySinceMs = null
                        val anchor = firstReadyMs ?: now.also { firstReadyMs = it }
                        if (now - anchor >= READY_RECOVERY_WINDOW_MS) {
                            Log.d(TAG, "Recovery window elapsed after READY - stopping checks")
                            return@launch
                        }
                    } else {
                        val since = notReadySinceMs ?: now.also { notReadySinceMs = it }
                        if (now - since >= NOT_READY_GRACE_MS) {
                            Log.d(TAG, "RNS not READY for the grace window - stopping checks")
                            return@launch
                        }
                    }
                }
            }
        }

        /**
         * Pure liveness check: RNS is READY and every interface that the current
         * transport filter would apply is present in the live transport set.
         *
         * The expected set is [filterByTransport]-filtered against the CURRENT
         * transport (the same predicate [InterfaceConfigManager.applyInterfaceChanges]
         * uses to build the runtime config), not every enabled IP-riding interface.
         * An enabled interface restricted to another transport (e.g. a WIFI_ONLY
         * interface while the device is on cellular) is deliberately NOT in the live
         * set; requiring it would treat a healthy, filtered configuration as damaged
         * and restart RNS in a loop.
         *
         * Public so unit tests can drive the invariant directly. Returns false when
         * the live set cannot be read (so an unknown state is never mistaken for
         * "intact").
         */
        suspend fun isIntactNow(): Boolean {
            if (rnsCore.networkStatus.value !is NetworkStatus.READY) return false
            val transport = transportObserver.currentTransport.value
            val enabled = interfaceRepository.enabledInterfaces.first()
            val expected = filterByTransport(enabled, transport).map { it.name }.toSet()
            if (expected.isEmpty()) return true
            val live = readLiveInterfaceNames()
            return live != null && expected.all { name -> name in live }
        }

        /**
         * Single recovery evaluation. Public so unit tests can drive the decision
         * table directly without the coroutine/timer plumbing.
         *
         * @return true when a full interface re-apply (restart) was executed.
         */
        suspend fun maybeRecover(): Boolean {
            skipReason()?.let {
                Log.d(TAG, "Skip recovery: $it")
                return false
            }
            return mutex.withLock {
                if (shouldSkipLocked()) return@withLock false
                performRecovery()
            }
        }

        /**
         * Coarse pre-gate (read-only, before taking the lock). Returns a human
         * reason when recovery must be skipped, or null when the coarse checks
         * all pass and the guarded action may proceed.
         */
        private suspend fun skipReason(): String? {
            val hotReload = rnsBackend.capabilities.value.interfaces.hotReloadInterfaces
            if (hotReload || rnsCore.networkStatus.value !is NetworkStatus.READY) {
                return if (hotReload) {
                    "hot-reload backend (heals via observer reloadInterfaces)"
                } else {
                    "RNS not READY (${rnsCore.networkStatus.value})"
                }
            }
            if (configApplyFlagManager.isApplyingConfig()) return "interface apply already in flight"
            val withinCooldown =
                SystemClock.elapsedRealtime() - lastRecoveryStartMs < RECOVERY_COOLDOWN_MS
            return if (withinCooldown) "cooldown active" else null
        }

        /**
         * Fine-grated re-checks under the lock (a concurrent manual Restart may
         * have started since [skipReason]). True when the guarded action must
         * still be skipped.
         */
        private suspend fun shouldSkipLocked(): Boolean {
            if (configApplyFlagManager.isApplyingConfig()) {
                Log.d(TAG, "Skip recovery: interface apply in flight (under lock)")
                return true
            }
            val transport = transportObserver.currentTransport.value
            if (transport == CurrentTransport.NONE ||
                rnsCore.networkStatus.value !is NetworkStatus.READY
            ) {
                Log.d(TAG, "Skip recovery: transport=$transport, status=${rnsCore.networkStatus.value}")
                return true
            }
            val intact = isIntactNow()
            return if (intact) {
                Log.d(TAG, "Interface set intact; no recovery needed")
                true
            } else {
                false
            }
        }

        /** Runs the full interface re-apply (service restart). */
        private suspend fun performRecovery(): Boolean {
            Log.i(TAG, "#1127 recovery: transport=${transportObserver.currentTransport.value}, " +
                "RNS READY but interfaces missing; running full interface re-apply (service restart)")
            lastRecoveryStartMs = SystemClock.elapsedRealtime()
            interfaceConfigManager.applyInterfaceChanges()
                .onSuccess { Log.i(TAG, "#1127 recovery: interface re-apply succeeded") }
                .onFailure { e ->
                    Log.e(TAG, "#1127 recovery: interface re-apply failed", e)
                    // Reset the cooldown on failure so the next poll can retry
                    // promptly instead of waiting out the full window.
                    lastRecoveryStartMs = 0L
                }
            return true
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
