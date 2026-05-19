package network.columba.app.rns.backend.kt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.model.InterfaceConfig
import network.reticulum.transport.Transport

internal object RNodeConnectionHelper {
    private const val TAG = "NativeInterfaceFactory"

    suspend fun startRNodeInterface(
        config: InterfaceConfig.RNode,
        appContext: Context?,
        hostBridge: RNodeHostBridge?,
        scope: CoroutineScope,
        onRegisterAndTrack: (String, network.reticulum.interfaces.Interface) -> Unit,
        onMonitorLifecycle: (InterfaceConfig.RNode, network.reticulum.interfaces.rnode.RNodeInterface) -> Unit,
        onEnsureRecovery: (InterfaceConfig.RNode) -> Unit,
    ) {
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "Cannot start RNode interface: appContext not set")
            return
        }
        if (hostBridge == null) {
            Log.e(
                TAG,
                "Cannot start RNode interface: RNodeHostBridge not provided — install the :rns-host kotlinBackend Hilt module",
            )
            return
        }
        try {
            val (input, output) =
                when (config.connectionMode) {
                    "usb" -> hostBridge.openUsbSerial(ctx, config.usbVendorId, config.usbProductId, config.usbDeviceId)
                    "tcp" -> openTcpConnection(config)
                    "ble" -> {
                        val address = config.targetDeviceName
                        check(address.isNotBlank()) { "BLE device address/name not configured for RNode" }
                        hostBridge.openBleSerial(ctx, address)
                    }
                    else -> {
                        Log.w(TAG, "RNode connection mode '${config.connectionMode}' not supported")
                        return
                    }
                }

            val iface =
                network.reticulum.interfaces.rnode.RNodeInterface(
                    name = config.name,
                    inputStream = input,
                    outputStream = output,
                    frequency = config.frequency,
                    bandwidth = config.bandwidth.toLong(),
                    txPower = config.txPower,
                    spreadingFactor = config.spreadingFactor,
                    codingRate = config.codingRate,
                    flowControl = config.connectionMode == "usb",
                    activityKeepaliveMs = if (config.connectionMode == "tcp") 3_500L else null,
                    framebufferLineDelayMs = if (config.connectionMode == "tcp") 15L else 0L,
                    framebufferEnableDelayMs = if (config.connectionMode == "tcp") 50L else 0L,
                    // Safe to pass the factory's process-lifetime scope directly (unlike
                    // AndroidBLEDriver, which cancels its scope in shutdown()). RNodeInterface
                    // derives an internal ioScope with a child SupervisorJob tied to this
                    // parent, and its detach() cancels only that ioScope — the factory's root
                    // scope is never touched. If that invariant ever changes upstream, wrap
                    // this in a dedicated child scope the way startBleInterface does.
                    parentScope = scope,
                    displayImageData = if (config.enableFramebuffer) hostBridge.rnodeFramebufferData() else null,
                )
            iface.onPacketReceived = { data, fromInterface ->
                Transport.inbound(
                    data,
                    network.reticulum.interfaces.InterfaceAdapter
                        .getOrCreate(fromInterface),
                )
            }
            iface.start()
            onRegisterAndTrack(config.name, iface)
            onMonitorLifecycle(config, iface)
            Log.i(TAG, "RNode interface started: ${config.name} (${config.connectionMode})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RNode interface ${config.name}: ${e.message}", e)
            onEnsureRecovery(config)
        }
    }

    private fun openTcpConnection(config: InterfaceConfig.RNode): Pair<java.io.InputStream, java.io.OutputStream> {
        val host = config.tcpHost ?: error("TCP host not configured for RNode")
        val socket =
            java.net.Socket(host, config.tcpPort).apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = 500
                setSoLinger(true, 5)
            }

        val rawInput = socket.getInputStream()
        val rawOutput = socket.getOutputStream()

        val input =
            object : java.io.FilterInputStream(rawInput) {
                @Suppress("unused")
                private val retainedSocket = socket

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { retainedSocket.close() }
                    }
                }
            }

        val output =
            object : java.io.FilterOutputStream(rawOutput) {
                @Suppress("unused")
                private val retainedSocket = socket

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { retainedSocket.close() }
                    }
                }
            }

        return Pair(input, output)
    }
}
