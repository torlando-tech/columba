package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RNode constants, enums, and data classes.
 */
class RNodeConstantsTest {
    @Test
    fun `KISS constants match protocol specification`() {
        assertEquals(0xC0.toByte(), RNodeConstants.KISS_FEND)
        assertEquals(0xDB.toByte(), RNodeConstants.KISS_FESC)
        assertEquals(0xDC.toByte(), RNodeConstants.KISS_TFEND)
        assertEquals(0xDD.toByte(), RNodeConstants.KISS_TFESC)
    }

    @Test
    fun `platform constants match RNode specification`() {
        assertEquals(0x90.toByte(), RNodeConstants.PLATFORM_AVR)
        assertEquals(0x80.toByte(), RNodeConstants.PLATFORM_ESP32)
        assertEquals(0x70.toByte(), RNodeConstants.PLATFORM_NRF52)
    }

    @Test
    fun `baud rate constants are correct`() {
        assertEquals(115200, RNodeConstants.BAUD_RATE_DEFAULT)
        assertEquals(1200, RNodeConstants.BAUD_RATE_DFU_TOUCH)
        assertEquals(921600, RNodeConstants.BAUD_RATE_ESPTOOL)
    }

    @Test
    fun `detect command constants match protocol`() {
        assertEquals(0x08.toByte(), RNodeConstants.CMD_DETECT)
        assertEquals(0x73.toByte(), RNodeConstants.DETECT_REQ)
        assertEquals(0x46.toByte(), RNodeConstants.DETECT_RESP)
    }
}

class RNodePlatformTest {
    @Test
    fun `fromCode returns correct platform`() {
        assertEquals(RNodePlatform.AVR, RNodePlatform.fromCode(0x90.toByte()))
        assertEquals(RNodePlatform.ESP32, RNodePlatform.fromCode(0x80.toByte()))
        assertEquals(RNodePlatform.NRF52, RNodePlatform.fromCode(0x70.toByte()))
    }

    @Test
    fun `fromCode returns UNKNOWN for invalid code`() {
        assertEquals(RNodePlatform.UNKNOWN, RNodePlatform.fromCode(0x00.toByte()))
        assertEquals(RNodePlatform.UNKNOWN, RNodePlatform.fromCode(0xFF.toByte()))
    }

    @Test
    fun `platform code property matches constant`() {
        assertEquals(RNodeConstants.PLATFORM_AVR, RNodePlatform.AVR.code)
        assertEquals(RNodeConstants.PLATFORM_ESP32, RNodePlatform.ESP32.code)
        assertEquals(RNodeConstants.PLATFORM_NRF52, RNodePlatform.NRF52.code)
    }
}

class RNodeMcuTest {
    @Test
    fun `fromCode returns correct MCU`() {
        assertEquals(RNodeMcu.ATmega1284P, RNodeMcu.fromCode(0x91.toByte()))
        assertEquals(RNodeMcu.ATmega2560, RNodeMcu.fromCode(0x92.toByte()))
        assertEquals(RNodeMcu.ESP32, RNodeMcu.fromCode(0x81.toByte()))
        assertEquals(RNodeMcu.NRF52, RNodeMcu.fromCode(0x71.toByte()))
    }

    @Test
    fun `fromCode returns UNKNOWN for invalid code`() {
        assertEquals(RNodeMcu.UNKNOWN, RNodeMcu.fromCode(0x00.toByte()))
    }
}

class RNodeBoardTest {
    @Test
    fun `fromProductCode returns correct board`() {
        assertEquals(RNodeBoard.RAK4631, RNodeBoard.fromProductCode(0x10))
        assertEquals(RNodeBoard.HELTEC_V3, RNodeBoard.fromProductCode(0xC1.toByte()))
        assertEquals(RNodeBoard.TBEAM, RNodeBoard.fromProductCode(0xE0.toByte()))
    }

    @Test
    fun `fromProductCode returns UNKNOWN for invalid code`() {
        assertEquals(RNodeBoard.UNKNOWN, RNodeBoard.fromProductCode(0x00))
        assertEquals(RNodeBoard.UNKNOWN, RNodeBoard.fromProductCode(0x99.toByte()))
    }

    @Test
    fun `board has correct platform association`() {
        assertEquals(RNodePlatform.NRF52, RNodeBoard.RAK4631.platform)
        assertEquals(RNodePlatform.NRF52, RNodeBoard.HELTEC_T114.platform)
        assertEquals(RNodePlatform.ESP32, RNodeBoard.HELTEC_V3.platform)
        assertEquals(RNodePlatform.ESP32, RNodeBoard.TBEAM.platform)
        assertEquals(RNodePlatform.AVR, RNodeBoard.RNODE.platform)
    }

    @Test
    fun `board has firmware prefix`() {
        assertTrue(RNodeBoard.RAK4631.firmwarePrefix.contains("rak4631"))
        assertTrue(RNodeBoard.HELTEC_V3.firmwarePrefix.contains("heltec"))
        assertTrue(RNodeBoard.TBEAM.firmwarePrefix.contains("tbeam"))
    }

    @Test
    fun `board has display name`() {
        assertTrue(RNodeBoard.RAK4631.displayName.isNotBlank())
        assertTrue(RNodeBoard.HELTEC_V3.displayName.isNotBlank())
        assertTrue(RNodeBoard.TBEAM.displayName.isNotBlank())
    }
}

class RNodeDeviceInfoTest {
    @Test
    fun `isFlashable returns true for known platforms and boards`() {
        val info =
            RNodeDeviceInfo(
                platform = RNodePlatform.NRF52,
                mcu = RNodeMcu.NRF52,
                board = RNodeBoard.RAK4631,
                firmwareVersion = "1.78",
                isProvisioned = true,
                isConfigured = true,
                serialNumber = 12345,
                hardwareRevision = 1,
                product = 0x10,
                model = 0x11,
            )

        assertTrue(info.isFlashable)
    }

    @Test
    fun `isFlashable returns false for unknown platform`() {
        val info =
            RNodeDeviceInfo(
                platform = RNodePlatform.UNKNOWN,
                mcu = RNodeMcu.UNKNOWN,
                board = RNodeBoard.RAK4631,
                firmwareVersion = null,
                isProvisioned = false,
                isConfigured = false,
                serialNumber = null,
                hardwareRevision = null,
                product = 0,
                model = 0,
            )

        assertFalse(info.isFlashable)
    }

    @Test
    fun `isFlashable returns false for unknown board`() {
        val info =
            RNodeDeviceInfo(
                platform = RNodePlatform.ESP32,
                mcu = RNodeMcu.ESP32,
                board = RNodeBoard.UNKNOWN,
                firmwareVersion = null,
                isProvisioned = false,
                isConfigured = false,
                serialNumber = null,
                hardwareRevision = null,
                product = 0,
                model = 0,
            )

        assertFalse(info.isFlashable)
    }

    @Test
    fun `requiresDfuMode returns true for NRF52`() {
        val info = createDeviceInfo(RNodePlatform.NRF52)
        assertTrue(info.requiresDfuMode)
    }

    @Test
    fun `requiresDfuMode returns false for ESP32`() {
        val info = createDeviceInfo(RNodePlatform.ESP32)
        assertFalse(info.requiresDfuMode)
    }

    @Test
    fun `supportsEspTool returns true for ESP32`() {
        val info = createDeviceInfo(RNodePlatform.ESP32)
        assertTrue(info.supportsEspTool)
    }

    @Test
    fun `supportsEspTool returns false for NRF52`() {
        val info = createDeviceInfo(RNodePlatform.NRF52)
        assertFalse(info.supportsEspTool)
    }

    private fun createDeviceInfo(platform: RNodePlatform) =
        RNodeDeviceInfo(
            platform = platform,
            mcu =
                when (platform) {
                    RNodePlatform.NRF52 -> RNodeMcu.NRF52
                    RNodePlatform.ESP32 -> RNodeMcu.ESP32
                    RNodePlatform.AVR -> RNodeMcu.ATmega1284P
                    RNodePlatform.UNKNOWN -> RNodeMcu.UNKNOWN
                },
            board =
                when (platform) {
                    RNodePlatform.NRF52 -> RNodeBoard.RAK4631
                    RNodePlatform.ESP32 -> RNodeBoard.HELTEC_V3
                    else -> RNodeBoard.UNKNOWN
                },
            firmwareVersion = "1.78",
            isProvisioned = true,
            isConfigured = true,
            serialNumber = 12345,
            hardwareRevision = 1,
            product = 0x10,
            model = 0x11,
        )
}
