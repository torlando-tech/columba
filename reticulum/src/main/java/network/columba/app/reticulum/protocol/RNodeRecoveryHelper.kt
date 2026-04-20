package network.columba.app.reticulum.protocol

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.columba.app.reticulum.model.InterfaceConfig

internal object RNodeRecoveryHelper {
    private const val TAG = "NativeInterfaceFactory"
    private const val RNODE_MONITOR_POLL_MS = 1000L
    private const val RNODE_RECOVERY_RETRY_MS = 5000L
    private const val RNODE_RECOVERY_ATTEMPT_WINDOW_MS = 12000L

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
            while (isActive) {
                if (runningInterfaces[config.name] !== iface || iface.detached.get()) return@launch

                if (iface.online.value) {
                    wasOnline = true
                } else if (wasOnline) {
                    Log.w(TAG, "RNode interface ${config.name} went offline; starting recovery")
                    onNotifyListeners()
                    onEnsureRecovery(config)
                    return@launch
                }

                kotlinx.coroutines.delay(RNODE_MONITOR_POLL_MS)
            }
        }
    }

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
