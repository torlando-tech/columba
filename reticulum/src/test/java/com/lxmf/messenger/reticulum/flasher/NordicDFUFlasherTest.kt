package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Nordic DFU flasher and related nRF52 functionality.
 *
 * The Nordic DFU protocol is used for nRF52-based boards like:
 * - RAK4631
 * - Heltec T114
 * - LilyGO T-Echo
 *
 * Key characteristics:
 * - Uses 1200 baud touch to enter bootloader (DFU mode)
 * - Flashes at 115200 baud (not configurable like ESPTool)
 * - Uses SLIP-encoded HCI packets with CRC16
 */
class NordicDFUBoardDetectionTest {
    @Test
    fun `RAK4631 is nRF52 platform`() {
        assertEquals(RNodePlatform.NRF52, RNodeBoard.RAK4631.platform)
    }

    @Test
    fun `Heltec T114 is nRF52 platform`() {
        assertEquals(RNodePlatform.NRF52, RNodeBoard.HELTEC_T114.platform)
    }

    @Test
    fun `T-Echo is nRF52 platform`() {
        assertEquals(RNodePlatform.NRF52, RNodeBoard.TECHO.platform)
    }

    @Test
    fun `nRF52 boards require DFU mode for flashing`() {
        val rak4631Info = createDeviceInfo(RNodeBoard.RAK4631)
        val t114Info = createDeviceInfo(RNodeBoard.HELTEC_T114)
        val techoInfo = createDeviceInfo(RNodeBoard.TECHO)

        assertTrue("RAK4631 should require DFU mode", rak4631Info.requiresDfuMode)
        assertTrue("Heltec T114 should require DFU mode", t114Info.requiresDfuMode)
        assertTrue("T-Echo should require DFU mode", techoInfo.requiresDfuMode)
    }

    @Test
    fun `nRF52 boards do not support ESPTool`() {
        val rak4631Info = createDeviceInfo(RNodeBoard.RAK4631)
        val t114Info = createDeviceInfo(RNodeBoard.HELTEC_T114)
        val techoInfo = createDeviceInfo(RNodeBoard.TECHO)

        assertFalse("RAK4631 should not support ESPTool", rak4631Info.supportsEspTool)
        assertFalse("Heltec T114 should not support ESPTool", t114Info.supportsEspTool)
        assertFalse("T-Echo should not support ESPTool", techoInfo.supportsEspTool)
    }

    @Test
    fun `ESP32 boards support ESPTool not DFU`() {
        val heltecV3Info = createDeviceInfo(RNodeBoard.HELTEC_V3)
        val tbeamInfo = createDeviceInfo(RNodeBoard.TBEAM)

        assertTrue("Heltec V3 should support ESPTool", heltecV3Info.supportsEspTool)
        assertTrue("T-Beam should support ESPTool", tbeamInfo.supportsEspTool)
        assertFalse("Heltec V3 should not require DFU mode", heltecV3Info.requiresDfuMode)
        assertFalse("T-Beam should not require DFU mode", tbeamInfo.requiresDfuMode)
    }

    private fun createDeviceInfo(board: RNodeBoard) =
        RNodeDeviceInfo(
            platform = board.platform,
            mcu =
                when (board.platform) {
                    RNodePlatform.NRF52 -> RNodeMcu.NRF52
                    RNodePlatform.ESP32 -> RNodeMcu.ESP32
                    RNodePlatform.AVR -> RNodeMcu.ATmega1284P
                    RNodePlatform.UNKNOWN -> RNodeMcu.UNKNOWN
                },
            board = board,
            firmwareVersion = "1.78",
            isProvisioned = true,
            isConfigured = true,
            serialNumber = 12345,
            hardwareRevision = 1,
            product = board.productCode,
            model = 0x11.toByte(),
        )
}

/**
 * Tests for baud rate constants used by flashers.
 */
class FlasherBaudRateConstantsTest {
    @Test
    fun `default baud rate constant is 115200`() {
        assertEquals(115200, RNodeConstants.BAUD_RATE_DEFAULT)
    }

    @Test
    fun `DFU touch baud rate constant is 1200`() {
        // 1200 baud touch triggers nRF52 bootloader
        assertEquals(1200, RNodeConstants.BAUD_RATE_DFU_TOUCH)
    }

    @Test
    fun `ESPTool flash baud rate constant is 921600`() {
        // ESPTool uses 921600 for high-speed USB-UART bridges
        assertEquals(921600, RNodeConstants.BAUD_RATE_ESPTOOL)
    }

    @Test
    fun `nRF52 DFU uses 115200 baud for flashing`() {
        // Nordic DFU flashes at 115200 baud (after 1200 touch)
        // This is the same as RNodeConstants.BAUD_RATE_DEFAULT
        assertEquals(RNodeConstants.BAUD_RATE_DEFAULT, 115200)
    }
}

/**
 * Tests for nRF52 board firmware prefix patterns.
 */
class NordicDFUFirmwarePrefixTest {
    @Test
    fun `RAK4631 has correct firmware prefix`() {
        assertTrue(RNodeBoard.RAK4631.firmwarePrefix.contains("rak4631"))
    }

    @Test
    fun `Heltec T114 has correct firmware prefix`() {
        assertTrue(RNodeBoard.HELTEC_T114.firmwarePrefix.contains("heltec_t114"))
    }

    @Test
    fun `T-Echo has correct firmware prefix`() {
        assertTrue(RNodeBoard.TECHO.firmwarePrefix.contains("techo"))
    }
}
