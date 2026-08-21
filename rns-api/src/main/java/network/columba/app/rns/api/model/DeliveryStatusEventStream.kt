package network.columba.app.rns.api.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Advisory lifecycle invalidations for currently connected observers.
 * Durable Room state is authoritative; reconnecting consumers reload it instead of replaying IPC deltas.
 */
class DeliveryStatusEventStream {
    private val mutableEvents =
        MutableSharedFlow<DeliveryStatusUpdate>(
            replay = 0,
            extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        )

    val events: SharedFlow<DeliveryStatusUpdate> = mutableEvents.asSharedFlow()

    fun publish(update: DeliveryStatusUpdate): Boolean = mutableEvents.tryEmit(update)

    companion object {
        private const val EXTRA_BUFFER_CAPACITY = 64
    }
}
