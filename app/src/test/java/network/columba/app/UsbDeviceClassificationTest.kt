package network.columba.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceClassificationTest {
    @Test
    fun `positive Pyxis identity takes precedence`() {
        assertEquals(
            SharedEsp32S3FirmwareClassification.PYXIS,
            classifySharedEsp32S3Firmware(pyxisDetected = true, rnodeDetected = true),
        )
    }

    @Test
    fun `positive RNode protocol response identifies configured RNode`() {
        assertEquals(
            SharedEsp32S3FirmwareClassification.RNODE,
            classifySharedEsp32S3Firmware(pyxisDetected = false, rnodeDetected = true),
        )
    }

    @Test
    fun `no protocol identity remains inconclusive`() {
        assertEquals(
            SharedEsp32S3FirmwareClassification.INCONCLUSIVE,
            classifySharedEsp32S3Firmware(pyxisDetected = false, rnodeDetected = false),
        )
    }

    @Test
    fun `detach dismisses action screen for the same device`() {
        assertTrue(shouldDismissUsbAction("usb_device_action?usbDeviceId={usbDeviceId}", 42, 42))
    }

    @Test
    fun `detach does not dismiss unrelated routes or devices`() {
        assertFalse(shouldDismissUsbAction("chats", 42, 42))
        assertFalse(shouldDismissUsbAction("usb_device_action", 7, 42))
    }
}
