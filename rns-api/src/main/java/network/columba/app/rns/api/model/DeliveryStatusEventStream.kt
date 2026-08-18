package network.columba.app.rns.api.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Backend-parity stream retaining the last bounded lifecycle events for late subscribers. */
class DeliveryStatusEventStream(
    replay: Int = DEFAULT_REPLAY,
) {
    private val mutableEvents =
        MutableSharedFlow<DeliveryStatusUpdate>(
            replay = replay,
            extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        )

    val events: SharedFlow<DeliveryStatusUpdate> = mutableEvents.asSharedFlow()

    fun publish(update: DeliveryStatusUpdate): Boolean = mutableEvents.tryEmit(update)

    companion object {
        const val DEFAULT_REPLAY = 8
        private const val EXTRA_BUFFER_CAPACITY = 64
    }
}
