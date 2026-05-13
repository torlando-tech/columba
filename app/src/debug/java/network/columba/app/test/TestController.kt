package network.columba.app.test

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.repository.InterfaceRepository
import network.columba.app.service.InterfaceConfigManager

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
        fun interfaceRepository(): InterfaceRepository
        fun interfaceConfigManager(): InterfaceConfigManager
    }

    // Surface uncaught throws inside any scope.launch as a parseable
    // logcat line under LOGCAT_TAG, so the harness sees a failure event
    // instead of hanging on a missing success event. Without this, an
    // exception in a launch (e.g. SQLiteException from Room in the
    // interface-management handlers) would go to Android's default
    // uncaught-exception sink — visible in logcat as a stack trace under
    // the kotlinx.coroutines tag, but invisible to a harness scanning
    // only COLUMBA_TEST.
    private val launchErrorHandler = CoroutineExceptionHandler { _, t ->
        Log.e(
            LOGCAT_TAG,
            "launch_err type=${t.javaClass.simpleName} msg=${escape(t.message ?: "")}",
            t,
        )
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + launchErrorHandler,
    )
    private var protocol: ReticulumProtocol? = null
    private var interfaceRepo: InterfaceRepository? = null
    private var interfaceConfigManager: InterfaceConfigManager? = null
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
        interfaceRepo = ep.interfaceRepository()
        interfaceConfigManager = ep.interfaceConfigManager()
        receiveJob = scope.launch {
            protocol!!.observeMessages().collect { msg ->
                synchronized(rxLock) { rxQueue.add(msg) }
                // `source=stream` distinguishes real-time observer logs
                // from the queue drain emitted by handleGetRx (where lines
                // carry `source=drain`). Without the tag, every received
                // message would appear twice in logcat — once here when
                // it streams in, again when GET_RX drains the queue —
                // and a harness regex matching plain `rx_msg …` would
                // sometimes hit the duplicate first and confuse counts.
                Log.i(
                    LOGCAT_TAG,
                    "rx_msg source=stream from=${msg.sourceHash.toHex()} " +
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
            val hex = dest?.hexHash ?: dest?.hash?.toHex() ?: identity?.hash?.toHex()
            if (hex == null) {
                // Mirror handleAnnounce's not-ready signal so the harness sees
                // an explicit error token rather than a bare `dest=` it can't
                // parse.
                Log.i(LOGCAT_TAG, "dest_err reason=not_ready")
            } else {
                Log.i(LOGCAT_TAG, "dest=$hex")
            }
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
            // `source=drain` mirrors the streaming-observer convention so
            // a harness can wait on rx_msg from either path while keeping
            // counts honest (no double-logging the same delivery).
            Log.i(
                LOGCAT_TAG,
                "rx_msg source=drain from=${msg.sourceHash.toHex()} " +
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

    /** Force an announce of the active LXMF destination. Critical for the
     * harness — peers can't echo back to the phone until they've seen its
     * announce, and Columba may not announce on a fresh interface for a
     * while. Rate-limited internally by Columba ("minimum interval between
     * announces"); on success the reply is `announced dest=<hex>`, on
     * failure it's `announce_err dest=<hex> reason=<msg>` (or
     * `announce_err reason=no_active_destination` before LXMF is up). */
    fun handleAnnounce(context: Context) {
        ensureInit(context)
        scope.launch {
            val dest = protocol!!.getLxmfDestination().getOrNull() ?: run {
                Log.i(LOGCAT_TAG, "announce_err reason=no_active_destination")
                return@launch
            }
            val result = protocol!!.announceDestination(dest)
            if (result.isSuccess) {
                Log.i(LOGCAT_TAG, "announced dest=${dest.hexHash}")
            } else {
                Log.i(
                    LOGCAT_TAG,
                    "announce_err dest=${dest.hexHash} reason=${escape(result.exceptionOrNull()?.message ?: "unknown")}",
                )
            }
        }
    }

    // ─── interface management ──────────────────────────────────────────

    fun handleListInterfaces(context: Context) {
        ensureInit(context)
        scope.launch {
            val rows = interfaceRepo!!.allInterfaceEntities.first()
            for (e in rows) {
                Log.i(
                    LOGCAT_TAG,
                    "interface id=${e.id} name=${escape(e.name)} type=${e.type} enabled=${e.enabled}",
                )
            }
            Log.i(LOGCAT_TAG, "interface_list_done count=${rows.size}")
        }
    }

    /** Disables every existing interface (sets enabled=false). Does NOT
     * delete — the user's config survives. Useful for "isolate one test
     * interface" setups: disable all, then enable/add the test one. */
    fun handleDisableAllInterfaces(context: Context) {
        ensureInit(context)
        scope.launch {
            val rows = interfaceRepo!!.allInterfaceEntities.first()
            var disabled = 0
            for (e in rows) {
                if (e.enabled) {
                    interfaceRepo!!.toggleInterfaceEnabled(e.id, false)
                    disabled++
                }
            }
            applyAndLog("interfaces_disabled", "count=$disabled")
        }
    }

    fun handleSetInterfaceEnabled(context: Context, name: String, enabled: Boolean) {
        ensureInit(context)
        scope.launch {
            val e = interfaceRepo!!.findInterfaceByName(name)
            if (e == null) {
                Log.i(
                    LOGCAT_TAG,
                    "interface_${if (enabled) "enable" else "disable"}_err " +
                        "name=${escape(name)} reason=not_found",
                )
                return@launch
            }
            interfaceRepo!!.toggleInterfaceEnabled(e.id, enabled)
            applyAndLog(
                if (enabled) "interface_enabled" else "interface_disabled",
                "name=${escape(name)} id=${e.id}",
            )
        }
    }

    /** Adds a new TCP-client interface targeting host:port. If an interface
     * with the same name already exists, replaces it (delete-then-insert)
     * so repeat invocations are idempotent. */
    fun handleAddTcpClient(
        context: Context,
        name: String,
        host: String,
        port: Int,
    ) {
        ensureInit(context)
        scope.launch {
            val existing = interfaceRepo!!.findInterfaceByName(name)
            if (existing != null) {
                interfaceRepo!!.deleteInterface(existing.id)
            }
            val cfg = InterfaceConfig.TCPClient(
                name = name,
                enabled = true,
                targetHost = host,
                targetPort = port,
                kissFraming = false,
                mode = "full",
                networkRestriction = NetworkRestriction.ANY,
            )
            val newId = interfaceRepo!!.insertInterface(cfg)
            applyAndLog(
                "interface_added",
                "name=${escape(name)} id=$newId type=TCPClient host=${escape(host)} port=$port",
            )
        }
    }

    /** Configure (or clear) the outbound propagation node. Pass an empty
     * `hex` to clear. The protocol stores the dest hash and uses it as
     * the relay for any subsequent PROPAGATED message. */
    fun handleSetPropNode(context: Context, hex: String) {
        ensureInit(context)
        scope.launch {
            val bytes = if (hex.isBlank()) null else hex.fromHex()
            if (hex.isNotBlank() && bytes == null) {
                Log.i(LOGCAT_TAG, "prop_node_err reason=bad_hex hex=$hex")
                return@launch
            }
            val res = protocol!!.setOutboundPropagationNode(bytes)
            if (res.isSuccess) {
                Log.i(
                    LOGCAT_TAG,
                    "prop_node_set hex=${if (bytes == null) "(cleared)" else hex}",
                )
            } else {
                Log.i(
                    LOGCAT_TAG,
                    "prop_node_err reason=${escape(res.exceptionOrNull()?.message ?: "unknown")}",
                )
            }
        }
    }

    /** Kick a propagation-node sync — fetches any messages waiting for
     * this peer at the configured PN. Anything new arrives via the
     * existing observeMessages flow → rxQueue. */
    fun handleSyncProp(context: Context) {
        ensureInit(context)
        scope.launch {
            val identity = protocol!!.getLxmfIdentity().getOrNull()
            if (identity?.privateKey == null) {
                Log.i(LOGCAT_TAG, "prop_sync_err reason=no_active_identity_priv")
                return@launch
            }
            val res = protocol!!.requestMessagesFromPropagationNode(
                identityPrivateKey = identity.privateKey,
            )
            if (res.isSuccess) {
                val state = res.getOrNull()
                if (state == null) {
                    // Result.success(null) isn't documented in
                    // requestMessagesFromPropagationNode's contract — emit a
                    // distinct error token rather than a `state=?` sentinel
                    // that would silently get past a harness regex expecting
                    // a numeric `state=<n>`.
                    Log.i(LOGCAT_TAG, "prop_sync_err reason=null_result")
                } else {
                    Log.i(
                        LOGCAT_TAG,
                        "prop_sync_started state=${state.state} " +
                            "messages_received=${state.messagesReceived}",
                    )
                }
            } else {
                Log.i(
                    LOGCAT_TAG,
                    "prop_sync_err reason=${escape(res.exceptionOrNull()?.message ?: "unknown")}",
                )
            }
        }
    }

    fun handleRemoveInterface(context: Context, name: String) {
        ensureInit(context)
        scope.launch {
            val e = interfaceRepo!!.findInterfaceByName(name)
            if (e == null) {
                Log.i(
                    LOGCAT_TAG,
                    "interface_remove_err name=${escape(name)} reason=not_found",
                )
                return@launch
            }
            interfaceRepo!!.deleteInterface(e.id)
            applyAndLog("interface_removed", "name=${escape(name)} id=${e.id}")
        }
    }

    /** Calls applyInterfaceChanges() on the config manager so the just-edited
     * DB rows actually take effect on the running stack, then logs the
     * provided event. */
    private suspend fun applyAndLog(event: String, extras: String) {
        val res = interfaceConfigManager!!.applyInterfaceChanges()
        if (res.isSuccess) {
            Log.i(LOGCAT_TAG, "$event $extras applied=true")
        } else {
            val err = res.exceptionOrNull()?.message ?: "unknown"
            Log.i(
                LOGCAT_TAG,
                "$event $extras applied=false err=${escape(err)}",
            )
        }
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
    // Replace every whitespace char with a visible non-whitespace sentinel
    // so the result is always a single token in the harness's `key=value`
    // format and matches `\S+` regexes. Previously `\r`/`\t` were replaced
    // *with literal spaces* (which is itself token-breaking), and plain
    // ` ` (0x20) wasn't escaped at all — so any message content or
    // interface name containing a space would split the log line into
    // multiple tokens and break harness parsing.
    //   ' ' (0x20)  → '␣' (U+2423 OPEN BOX)
    //   '\n' (0x0A) → '⏎' (U+23CE RETURN SYMBOL)
    //   '\r' (0x0D) → '␍' (U+240D SYMBOL FOR CARRIAGE RETURN)
    //   '\t' (0x09) → '␉' (U+2409 SYMBOL FOR HORIZONTAL TABULATION)
    s.replace(" ", "␣").replace("\n", "⏎").replace("\r", "␍").replace("\t", "␉")
        .let { if (it.length > 1024) it.substring(0, 1024) + "…" else it }
