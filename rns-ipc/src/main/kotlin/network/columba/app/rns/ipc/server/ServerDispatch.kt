package network.columba.app.rns.ipc.server

import android.os.Bundle
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.ipc.callback.IRnsBoolCallback
import network.columba.app.rns.ipc.callback.IRnsByteArrayCallback
import network.columba.app.rns.ipc.callback.IRnsFloatCallback
import network.columba.app.rns.ipc.callback.IRnsIntCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.callback.IRnsStringListCallback

/**
 * Server-side dispatch helpers — launch a coroutine on the host scope, run
 * the suspend backend call, and translate success/failure into the right
 * callback shape. Centralizing the try/catch keeps each per-sub-interface
 * adapter focused on the call-shape translation alone.
 *
 * `Result.failure` from the suspend impl is unwrapped via [bundleOrThrow] /
 * [getOrThrow] and turned into [IRnsResultCallback.onError] with a typed
 * [RnsError] envelope. [RnsException] passes through unchanged; everything
 * else is wrapped in [RnsError.Generic].
 *
 * Client-side [RemoteException] swallowing keeps the host alive when a UI
 * process crashes mid-callback.
 */
internal inline fun dispatch(
    cb: IRnsResultCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> Bundle,
) {
    scope.launch {
        try {
            val payload = block()
            cb.safeSuccess(payload)
        } catch (e: Throwable) {
            cb.safeError(e.toRnsError())
        }
    }
}

internal inline fun dispatchBool(
    cb: IRnsBoolCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> Boolean,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

internal inline fun dispatchNullableInt(
    cb: IRnsIntCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> Int?,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value ?: 0, value != null) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

internal inline fun dispatchNullableString(
    cb: IRnsStringCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> String?,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

internal inline fun dispatchFloat(
    cb: IRnsFloatCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> Float,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

internal inline fun dispatchStringList(
    cb: IRnsStringListCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> List<String>,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

internal inline fun dispatchNullableByteArray(
    cb: IRnsByteArrayCallback,
    scope: CoroutineScope,
    crossinline block: suspend () -> ByteArray?,
) {
    scope.launch {
        try {
            val value = block()
            try { cb.onSuccess(value) } catch (_: RemoteException) { /* client dead */ }
        } catch (e: Throwable) {
            try { cb.onError(e.toRnsError()) } catch (_: RemoteException) { /* client dead */ }
        }
    }
}

/** Swallow [RemoteException] when delivering a one-shot Bundle success. */
internal fun IRnsResultCallback.safeSuccess(payload: Bundle) {
    try { onSuccess(payload) } catch (_: RemoteException) { /* client dead */ }
}

/** Swallow [RemoteException] when delivering a one-shot error. */
internal fun IRnsResultCallback.safeError(error: RnsError) {
    try { onError(error) } catch (_: RemoteException) { /* client dead */ }
}

/**
 * Map any throwable to a typed [RnsError]. [RnsException] passes through
 * unchanged; everything else falls into [RnsError.Generic] with the message
 * and stack trace preserved as text so the UI can show a useful failure.
 */
internal fun Throwable.toRnsError(): RnsError =
    when (this) {
        is RnsException -> error
        else -> RnsError.Generic(message ?: this::class.java.simpleName, stackTraceToString())
    }

/**
 * Unwrap a [Result.success]'s value or throw the failure as an [RnsException]
 * so the dispatch helper can translate it into [IRnsResultCallback.onError].
 *
 * The result must have been built from a backend call that returns
 * `Result<Unit>`; this overload simply returns [Bundle.EMPTY] on success.
 */
internal fun Result<Unit>.bundleOrThrow(): Bundle {
    return fold(
        onSuccess = { Bundle.EMPTY },
        onFailure = { throw it },
    )
}
