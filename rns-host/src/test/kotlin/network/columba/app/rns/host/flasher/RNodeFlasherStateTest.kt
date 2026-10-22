package network.columba.app.rns.host.flasher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RNodeFlasher.FlashState sealed class and its variants.
 *
 * These tests verify the behavior of the various flash state data classes
 * including equality, hashcode, and property access.
 */
class FlashStateProgressTest {
    @Test
    fun `Progress state has correct percent and message`() {
        val state = RNodeFlasher.FlashState.Progress(50, FlashText.Raw("Flashing..."))
        assertEquals(50, state.percent)
        assertEquals(FlashText.Raw("Flashing..."), state.message)
    }

    @Test
    fun `Progress states with same values are equal`() {
        val state1 = RNodeFlasher.FlashState.Progress(50, FlashText.Raw("Flashing..."))
        val state2 = RNodeFlasher.FlashState.Progress(50, FlashText.Raw("Flashing..."))
        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
    }

    @Test
    fun `Progress states with different values are not equal`() {
        val state1 = RNodeFlasher.FlashState.Progress(50, FlashText.Raw("Flashing..."))
        val state2 = RNodeFlasher.FlashState.Progress(75, FlashText.Raw("Flashing..."))
        val state3 = RNodeFlasher.FlashState.Progress(50, FlashText.Raw("Almost done..."))
        assertNotEquals(state1, state2)
        assertNotEquals(state1, state3)
    }
}

class FlashStateDetectingTest {
    @Test
    fun `Detecting state has correct message`() {
        val state = RNodeFlasher.FlashState.Detecting(FlashText.Raw("Connecting..."))
        assertEquals(FlashText.Raw("Connecting..."), state.message)
    }

    @Test
    fun `Detecting states with same message are equal`() {
        val state1 = RNodeFlasher.FlashState.Detecting(FlashText.Raw("Connecting..."))
        val state2 = RNodeFlasher.FlashState.Detecting(FlashText.Raw("Connecting..."))
        assertEquals(state1, state2)
    }
}

class FlashStateProvisioningTest {
    @Test
    fun `Provisioning state has correct message`() {
        val state = RNodeFlasher.FlashState.Provisioning(FlashText.Raw("Writing EEPROM..."))
        assertEquals(FlashText.Raw("Writing EEPROM..."), state.message)
    }

    @Test
    fun `Provisioning states with same message are equal`() {
        val state1 = RNodeFlasher.FlashState.Provisioning(FlashText.Raw("Writing EEPROM..."))
        val state2 = RNodeFlasher.FlashState.Provisioning(FlashText.Raw("Writing EEPROM..."))
        assertEquals(state1, state2)
    }
}

class FlashStateNeedsManualResetTest {
    @Test
    fun `NeedsManualReset state has correct board and message`() {
        val state =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.TBEAM_S,
                message = FlashText.Raw("Please reset the device"),
            )
        assertEquals(RNodeBoard.TBEAM_S, state.board)
        assertEquals(FlashText.Raw("Please reset the device"), state.message)
        assertNull(state.firmwareHash)
    }

    @Test
    fun `NeedsManualReset state can include firmware hash`() {
        val hash = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val state =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.HELTEC_V4,
                message = FlashText.Raw("Please reset"),
                firmwareHash = hash,
            )
        assertArrayEquals(hash, state.firmwareHash)
    }

    @Test
    fun `NeedsManualReset equality includes firmware hash`() {
        val hash1 = byteArrayOf(0x01, 0x02, 0x03)
        val hash2 = byteArrayOf(0x01, 0x02, 0x03)
        val hash3 = byteArrayOf(0x04, 0x05, 0x06)

        val state1 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.TDECK,
                message = FlashText.Raw("Reset"),
                firmwareHash = hash1,
            )
        val state2 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.TDECK,
                message = FlashText.Raw("Reset"),
                firmwareHash = hash2,
            )
        val state3 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.TDECK,
                message = FlashText.Raw("Reset"),
                firmwareHash = hash3,
            )

        assertEquals(state1, state2) // Same hash content
        assertNotEquals(state1, state3) // Different hash content
    }

    @Test
    fun `NeedsManualReset with null hash equals another with null hash`() {
        val state1 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.HELTEC_V3,
                message = FlashText.Raw("Reset"),
                firmwareHash = null,
            )
        val state2 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.HELTEC_V3,
                message = FlashText.Raw("Reset"),
                firmwareHash = null,
            )
        assertEquals(state1, state2)
    }

    @Test
    fun `NeedsManualReset with null hash differs from one with hash`() {
        val state1 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.HELTEC_V3,
                message = FlashText.Raw("Reset"),
                firmwareHash = null,
            )
        val state2 =
            RNodeFlasher.FlashState.NeedsManualReset(
                board = RNodeBoard.HELTEC_V3,
                message = FlashText.Raw("Reset"),
                firmwareHash = byteArrayOf(0x01),
            )
        assertNotEquals(state1, state2)
    }
}

class FlashStateErrorTest {
    @Test
    fun `Error state has correct message`() {
        val state = RNodeFlasher.FlashState.Error(FlashText.Raw("Flash failed"))
        assertEquals(FlashText.Raw("Flash failed"), state.message)
        assertTrue(state.recoverable)
    }

    @Test
    fun `Error state can be non-recoverable`() {
        val state = RNodeFlasher.FlashState.Error(FlashText.Raw("Critical failure"), recoverable = false)
        assertFalse(state.recoverable)
    }

    @Test
    fun `Error states with same values are equal`() {
        val state1 = RNodeFlasher.FlashState.Error(FlashText.Raw("Flash failed"), true)
        val state2 = RNodeFlasher.FlashState.Error(FlashText.Raw("Flash failed"), true)
        assertEquals(state1, state2)
    }

    @Test
    fun `Error states with different recoverable are not equal`() {
        val state1 = RNodeFlasher.FlashState.Error(FlashText.Raw("Flash failed"), true)
        val state2 = RNodeFlasher.FlashState.Error(FlashText.Raw("Flash failed"), false)
        assertNotEquals(state1, state2)
    }
}

class FlashStateCompleteTest {
    @Test
    fun `Complete state can have null device info`() {
        val state = RNodeFlasher.FlashState.Complete(null)
        assertNull(state.deviceInfo)
    }

    @Test
    fun `Complete state can include device info`() {
        val info =
            RNodeDeviceInfo(
                platform = RNodePlatform.ESP32,
                mcu = RNodeMcu.ESP32,
                board = RNodeBoard.HELTEC_V3,
                firmwareVersion = "1.78",
                isProvisioned = true,
                isConfigured = false,
                serialNumber = 12345,
                hardwareRevision = 1,
                product = 0xC1.toByte(),
                model = 0x11.toByte(),
            )
        val state = RNodeFlasher.FlashState.Complete(info)
        assertEquals(info, state.deviceInfo)
    }
}

class FlashStateIdleTest {
    @Test
    fun `Idle is singleton object`() {
        val state1 = RNodeFlasher.FlashState.Idle
        val state2 = RNodeFlasher.FlashState.Idle
        assertTrue(state1 === state2)
    }
}

/**
 * Tests verifying the type hierarchy of FlashState.
 */
class FlashStateHierarchyTest {
    @Test
    fun `all FlashState variants are FlashState`() {
        val states =
            listOf<RNodeFlasher.FlashState>(
                RNodeFlasher.FlashState.Idle,
                RNodeFlasher.FlashState.Detecting(FlashText.Raw("test")),
                RNodeFlasher.FlashState.Progress(50, FlashText.Raw("test")),
                RNodeFlasher.FlashState.Provisioning(FlashText.Raw("test")),
                RNodeFlasher.FlashState.NeedsManualReset(RNodeBoard.TBEAM_S, FlashText.Raw("test")),
                RNodeFlasher.FlashState.Complete(null),
                RNodeFlasher.FlashState.Error(FlashText.Raw("test")),
            )

        for (state in states) {
            assertTrue(state is RNodeFlasher.FlashState)
        }
    }
}
