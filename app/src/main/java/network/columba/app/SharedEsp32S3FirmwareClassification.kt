package network.columba.app

internal enum class SharedEsp32S3FirmwareClassification {
    PYXIS,
    RNODE,
    INCONCLUSIVE,
}

internal fun classifySharedEsp32S3Firmware(
    pyxisDetected: Boolean,
    rnodeDetected: Boolean,
): SharedEsp32S3FirmwareClassification =
    when {
        pyxisDetected -> SharedEsp32S3FirmwareClassification.PYXIS
        rnodeDetected -> SharedEsp32S3FirmwareClassification.RNODE
        else -> SharedEsp32S3FirmwareClassification.INCONCLUSIVE
    }

internal fun shouldDismissUsbAction(
    route: String?,
    activeDeviceId: Int?,
    detachedDeviceId: Int,
): Boolean = route?.startsWith("usb_device_action") == true && activeDeviceId == detachedDeviceId
