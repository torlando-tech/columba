package network.columba.app.rns.host.flasher

import network.columba.app.rns.host.usb.UsbDeviceInfo
import java.io.InputStream

/** Narrow seam around ESPTool used by the persistence-safe Pyxis updater. */
internal fun interface PyxisEspToolTransport {
    suspend fun flash(request: PyxisFlashRequest): Boolean
}

internal data class PyxisFlashRequest(
    val firmwareZipStream: InputStream,
    val deviceId: Int,
    val board: RNodeBoard,
    val vendorId: Int,
    val productId: Int,
    val consoleImageStream: InputStream?,
    val progressCallback: ESPToolFlasher.ProgressCallback,
)

/**
 * Dedicated Pyxis flash path. It deliberately has no RNode indication or provisioning
 * dependencies and can only submit the validated ZIP with no console image.
 */
internal class PyxisFlashCore(
    private val findDevice: (Int) -> UsbDeviceInfo?,
    private val transport: PyxisEspToolTransport,
    private val emitState: (RNodeFlasher.FlashState) -> Unit,
) {
    suspend fun flash(
        deviceId: Int,
        pyxisPackage: PyxisFirmwarePackage,
    ): Boolean {
        emitState(RNodeFlasher.FlashState.Progress(0, "Starting Pyxis flash..."))
        val usbDevice = findDevice(deviceId)
        if (usbDevice == null) {
            emitState(RNodeFlasher.FlashState.Error("USB device is not connected"))
            return false
        }

        var callbackError = false
        val callback =
            object : ESPToolFlasher.ProgressCallback {
                override fun onProgress(percent: Int, message: String) {
                    emitState(RNodeFlasher.FlashState.Progress(percent, message))
                }

                override fun onError(error: String) {
                    callbackError = true
                    emitState(RNodeFlasher.FlashState.Error(error))
                }

                override fun onComplete() {
                    // ESPTool completion is advisory; only its successful return emits Complete.
                }
            }

        return try {
            val success =
                transport.flash(
                    PyxisFlashRequest(
                        firmwareZipStream = pyxisPackage.openStream(),
                        deviceId = deviceId,
                        board = RNodeBoard.TDECK,
                        vendorId = usbDevice.vendorId,
                        productId = usbDevice.productId,
                        consoleImageStream = null,
                        progressCallback = callback,
                    ),
                )
            if (success && !callbackError) {
                emitState(RNodeFlasher.FlashState.Complete(null))
            } else if (!callbackError) {
                emitState(RNodeFlasher.FlashState.Error("Pyxis flash failed"))
            }
            success && !callbackError
        } catch (e: ESPToolFlasher.ManualBootModeRequired) {
            emitState(RNodeFlasher.FlashState.Error(e.message ?: "Manual boot mode required", recoverable = true))
            false
        } catch (e: Exception) {
            emitState(RNodeFlasher.FlashState.Error("Pyxis flash failed: ${e.message ?: "unknown error"}"))
            false
        }
    }
}
