package network.columba.app.rns.host.flasher

import network.columba.app.rns.host.R
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
    val verifyFlashWrites: Boolean,
    val performBackupHardReset: Boolean,
    val flashSizeBytes: Int,
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
        emitState(RNodeFlasher.FlashState.Progress(0, flashText(R.string.pyxis_starting)))
        val usbDevice = findDevice(deviceId)
        if (usbDevice == null) {
            emitState(RNodeFlasher.FlashState.Error(flashText(R.string.pyxis_device_disconnected)))
            return false
        }

        var callbackError = false
        val callback =
            object : ESPToolFlasher.ProgressCallback {
                override fun onProgress(
                    percent: Int,
                    stage: ESPToolFlasher.FlashStage,
                    message: FlashText,
                ) {
                    if (stage !in SUPPRESSED_RNODE_STAGES) {
                        emitState(RNodeFlasher.FlashState.Progress(percent, message))
                    }
                }

                override fun onError(error: FlashText) {
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
                        verifyFlashWrites = true,
                        performBackupHardReset = false,
                        flashSizeBytes = 8 * 1024 * 1024,
                        progressCallback = callback,
                    ),
                )
            if (success && !callbackError) {
                emitState(RNodeFlasher.FlashState.Complete(null))
            } else if (!callbackError) {
                emitState(RNodeFlasher.FlashState.Error(flashText(R.string.pyxis_flash_failed)))
            }
            success && !callbackError
        } catch (_: ESPToolFlasher.ManualBootModeRequired) {
            emitState(
                RNodeFlasher.FlashState.Error(
                    flashText(R.string.pyxis_manual_boot),
                    recoverable = true,
                ),
            )
            false
        } catch (e: Exception) {
            emitState(
                RNodeFlasher.FlashState.Error(
                    flashText(R.string.pyxis_flash_failed_error, e.message ?: "unknown error"),
                ),
            )
            false
        }
    }

    private companion object {
        // The T-Deck Plus has no user-visible bootloader/partition-table stage;
        // suppress by stage enum, never by message text (i18n ticket 02).
        val SUPPRESSED_RNODE_STAGES =
            setOf(
                ESPToolFlasher.FlashStage.FLASHING_BOOTLOADER,
                ESPToolFlasher.FlashStage.FLASHING_PARTITION_TABLE,
            )
    }
}
