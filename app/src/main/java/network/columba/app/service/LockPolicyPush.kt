package network.columba.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.host.ReticulumService

/**
 * Push the live AutoInterface running state to the `:reticulum` service so its
 * MulticastLock and keepalive-pulse policy track the interfaces actually
 * started. The config snapshot is only rewritten on full initialize, so the
 * hot-reload path ([network.columba.app.viewmodel.InterfaceManagementViewModel])
 * and the transport-change reload path
 * ([network.columba.app.service.manager.InterfaceTransportObserver]) must push
 * the live value themselves. Pass the interfaces actually started (after the
 * transport filter), not the full enabled set, or the service holds locks for
 * an interface that is not running. Failures are logged; the service re-reads
 * the snapshot at its next cold start regardless.
 */
fun notifyServiceLockPolicy(
    context: Context,
    configs: List<InterfaceConfig>,
    tag: String,
) {
    val autoInterfaceEnabled = configs.any { it is InterfaceConfig.AutoInterface && it.enabled }
    runCatching {
        val intent =
            Intent(context, ReticulumService::class.java).apply {
                action = ReticulumService.ACTION_REEVALUATE_LOCKS
                putExtra(ReticulumService.EXTRA_AUTO_INTERFACE_ENABLED, autoInterfaceEnabled)
            }
        ContextCompat.startForegroundService(context, intent)
    }.onFailure { e ->
        // Plain-JVM unit tests have no Android framework: Intent/Log
        // throw "Stub!" there. Logging must never outlive the guard.
        runCatching { Log.w(tag, "notifyServiceLockPolicy intent failed", e) }
    }
}
