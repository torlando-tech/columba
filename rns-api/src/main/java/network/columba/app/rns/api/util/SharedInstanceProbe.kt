package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.ReticulumConfig
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Two-axis detection for "is another Reticulum app on this device":
 *
 *  - **TCP probe** on `127.0.0.1:37428` — the canonical RNS
 *    `shared_instance_port`. Detects an app running in
 *    `share_instance = yes` mode (e.g. Sideband by default). When
 *    found, we set `share_instance = yes` in our config and skip
 *    interface declarations — RNS joins as a client via RPC.
 *
 *  - **UDP test bind** on the AutoInterface `data_port` (29717
 *    default). Detects an app holding the IPv6 link-local multicast
 *    bind even when it's NOT exposing a shared instance — i.e. another
 *    Columba install running as standalone. AutoInterface can't be
 *    started in our process; we skip declaring it but leave the rest
 *    of our config (TCP/BLE/RNode) intact.
 *
 * The TCP probe alone (the v0.10.x design) works when the coexisting
 * app is configured as a shared master, but fails when it's just
 * another standalone owning the multicast bind.
 */
object SharedInstanceProbe {
    /** RNS default `shared_instance_port` (`Reticulum.SHARED_INSTANCE_PORT`). */
    const val DEFAULT_PORT = 37428
    const val DEFAULT_TIMEOUT_MS = 1_000

    /**
     * How many times [isAvailable] retries a failed connect, and how long it
     * waits between attempts.
     *
     * A single 1s connect is racy on a busy device: if the co-hosted master
     * (Sideband) is restarting its backend, or the loopback stack is briefly
     * contended, the connect fails even though a shared master is (or will be
     * within a second or two) present. Callers of [shouldShareInstance] would
     * then wrongly fall back to own-instance mode, and the Settings
     * availability check would wrongly report "no shared instance".
     *
     * Retries bound the worst-case "unavailable" latency to
     * `attempts * timeout + (attempts - 1) * delay` (default ≈1.6s), which is
     * acceptable on both call sites: the daemon's join-decision runs on a
     * background thread, and the Settings monitor polls on a 5s cadence.
     * An immediate success (the common case) still returns after one attempt.
     */
    const val DEFAULT_ATTEMPTS = 3
    const val DEFAULT_RETRY_DELAY_MS = 300L

    /** AutoInterface default `data_port` (`AutoInterface.DEFAULT_DATA_PORT`). */
    const val DEFAULT_AUTO_INTERFACE_DATA_PORT = 29717

    fun isAvailable(
        host: String = "127.0.0.1",
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = DEFAULT_ATTEMPTS,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    ): Boolean {
        repeat(attempts) { attempt ->
            if (
                runCatching {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), timeoutMs)
                        sock.isConnected
                    }
                }.getOrDefault(false)
            ) {
                return true
            }
            // Not the last attempt: back off briefly before retrying.
            if (attempt < attempts - 1) Thread.sleep(retryDelayMs)
        }
        return false
    }

    /**
     * Try to bind a UDP socket to [port] on the wildcard address with
     * `SO_REUSEADDR = false`. If the bind fails, another process holds
     * the port. Same kernel behaviour upstream RNS hits in
     * `AutoInterface.final_init` (the bug that crashes us). Closes
     * immediately on success — purely a probe.
     */
    fun isAutoInterfacePortFree(port: Int = DEFAULT_AUTO_INTERFACE_DATA_PORT): Boolean =
        runCatching {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = false
                sock.bind(InetSocketAddress(port))
                true
            }
        }.getOrDefault(false)

    /**
     * Policy wrapper — `preferOwnInstance = true` short-circuits to false
     * without probing, otherwise probe + return the result. Port/timeout
     * are overridable for tests; production callers pass nothing.
     */
    fun shouldShareInstance(
        config: ReticulumConfig,
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Boolean =
        !config.preferOwnInstance && isAvailable(port = port, timeoutMs = timeoutMs)

    /**
     * True when AutoInterface can be declared without crashing on
     * `EADDRINUSE` — i.e. nothing currently holds the multicast data
     * port. `preferOwnInstance = true` does NOT override this — even
     * if the user demands their own instance, AutoInterface is
     * physically un-bindable when another app holds the socket.
     */
    fun isAutoInterfaceUsable(
        port: Int = DEFAULT_AUTO_INTERFACE_DATA_PORT,
    ): Boolean = isAutoInterfacePortFree(port)
}
