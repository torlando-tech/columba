package network.columba.app.service.binder

import android.os.Binder
import android.util.Log
import network.columba.app.service.state.ServiceState

/**
 * Local binder for ReticulumService.
 *
 * The service process is retained for foreground notification, wake locks,
 * BLE coordination, and network monitoring — it no longer brokers protocol
 * calls. NativeReticulumProtocol runs in the app process. This binder
 * therefore only exposes the two hooks the service itself uses locally.
 */
class ReticulumServiceBinder(
    private val state: ServiceState,
    private val onShutdown: () -> Unit,
) : Binder() {
    companion object {
        private const val TAG = "ReticulumServiceBinder"
    }

    fun isInitialized(): Boolean = state.isInitialized()

    /**
     * No-op in native mode — AutoInterface is managed by NativeReticulumProtocol in the
     * app process. Kept so ReticulumService's onNetworkChanged callback continues to compile.
     */
    fun restartAutoInterface() = Unit

    /**
     * No-op in native mode — announces are emitted by NativeReticulumProtocol in the
     * app process. Kept so ReticulumService's onNetworkChanged callback continues to compile.
     */
    fun announceLxmfDestination() = Unit

    fun shutdown() {
        Log.d(TAG, "Shutdown called")
        onShutdown()
    }
}
