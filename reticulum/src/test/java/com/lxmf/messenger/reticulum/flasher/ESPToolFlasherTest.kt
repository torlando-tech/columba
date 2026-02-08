package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ESPToolFlasher static methods and constants.
 *
 * These tests cover the critical USB detection logic that determines:
 * 1. Whether a device uses native USB-JTAG-Serial (affects reset sequence)
 * 2. Whether a board uses ESP32-S3 chip (affects bootloader offset)
 *
 * IMPORTANT: USB interface type (native vs USB-UART bridge) is DIFFERENT from
 * chip type (ESP32-S3 vs non-S3). The Heltec V3 uses ESP32-S3 chip but has a
 * CH340 USB-UART bridge, so it needs ClassicReset (not USBJTAGSerialReset).
 */
class ESPToolFlasherNativeUsbDetectionTest {
    @Test
    fun `isNativeUsbDevice returns true for Espressif USB-JTAG-Serial`() {
        // VID 0x303A, PID 0x1001 is the ESP32-S3 native USB-JTAG-Serial peripheral
        assertTrue(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = ESPToolFlasher.ESPRESSIF_VID,
                productId = ESPToolFlasher.ESP32_S3_USB_JTAG_SERIAL_PID,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns true for exact VID PID values`() {
        // Test with literal hex values to ensure constants are correct
        assertTrue(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x303A,
                productId = 0x1001,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for CH340 USB-UART bridge`() {
        // CH340 is commonly used on Heltec V3, LoRa32, etc.
        // VID 0x1A86, PID 0x7523
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x1A86,
                productId = 0x7523,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for CH340C USB-UART bridge`() {
        // CH340C variant
        // VID 0x1A86, PID 0x55D4
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x1A86,
                productId = 0x55D4,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for CP2102 USB-UART bridge`() {
        // CP2102 is commonly used on T-Beam, older Heltec boards
        // VID 0x10C4, PID 0xEA60
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x10C4,
                productId = 0xEA60,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for CP2104 USB-UART bridge`() {
        // CP2104 variant
        // VID 0x10C4, PID 0xEA61
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x10C4,
                productId = 0xEA61,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for FTDI FT232R USB-UART bridge`() {
        // FTDI FT232R
        // VID 0x0403, PID 0x6001
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x0403,
                productId = 0x6001,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for correct VID but wrong PID`() {
        // Espressif VID but different PID (e.g., ESP-PROG debugger)
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x303A,
                productId = 0x1002,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for correct PID but wrong VID`() {
        // Wrong VID but same PID as native USB
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x0000,
                productId = 0x1001,
            ),
        )
    }

    @Test
    fun `isNativeUsbDevice returns false for zero VID and PID`() {
        assertFalse(ESPToolFlasher.isNativeUsbDevice(0, 0))
    }

    @Test
    fun `isNativeUsbDevice returns false for unknown device`() {
        assertFalse(ESPToolFlasher.isNativeUsbDevice(0xFFFF, 0xFFFF))
    }
}

/**
 * Tests for ESP32-S3 chip detection and bootloader offset.
 */
class ESPToolFlasherChipDetectionTest {
    @Test
    fun `isEsp32S3 returns true for T-Beam Supreme`() {
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM_S))
    }

    @Test
    fun `isEsp32S3 returns true for T-Deck`() {
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.TDECK))
    }

    @Test
    fun `isEsp32S3 returns true for Heltec V3`() {
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V3))
    }

    @Test
    fun `isEsp32S3 returns true for Heltec V4`() {
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V4))
    }

    @Test
    fun `isEsp32S3 returns false for T-Beam (original)`() {
        // T-Beam uses ESP32 (not S3)
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM))
    }

    @Test
    fun `isEsp32S3 returns false for Heltec V2`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V2))
    }

    @Test
    fun `isEsp32S3 returns false for LoRa32 V2_0`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.LORA32_V2_0))
    }

    @Test
    fun `isEsp32S3 returns false for LoRa32 V2_1`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.LORA32_V2_1))
    }

    @Test
    fun `isEsp32S3 returns false for RNODE (AVR)`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.RNODE))
    }

    @Test
    fun `isEsp32S3 returns false for HOMEBREW ESP32`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.HOMEBREW))
    }

    @Test
    fun `isEsp32S3 returns false for RAK4631 (nRF52)`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.RAK4631))
    }

    @Test
    fun `isEsp32S3 returns false for Heltec T114 (nRF52)`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_T114))
    }

    @Test
    fun `isEsp32S3 returns false for T-Echo (nRF52)`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.TECHO))
    }

    @Test
    fun `isEsp32S3 returns false for UNKNOWN board`() {
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.UNKNOWN))
    }
}

/**
 * Tests for bootloader offset calculation.
 */
class ESPToolFlasherBootloaderOffsetTest {
    @Test
    fun `bootloader offset is 0x0 for ESP32-S3 boards`() {
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32_S3, ESPToolFlasher.getBootloaderOffset(RNodeBoard.TBEAM_S))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32_S3, ESPToolFlasher.getBootloaderOffset(RNodeBoard.TDECK))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32_S3, ESPToolFlasher.getBootloaderOffset(RNodeBoard.HELTEC_V3))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32_S3, ESPToolFlasher.getBootloaderOffset(RNodeBoard.HELTEC_V4))
    }

    @Test
    fun `bootloader offset is 0x1000 for non-S3 ESP32 boards`() {
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.TBEAM))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.HELTEC_V2))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.LORA32_V2_0))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.LORA32_V2_1))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.HOMEBREW))
    }

    @Test
    fun `bootloader offset is 0x1000 for AVR boards`() {
        // AVR boards use 0x1000 offset (same as non-S3 ESP32)
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.RNODE))
    }

    @Test
    fun `bootloader offset is 0x1000 for nRF52 boards`() {
        // nRF52 boards also use 0x1000 offset (though ESPToolFlasher isn't used for them)
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.RAK4631))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.HELTEC_T114))
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.TECHO))
    }

    @Test
    fun `bootloader offset is 0x1000 for UNKNOWN board`() {
        assertEquals(ESPToolFlasher.OFFSET_BOOTLOADER_ESP32, ESPToolFlasher.getBootloaderOffset(RNodeBoard.UNKNOWN))
    }

    @Test
    fun `ESP32-S3 bootloader offset is 0x0`() {
        assertEquals(0x0, ESPToolFlasher.OFFSET_BOOTLOADER_ESP32_S3)
    }

    @Test
    fun `ESP32 bootloader offset is 0x1000`() {
        assertEquals(0x1000, ESPToolFlasher.OFFSET_BOOTLOADER_ESP32)
    }
}

/**
 * Tests for flash memory offset constants.
 */
class ESPToolFlasherOffsetConstantsTest {
    @Test
    fun `partition table offset is 0x8000`() {
        assertEquals(0x8000, ESPToolFlasher.OFFSET_PARTITIONS)
    }

    @Test
    fun `boot_app0 offset is 0xE000`() {
        assertEquals(0xE000, ESPToolFlasher.OFFSET_BOOT_APP0)
    }

    @Test
    fun `application offset is 0x10000`() {
        assertEquals(0x10000, ESPToolFlasher.OFFSET_APPLICATION)
    }

    @Test
    fun `console image offset is 0x210000`() {
        assertEquals(0x210000, ESPToolFlasher.OFFSET_CONSOLE)
    }
}

/**
 * Tests for USB identifier constants.
 */
class ESPToolFlasherUsbConstantsTest {
    @Test
    fun `Espressif VID constant is 0x303A`() {
        assertEquals(0x303A, ESPToolFlasher.ESPRESSIF_VID)
    }

    @Test
    fun `ESP32-S3 USB-JTAG-Serial PID constant is 0x1001`() {
        assertEquals(0x1001, ESPToolFlasher.ESP32_S3_USB_JTAG_SERIAL_PID)
    }
}

/**
 * Tests verifying the critical distinction between USB interface type and chip type.
 *
 * This is the bug we fixed: Heltec V3 uses ESP32-S3 chip but CH340 USB-UART bridge,
 * so it needs ClassicReset (based on USB interface) not USBJTAGSerialReset (based on chip).
 */
class ESPToolFlasherInterfaceVsChipTypeTest {
    @Test
    fun `Heltec V3 is S3 chip but can have CH340 bridge - not native USB`() {
        // Heltec V3 uses ESP32-S3 chip
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V3))

        // But it uses CH340 USB-UART bridge (VID 0x1A86, PID 0x7523)
        // So isNativeUsbDevice should return false
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x1A86, // CH340 VID
                productId = 0x7523, // CH340 PID
            ),
        )
    }

    @Test
    fun `Heltec V4 is S3 chip but can have CH340 bridge - not native USB`() {
        // Heltec V4 uses ESP32-S3 chip
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V4))

        // CH340 bridge means not native USB
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x1A86,
                productId = 0x7523,
            ),
        )
    }

    @Test
    fun `T-Beam Supreme is S3 chip with native USB`() {
        // T-Beam Supreme uses ESP32-S3 chip
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM_S))

        // AND it uses native USB-JTAG-Serial
        assertTrue(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = ESPToolFlasher.ESPRESSIF_VID,
                productId = ESPToolFlasher.ESP32_S3_USB_JTAG_SERIAL_PID,
            ),
        )
    }

    @Test
    fun `T-Deck is S3 chip with native USB`() {
        // T-Deck uses ESP32-S3 chip
        assertTrue(ESPToolFlasher.isEsp32S3(RNodeBoard.TDECK))

        // AND it uses native USB-JTAG-Serial
        assertTrue(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = ESPToolFlasher.ESPRESSIF_VID,
                productId = ESPToolFlasher.ESP32_S3_USB_JTAG_SERIAL_PID,
            ),
        )
    }

    @Test
    fun `T-Beam original is NOT S3 and uses CP2102 bridge`() {
        // T-Beam (original) uses ESP32 (not S3)
        assertFalse(ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM))

        // Uses CP2102 USB-UART bridge
        assertFalse(
            ESPToolFlasher.isNativeUsbDevice(
                vendorId = 0x10C4, // Silicon Labs VID
                productId = 0xEA60, // CP2102 PID
            ),
        )
    }

    @Test
    fun `chip type and USB interface type are independent`() {
        // Demonstrate that chip type and USB interface are two separate axes:

        // Case 1: S3 chip + native USB (T-Beam Supreme, T-Deck)
        val case1IsS3 = ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM_S)
        val case1IsNativeUsb = ESPToolFlasher.isNativeUsbDevice(0x303A, 0x1001)
        assertTrue("T-Beam Supreme should be S3", case1IsS3)
        assertTrue("T-Beam Supreme should use native USB", case1IsNativeUsb)

        // Case 2: S3 chip + USB-UART bridge (Heltec V3/V4 with CH340)
        val case2IsS3 = ESPToolFlasher.isEsp32S3(RNodeBoard.HELTEC_V3)
        val case2IsNativeUsb = ESPToolFlasher.isNativeUsbDevice(0x1A86, 0x7523)
        assertTrue("Heltec V3 should be S3", case2IsS3)
        assertFalse("Heltec V3 with CH340 should NOT use native USB", case2IsNativeUsb)

        // Case 3: Non-S3 chip + USB-UART bridge (T-Beam original, Heltec V2)
        val case3IsS3 = ESPToolFlasher.isEsp32S3(RNodeBoard.TBEAM)
        val case3IsNativeUsb = ESPToolFlasher.isNativeUsbDevice(0x10C4, 0xEA60)
        assertFalse("T-Beam should NOT be S3", case3IsS3)
        assertFalse("T-Beam should NOT use native USB", case3IsNativeUsb)

        // Case 4: Non-S3 chip + native USB (hypothetical - not common)
        val case4IsS3 = ESPToolFlasher.isEsp32S3(RNodeBoard.HOMEBREW)
        val case4IsNativeUsb = ESPToolFlasher.isNativeUsbDevice(0x303A, 0x1001)
        assertFalse("Homebrew should NOT be S3", case4IsS3)
        assertTrue("Device with Espressif VID/PID would be native USB", case4IsNativeUsb)
    }
}

/**
 * Tests for the ManualBootModeRequired exception.
 */
class ESPToolFlasherManualBootModeRequiredTest {
    @Test
    fun `ManualBootModeRequired exception contains message`() {
        val message = "Test message"
        val exception = ESPToolFlasher.ManualBootModeRequired(message)
        assertEquals(message, exception.message)
    }

    @Test
    fun `ManualBootModeRequired is an Exception`() {
        val exception = ESPToolFlasher.ManualBootModeRequired("Test")
        assertTrue(exception is Exception)
    }
}
