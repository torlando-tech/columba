package network.columba.app.rns.backend.kt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.columba.app.rns.api.model.InterfaceConfig

internal object RNodeRecoveryHelper {
    private const val TAG = "NativeInterfaceFactory"
    private const val RNODE_MONITOR_POLL_MS = 1000L
    private const val RNODE_RECOVERY_RETRY_MS = 5000L
    private const val RNODE_RECOVERY_ATTEMPT_WINDOW_MS = 12000L

    /**
     * Polls without ever being online before the interface is reported as
     * misconfigured (20 s at the 1 s cadence). A healthy RNode is online within
     * a few seconds of start; past this the firmware has stopped answering the
     * configuration handshake, and retrying the same parameters cannot help.
     */
    private const val RNODE_NEVER_ONLINE_POLLS = 20L

    fun monitorLifecycle(
        config: InterfaceConfig.RNode,
        iface: network.reticulum.interfaces.rnode.RNodeInterface,
        scope: CoroutineScope,
        runningInterfaces: java.util.concurrent.ConcurrentHashMap<String, network.reticulum.interfaces.Interface>,
        onNotifyListeners: () -> Unit,
        onEnsureRecovery: (InterfaceConfig.RNode) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            var wasOnline = false
            var pollsWithoutOnline = 0L
            var reportedNeverOnline = false
            while (isActive) {
                if (runningInterfaces[config.name] !== iface || iface.detached.get()) return@launch

                if (iface.online.value) {
                    wasOnline = true
                } else if (wasOnline) {
                    Log.w(TAG, "RNode interface ${config.name} went offline; starting recovery")
                    onNotifyListeners()
                    onEnsureRecovery(config)
                    return@launch
                } else {
                    pollsWithoutOnline++
                    if (!reportedNeverOnline && pollsWithoutOnline >= RNODE_NEVER_ONLINE_POLLS) {
                        reportedNeverOnline = true
                        Log.w(TAG, neverOnlineDiagnostic(config))
                    }
                }

                kotlinx.coroutines.delay(RNODE_MONITOR_POLL_MS)
            }
        }
    }

    /**
     * The interface never reached the online state. RNode firmware refuses the
     * radio handshake when it has to rewrite a configured parameter: TX power is
     * clamped to the board ceiling (SX127x 17 dBm, SX1262 22 dBm, LoRa-PA boards
     * report the PA-quantised value), and a device running from its own saved
     * radio config (TNC mode) ignores host configuration entirely. Both used to
     * only surface as one line in the RNode log while the interface sat offline
     * forever with no explanation (issue #1255).
     */
    private fun neverOnlineDiagnostic(config: InterfaceConfig.RNode): String =
        "RNode interface ${config.name} never came online: the device's firmware likely rewrote a " +
            "configured radio parameter (configured txPower=${config.txPower} dBm; firmware clamps to " +
            "the board ceiling, and a TNC-mode device ignores host radio config). Look for " +
            "\"TX power mismatch\" in the RNode log."

    fun ensureRecovery(
        config: InterfaceConfig.RNode,
        scope: CoroutineScope,
        rnodeRecoveryJobs: java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>,
        runningInterfaces: java.util.concurrent.ConcurrentHashMap<String, network.reticulum.interfaces.Interface>,
        onStopInterface: (String) -> Unit,
        onStartInterface: (InterfaceConfig) -> Unit,
    ) {
        val existing = rnodeRecoveryJobs[config.name]
        if (existing != null && existing.isActive) return

        val job =
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val current = runningInterfaces[config.name]
                        if (current?.online?.value == true) return@launch

                        if (current != null) {
                            onStopInterface(config.name)
                        }

                        Log.i(TAG, "Recovering RNode interface: ${config.name}")
                        onStartInterface(config)

                        var waitedMs = 0L
                        while (waitedMs < RNODE_RECOVERY_ATTEMPT_WINDOW_MS && isActive) {
                            val iface = runningInterfaces[config.name]
                            if (iface?.online?.value == true) return@launch
                            kotlinx.coroutines.delay(500)
                            waitedMs += 500
                        }

                        Log.w(TAG, "RNode recovery attempt did not come online yet: ${config.name}; retrying")
                        kotlinx.coroutines.delay(RNODE_RECOVERY_RETRY_MS)
                    }
                } finally {
                    rnodeRecoveryJobs.remove(config.name)
                }
            }

        rnodeRecoveryJobs[config.name] = job
    }
}
