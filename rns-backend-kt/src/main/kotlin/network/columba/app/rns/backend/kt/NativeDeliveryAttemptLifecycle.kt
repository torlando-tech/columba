package network.columba.app.rns.backend.kt

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.util.toHex
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.MessageState

/** Owns callback ordering and exact-attempt state for one outbound message. */
internal class NativeDeliveryAttemptLifecycle(
    private val router: LXMRouter,
    private val tryPropagationOnFail: Boolean,
    private val initialMethod: NativeDeliveryMethod,
    private val originatingIdentityHash: String,
    private val scopeProvider: () -> CoroutineScope,
    private val fallbackDispatcher: CoroutineDispatcher,
    private val publishStatus: (String, DeliveryStatus, String) -> Unit,
) {
    private enum class State {
        PRIMARY,
        FALLBACK_SCHEDULED,
        FALLBACK_ADMITTED,
        FALLBACK_SUBMITTED,
        PROPAGATED,
        FAILED,
        DELIVERED,
    }

    private val lock = Any()
    private var state = State.PRIMARY

    fun install(message: LXMessage) {
        message.deliveryCallback = deliveryCallback
        message.failedCallback = primaryFailedCallback
    }

    private val fallbackFailedCallback: (LXMessage) -> Unit = { message ->
        message.hash?.toHex()?.let(::recordFallbackFailure)
    }

    private val deliveryCallback: (LXMessage) -> Unit = deliveryCallback@{ message ->
        val hash = message.hash?.toHex() ?: return@deliveryCallback
        val status = deliveryStatus(message)
        Log.i(
            TAG,
            "Delivery callback for ${hash.take(16)} -> $status " +
                "(state=${message.state}, method=${message.method}, desired=${message.desiredMethod})",
        )
        recordDelivery(hash, status)
    }

    private val primaryFailedCallback: (LXMessage) -> Unit = failedCallback@{ message ->
        val hash = message.hash?.toHex() ?: return@failedCallback
        val currentMethod = message.desiredMethod
        if (!admitFallbackScheduling(hash, currentMethod)) return@failedCallback

        Log.i(
            TAG,
            "${currentMethod ?: initialMethod} delivery failed for ${hash.take(16)}, falling back to PROPAGATED",
        )
        scopeProvider().launch(fallbackDispatcher) {
            submitFallback(message, hash)
        }
    }

    private fun deliveryStatus(message: LXMessage): DeliveryStatus =
        if (message.state == MessageState.DELIVERED) {
            DeliveryStatus.DELIVERED
        } else if (
            message.method == NativeDeliveryMethod.PROPAGATED ||
            message.desiredMethod == NativeDeliveryMethod.PROPAGATED
        ) {
            DeliveryStatus.PROPAGATED
        } else {
            DeliveryStatus.DELIVERED
        }

    private fun recordDelivery(hash: String, status: DeliveryStatus) {
        synchronized(lock) {
            if (status == DeliveryStatus.DELIVERED) {
                if (state != State.DELIVERED) {
                    state = State.DELIVERED
                    publish(hash, status)
                }
            } else if (state != State.DELIVERED && state != State.PROPAGATED) {
                state = State.PROPAGATED
                publish(hash, status)
            }
        }
    }

    private fun recordFallbackFailure(hash: String) {
        synchronized(lock) {
            if (state != State.DELIVERED && state != State.FAILED) {
                state = State.FAILED
                publish(hash, DeliveryStatus.FAILED)
            }
        }
    }

    private fun admitFallbackScheduling(
        hash: String,
        currentMethod: NativeDeliveryMethod?,
    ): Boolean =
        synchronized(lock) {
            when (state) {
                State.DELIVERED,
                State.FAILED,
                State.FALLBACK_SCHEDULED,
                State.FALLBACK_ADMITTED,
                -> false

                State.FALLBACK_SUBMITTED,
                State.PROPAGATED,
                -> {
                    state = State.FAILED
                    publish(hash, DeliveryStatus.FAILED)
                    false
                }

                State.PRIMARY -> scheduleFallbackOrFail(hash, currentMethod)
            }
        }

    private fun scheduleFallbackOrFail(
        hash: String,
        currentMethod: NativeDeliveryMethod?,
    ): Boolean {
        val canFallback =
            tryPropagationOnFail &&
                currentMethod != NativeDeliveryMethod.PROPAGATED &&
                router.getActivePropagationNode() != null
        if (canFallback) {
            state = State.FALLBACK_SCHEDULED
        } else {
            state = State.FAILED
            publish(hash, DeliveryStatus.FAILED)
        }
        return canFallback
    }

    private suspend fun submitFallback(
        message: LXMessage,
        hash: String,
    ) {
        if (!admitFallback(message, hash)) return
        runCatching { router.handleOutbound(message) }
            .onSuccess { recordFallbackSubmitted() }
            .onFailure {
                Log.w(TAG, "Propagation fallback submission failed for ${hash.take(16)}")
                fallbackFailedCallback(message)
            }
    }

    private fun admitFallback(
        message: LXMessage,
        hash: String,
    ): Boolean =
        synchronized(lock) {
            if (state != State.FALLBACK_SCHEDULED) {
                false
            } else {
                message.desiredMethod = NativeDeliveryMethod.PROPAGATED
                message.state = MessageState.OUTBOUND
                message.deliveryAttempts = 0
                message.failedCallback = fallbackFailedCallback
                state = State.FALLBACK_ADMITTED
                publish(hash, DeliveryStatus.RETRYING_PROPAGATED)
                true
            }
        }

    private fun recordFallbackSubmitted() {
        synchronized(lock) {
            if (state == State.FALLBACK_ADMITTED) {
                state = State.FALLBACK_SUBMITTED
            }
        }
    }

    private fun publish(hash: String, status: DeliveryStatus) {
        publishStatus(hash, status, originatingIdentityHash)
    }

    private companion object {
        const val TAG = "NativeReticulumProtocol"
    }
}
