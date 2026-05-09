package network.columba.app.test

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.reticulum.protocol.DeliveryMethod
import network.columba.app.reticulum.protocol.DeliveryStatusUpdate
import network.columba.app.reticulum.protocol.ReceivedMessage
import network.columba.app.reticulum.protocol.ReticulumProtocol

/**
 * Debug-only test surface for the columba phone harness.
 *
 * Lazy-initialized on the first broadcast received by [TestReceiver]. Binds
 * to [ReticulumProtocol] via Hilt's entry-point pattern (the protocol is a
 * singleton across the app), then subscribes to the inbound message and
 * delivery-status flows. Each broadcast action invokes one of the methods
 * below, which logs a structured `event=… key=…` line under the
 * [LOGCAT_TAG] tag for the harness to parse.
 *
 * No part of this class is referenced from main source set — it lives in
 * `app/src/debug/...` only and is compiled out of release builds.
 */
object TestController {
    const val LOGCAT_TAG = "COLUMBA_TEST"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestEntryPoint {
        fun reticulumProtocol(): ReticulumProtocol
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var protocol: ReticulumProtocol? = null
    private val rxQueue = mutableListOf<ReceivedMessage>()
    private val rxLock = Any()
    private val deliveryStates = mutableMapOf<String, String>() // msgHashHex -> stateName
    private val deliveryLock = Any()
    private var initialized = false
    private var receiveJob: Job? = null
    private var deliveryJob: Job? = null

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            TestEntryPoint::class.java,
        )
        protocol = ep.reticulumProtocol()
        receiveJob = scope.launch {
            protocol!!.observeMessages().collect { msg ->
                synchronized(rxLock) { rxQueue.add(msg) }
                Log.i(
                    LOGCAT_TAG,
                    "rx_msg from=${msg.sourceHash.toHex()} " +
                        "id=${msg.messageHash} content=${escape(msg.content)}",
                )
            }
        }
        deliveryJob = scope.launch {
            protocol!!.observeDeliveryStatus().collect { upd ->
                // DeliveryStatusUpdate.messageHash is already a hex string;
                // status is one of "delivered" | "failed" | "retrying_propagated"
                val idHex = upd.messageHash
                val stateName = upd.status.uppercase()
                synchronized(deliveryLock) { deliveryStates[idHex] = stateName }
                Log.i(LOGCAT_TAG, "msg_state id=$idHex state=$stateName")
            }
        }
        initialized = true
        Log.i(LOGCAT_TAG, "controller_ready")
    }

    fun handleGetDest(context: Context) {
        ensureInit(context)
        scope.launch {
            val dest = protocol!!.getLxmfDestination().getOrNull()
            val identity = protocol!!.getLxmfIdentity().getOrNull()
            // Prefer the LXMF destination hash (what peers route to).
            // Fall back to identity hash if destination isn't ready.
            val hex = dest?.hexHash ?: dest?.hash?.toHex() ?: identity?.hash?.toHex() ?: ""
            Log.i(LOGCAT_TAG, "dest=$hex")
        }
    }

    fun handleHasPath(context: Context, toHex: String) {
        ensureInit(context)
        scope.launch {
            val toBytes = toHex.fromHex() ?: run {
                Log.i(LOGCAT_TAG, "has_path to=$toHex result=err_bad_hex")
                return@launch
            }
            val has = try {
                protocol!!.hasPath(toBytes)
            } catch (e: Throwable) {
                Log.i(LOGCAT_TAG, "has_path to=$toHex result=err msg=${escape(e.message ?: "")}")
                return@launch
            }
            Log.i(LOGCAT_TAG, "has_path to=$toHex result=${if (has) 1 else 0}")
        }
    }

    fun handleSend(
        context: Context,
        method: DeliveryMethod,
        toHex: String,
        text: String,
    ) {
        ensureInit(context)
        scope.launch {
            val toBytes = toHex.fromHex() ?: run {
                Log.i(LOGCAT_TAG, "msg_send_err method=$method reason=bad_hex to=$toHex")
                return@launch
            }
            val identity = protocol!!.getLxmfIdentity().getOrNull() ?: run {
                Log.i(LOGCAT_TAG, "msg_send_err method=$method reason=no_active_identity")
                return@launch
            }
            val result = protocol!!.sendLxmfMessageWithMethod(
                destinationHash = toBytes,
                content = text,
                sourceIdentity = identity,
                deliveryMethod = method,
                tryPropagationOnFail = false,
            )
            result.onSuccess { receipt ->
                val idHex = receipt.messageHash.toHex()
                Log.i(
                    LOGCAT_TAG,
                    "msg_sent id=$idHex method=$method to=$toHex",
                )
                // Stamp initial state so GET_MSG_STATE works before the first
                // observeDeliveryStatus event arrives.
                synchronized(deliveryLock) {
                    deliveryStates.putIfAbsent(idHex, "OUTBOUND")
                }
            }.onFailure { err ->
                Log.i(
                    LOGCAT_TAG,
                    "msg_send_err method=$method to=$toHex reason=${escape(err.message ?: err::class.simpleName ?: "unknown")}",
                )
            }
        }
    }

    fun handleGetMsgState(context: Context, idHex: String) {
        ensureInit(context)
        val state = synchronized(deliveryLock) { deliveryStates[idHex] } ?: "UNKNOWN"
        Log.i(LOGCAT_TAG, "msg_state id=$idHex state=$state")
    }

    fun handleGetRx(context: Context) {
        ensureInit(context)
        val drained: List<ReceivedMessage>
        synchronized(rxLock) {
            drained = rxQueue.toList()
            rxQueue.clear()
        }
        for (msg in drained) {
            Log.i(
                LOGCAT_TAG,
                "rx_msg from=${msg.sourceHash.toHex()} " +
                    "id=${msg.messageHash} content=${escape(msg.content)}",
            )
        }
        Log.i(LOGCAT_TAG, "rx_drain count=${drained.size}")
    }

    fun handleRxClear(context: Context) {
        ensureInit(context)
        synchronized(rxLock) { rxQueue.clear() }
        Log.i(LOGCAT_TAG, "rx_cleared")
    }
}

// ─── helpers (file-private so they're in scope inside lambdas too) ─────────

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

private fun String.fromHex(): ByteArray? {
    val s = trim()
    if (s.length % 2 != 0) return null
    return try {
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

/** Escape a value for the `key=value` log format: replace any
 * whitespace and special chars that confuse the harness's regex. */
private fun escape(s: String): String =
    s.replace('\n', '⏎').replace('\r', ' ').replace('\t', ' ')
        .let { if (it.length > 1024) it.substring(0, 1024) + "…" else it }
