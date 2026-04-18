package network.columba.app.reticulum.protocol

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import network.columba.app.reticulum.model.InterfaceConfig
import network.columba.app.reticulum.usb.KotlinUSBBridge
import network.reticulum.transport.Transport

internal object RNodeConnectionHelper {
    private const val TAG = "NativeInterfaceFactory"

    suspend fun startRNodeInterface(
        config: InterfaceConfig.RNode,
        appContext: Context?,
        scope: CoroutineScope?,
        onRegisterAndTrack: (String, network.reticulum.interfaces.Interface) -> Unit,
        onMonitorLifecycle: (InterfaceConfig.RNode, network.reticulum.interfaces.rnode.RNodeInterface) -> Unit,
        onEnsureRecovery: (InterfaceConfig.RNode) -> Unit,
    ) {
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "Cannot start RNode interface: appContext not set")
            return
        }
        try {
            val (input, output) =
                when (config.connectionMode) {
                    "usb" -> openUsbSerialWithPermission(ctx, config)
                    "tcp" -> openTcpConnection(config)
                    "ble" -> openBleConnection(ctx, config)
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
                    parentScope = scope,
                    displayImageData =
                        if (config.enableFramebuffer) {
                            network.columba.app.reticulum.call.rnode.ColumbaLogo.FB_DATA
                        } else {
                            null
                        },
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

    private suspend fun openUsbSerialWithPermission(
        ctx: Context,
        config: InterfaceConfig.RNode,
    ): Pair<java.io.InputStream, java.io.OutputStream> {
        val bridge = KotlinUSBBridge.getInstance(ctx)

        val currentDeviceId =
            if (config.usbVendorId != null && config.usbProductId != null) {
                val found = bridge.findDeviceByVidPid(config.usbVendorId, config.usbProductId)
                if (found >= 0) found else config.usbDeviceId
            } else {
                config.usbDeviceId
            }

        if (currentDeviceId != null && !bridge.hasPermission(currentDeviceId)) {
            Log.i(TAG, "Requesting USB permission for device $currentDeviceId")
            val granted = bridge.requestPermissionSuspend(currentDeviceId)
            if (!granted) {
                throw SecurityException("USB permission denied for device $currentDeviceId")
            }
        }

        return bridge.openSerialStreams(
            vendorId = config.usbVendorId,
            productId = config.usbProductId,
            deviceId = config.usbDeviceId,
        )
    }

    private fun openBleConnection(
        ctx: Context,
        config: InterfaceConfig.RNode,
    ): Pair<java.io.InputStream, java.io.OutputStream> {
        val address = config.targetDeviceName
        check(address.isNotBlank()) { "BLE device address/name not configured for RNode" }
        val bleConn =
            network.columba.app.reticulum.call.rnode
                .BluetoothLeConnection(ctx, address)
        return bleConn.connect()
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
