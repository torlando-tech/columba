package network.columba.app.service.state

import java.util.concurrent.atomic.AtomicReference

/**
 * Shared state for ReticulumService.
 *
 * The service process only needs to track its own foreground-notification status now;
 * everything else (polling jobs, shutdown generations, conversation-active signals)
 * was Python-era plumbing and has been removed.
 */
class ServiceState {
    /**
     * Current network status.
     * Values: "SHUTDOWN", "INITIALIZING", "CONNECTING", "READY", or "ERROR:message".
     */
    val networkStatus = AtomicReference<String>("SHUTDOWN")

    /**
     * Check if the service network is ready.
     */
    fun isInitialized(): Boolean = networkStatus.get() == "READY"
}
