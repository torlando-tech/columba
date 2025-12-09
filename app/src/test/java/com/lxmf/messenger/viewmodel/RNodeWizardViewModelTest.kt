package com.lxmf.messenger.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.FrequencyRegions
import com.lxmf.messenger.data.model.ModemPreset
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for RNodeWizardViewModel.
 * Tests wizard navigation, validation, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RNodeWizardViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var viewModel: RNodeWizardViewModel

    private val usRegion = FrequencyRegions.findById("us_915")!!
    private val euRegionP = FrequencyRegions.findById("eu_868_p")!!
    private val brazilRegion = FrequencyRegions.findById("br_902")!!
    private val euRegionL = FrequencyRegions.findById("eu_868_l")!!

    private val testBleDevice =
        DiscoveredRNode(
            name = "RNode A1B2",
            address = "AA:BB:CC:DD:EE:FF",
            type = BluetoothType.BLE,
            rssi = -70,
            isPaired = true,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        interfaceRepository = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)

        // Mock SharedPreferences
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.getString(any(), any()) } returns "{}"
        every { sharedPreferences.edit() } returns mockk(relaxed = true)

        // Mock BluetoothManager as null to avoid Bluetooth calls
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns null

        viewModel = RNodeWizardViewModel(context, interfaceRepository, configManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== State Transition Tests ==========

    @Test
    fun `initial state is DEVICE_DISCOVERY`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(WizardStep.DEVICE_DISCOVERY, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep from DEVICE_DISCOVERY goes to REGION_SELECTION`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.goToNextStep()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(WizardStep.REGION_SELECTION, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep from REGION_SELECTION goes to MODEM_PRESET`() =
        runTest {
            viewModel.goToStep(WizardStep.REGION_SELECTION)
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(WizardStep.MODEM_PRESET, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep from MODEM_PRESET goes to FREQUENCY_SLOT`() =
        runTest {
            // Set up required state
            viewModel.selectFrequencyRegion(usRegion)
            viewModel.goToStep(WizardStep.MODEM_PRESET)
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(WizardStep.FREQUENCY_SLOT, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep from FREQUENCY_SLOT goes to REVIEW_CONFIGURE`() =
        runTest {
            // Set up required state
            viewModel.selectFrequencyRegion(usRegion)
            viewModel.goToStep(WizardStep.FREQUENCY_SLOT)
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(WizardStep.REVIEW_CONFIGURE, state.currentStep)
            }
        }

    @Test
    fun `goToPreviousStep from REGION_SELECTION goes to DEVICE_DISCOVERY`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.goToStep(WizardStep.REGION_SELECTION)
                advanceUntilIdle()
                awaitItem()

                viewModel.goToPreviousStep()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(WizardStep.DEVICE_DISCOVERY, state.currentStep)
            }
        }

    @Test
    fun `goToPreviousStep at DEVICE_DISCOVERY stays at DEVICE_DISCOVERY`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.goToPreviousStep()
                advanceUntilIdle()

                // No new emission expected since state doesn't change
                expectNoEvents()
            }
        }

    // ========== canProceed Tests ==========

    @Test
    fun `canProceed false when no device selected on DEVICE_DISCOVERY`() =
        runTest {
            // Initial state - no device
            assertFalse(viewModel.canProceed())
        }

    @Test
    fun `canProceed true when device selected on DEVICE_DISCOVERY`() =
        runTest {
            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed true with manual device name on DEVICE_DISCOVERY`() =
        runTest {
            viewModel.showManualEntry()
            advanceUntilIdle()

            viewModel.updateManualDeviceName("RNode Custom")
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed false when no region selected on REGION_SELECTION`() =
        runTest {
            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            assertFalse(viewModel.canProceed())
        }

    @Test
    fun `canProceed true when region selected on REGION_SELECTION`() =
        runTest {
            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    // ========== Region Selection Tests ==========

    @Test
    fun `selectFrequencyRegion updates state`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(usRegion, state.selectedFrequencyRegion)
            assertFalse(state.isCustomMode)
        }

    @Test
    fun `getFrequencyRegions returns all regions`() {
        val regions = viewModel.getFrequencyRegions()
        assertEquals(21, regions.size)
    }

    @Test
    fun `getRegionLimits returns null when no region selected`() {
        val limits = viewModel.getRegionLimits()
        assertNull(limits)
    }

    @Test
    fun `getRegionLimits returns correct limits for US region`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val limits = viewModel.getRegionLimits()

            assertNotNull(limits)
            assertEquals(30, limits!!.maxTxPower) // US allows 30 dBm max
            assertEquals(902_000_000L, limits.minFrequency)
            assertEquals(928_000_000L, limits.maxFrequency)
            assertEquals(100, limits.dutyCycle)
        }

    @Test
    fun `getRegionLimits returns correct limits for EU 868-P`() =
        runTest {
            viewModel.selectFrequencyRegion(euRegionP)
            advanceUntilIdle()

            val limits = viewModel.getRegionLimits()

            assertNotNull(limits)
            assertEquals(27, limits!!.maxTxPower)
            assertEquals(869_400_000L, limits.minFrequency)
            assertEquals(869_650_000L, limits.maxFrequency)
            assertEquals(10, limits.dutyCycle) // 10% duty cycle
        }

    // ========== Modem Preset Tests ==========

    @Test
    fun `selectModemPreset updates state`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.selectModemPreset(ModemPreset.SHORT_TURBO)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(ModemPreset.SHORT_TURBO, state.selectedModemPreset)
            }
        }

    @Test
    fun `getModemPresets returns all 8 presets`() {
        val presets = viewModel.getModemPresets()
        assertEquals(8, presets.size)
    }

    @Test
    fun `default modem preset is LONG_FAST`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(ModemPreset.LONG_FAST, state.selectedModemPreset)
            }
        }

    // ========== Frequency Slot Tests ==========

    @Test
    fun `getNumSlots returns 0 when no region selected`() {
        assertEquals(0, viewModel.getNumSlots())
    }

    @Test
    fun `getNumSlots returns correct count for US region`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            // Default modem preset is LONG_FAST (250kHz bandwidth)
            // US band: (928-902) / 0.25 = 104 slots
            assertEquals(104, viewModel.getNumSlots())
        }

    @Test
    fun `getFrequencyForSlot calculates correctly`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            // Slot 50 with 250kHz BW: 902 + 0.125 + (50 * 0.25) = 914.625 MHz
            val freq = viewModel.getFrequencyForSlot(50)
            assertEquals(914_625_000L, freq)
        }

    @Test
    fun `selectSlot updates state and clears custom frequency`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.selectSlot(50)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(50, state.selectedSlot)
                assertNull(state.customFrequency)
                assertNull(state.selectedSlotPreset)
            }
        }

    // ========== Validation Tests ==========

    @Test
    fun `updateInterfaceName clears error on valid name`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateInterfaceName("My RNode")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("My RNode", state.interfaceName)
                assertNull(state.nameError)
            }
        }

    @Test
    fun `updateFrequency sets error for frequency below min`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.updateFrequency("900000000") // Below 902 MHz
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.frequencyError)
                assertTrue(state.frequencyError!!.contains("MHz"))
            }
        }

    @Test
    fun `updateFrequency sets error for frequency above max`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.updateFrequency("930000000") // Above 928 MHz
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.frequencyError)
            }
        }

    @Test
    fun `updateFrequency clears error for valid frequency`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.updateFrequency("915000000")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.frequencyError)
        }

    @Test
    fun `changing region clears frequency validation error from previous region`() =
        runTest {
            // Select Brazil 902 region (902-907.5 MHz range)
            viewModel.selectFrequencyRegion(brazilRegion)
            advanceUntilIdle()

            // Enter an invalid frequency for Brazil (920 MHz is above 907.5 MHz limit)
            viewModel.updateFrequency("920000000")
            advanceUntilIdle()

            // Verify the error is set
            val stateWithError = viewModel.state.value
            assertNotNull(stateWithError.frequencyError)
            assertTrue(stateWithError.frequencyError!!.contains("MHz"))

            // Now change to EU 868 L region
            viewModel.selectFrequencyRegion(euRegionL)
            advanceUntilIdle()

            val stateAfterRegionChange = viewModel.state.value
            // Error should be cleared even though we programmatically set a new frequency
            assertNull(stateAfterRegionChange.frequencyError)
            // New frequency should be set to the EU region's default
            assertEquals(euRegionL.frequency.toString(), stateAfterRegionChange.frequency)
        }

    @Test
    fun `updateBandwidth sets error for bandwidth below 7800`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateBandwidth("5000")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.bandwidthError)
            }
        }

    @Test
    fun `updateBandwidth sets error for bandwidth above 1625000`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateBandwidth("2000000")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.bandwidthError)
            }
        }

    @Test
    fun `updateSpreadingFactor sets error for SF below 7`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateSpreadingFactor("6")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.spreadingFactorError)
            }
        }

    @Test
    fun `updateSpreadingFactor sets error for SF above 12`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateSpreadingFactor("13")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.spreadingFactorError)
            }
        }

    @Test
    fun `updateCodingRate sets error for CR below 5`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateCodingRate("4")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.codingRateError)
            }
        }

    @Test
    fun `updateCodingRate sets error for CR above 8`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateCodingRate("9")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.codingRateError)
            }
        }

    @Test
    fun `updateTxPower sets error when exceeding region max`() =
        runTest {
            viewModel.selectFrequencyRegion(usRegion) // Max 30 dBm
            advanceUntilIdle()

            viewModel.updateTxPower("35") // Above 30 dBm
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.txPowerError)
                assertTrue(state.txPowerError!!.contains("30"))
            }
        }

    @Test
    fun `updateStAlock sets error when exceeding duty cycle limit`() =
        runTest {
            viewModel.selectFrequencyRegion(euRegionP) // 10% duty cycle
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.updateStAlock("15") // 15% > 10%
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.stAlockError)
                assertTrue(state.stAlockError!!.contains("regional"))
            }
        }

    @Test
    fun `updateLtAlock sets error when exceeding 100 percent`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateLtAlock("101")
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull(state.ltAlockError)
            }
        }

    // ========== Device Selection Tests ==========

    @Test
    fun `selectDevice updates state`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.selectDevice(testBleDevice)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(testBleDevice, state.selectedDevice)
                assertFalse(state.showManualEntry)
            }
        }

    @Test
    fun `showManualEntry clears selected device`() =
        runTest {
            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state with device

                viewModel.showManualEntry()
                advanceUntilIdle()

                val state = awaitItem()
                assertNull(state.selectedDevice)
                assertTrue(state.showManualEntry)
            }
        }

    @Test
    fun `hideManualEntry updates state`() =
        runTest {
            viewModel.showManualEntry()
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.hideManualEntry()
                advanceUntilIdle()

                val state = awaitItem()
                assertFalse(state.showManualEntry)
            }
        }

    @Test
    fun `updateManualBluetoothType updates state`() =
        runTest {
            viewModel.showManualEntry()
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Current state

                viewModel.updateManualBluetoothType(BluetoothType.BLE)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(BluetoothType.BLE, state.manualBluetoothType)
            }
        }

    // ========== Helper Method Tests ==========

    @Test
    fun `getEffectiveDeviceName returns selected device name`() =
        runTest {
            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertEquals("RNode A1B2", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveDeviceName returns manual name when no device selected`() =
        runTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("Custom RNode")
            advanceUntilIdle()

            assertEquals("Custom RNode", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveDeviceName returns fallback when nothing set`() =
        runTest {
            assertEquals("No device selected", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveBluetoothType returns selected device type`() =
        runTest {
            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertEquals(BluetoothType.BLE, viewModel.getEffectiveBluetoothType())
        }

    @Test
    fun `getEffectiveBluetoothType returns manual type when no device selected`() =
        runTest {
            viewModel.showManualEntry()
            viewModel.updateManualBluetoothType(BluetoothType.CLASSIC)
            advanceUntilIdle()

            assertEquals(BluetoothType.CLASSIC, viewModel.getEffectiveBluetoothType())
        }

    // ========== Advanced Settings Tests ==========

    @Test
    fun `toggleAdvancedSettings updates state`() =
        runTest {
            viewModel.state.test {
                val initial = awaitItem()
                assertFalse(initial.showAdvancedSettings)

                viewModel.toggleAdvancedSettings()
                advanceUntilIdle()

                val toggled = awaitItem()
                assertTrue(toggled.showAdvancedSettings)

                viewModel.toggleAdvancedSettings()
                advanceUntilIdle()

                val toggledBack = awaitItem()
                assertFalse(toggledBack.showAdvancedSettings)
            }
        }

    @Test
    fun `updateEnableFramebuffer updates state`() =
        runTest {
            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.enableFramebuffer) // Default true

                viewModel.updateEnableFramebuffer(false)
                advanceUntilIdle()

                val updated = awaitItem()
                assertFalse(updated.enableFramebuffer)
            }
        }

    @Test
    fun `updateInterfaceMode updates state`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateInterfaceMode("boundary")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("boundary", state.interfaceMode)
            }
        }

    // ========== BLE Scan Error Tests ==========

    @Test
    fun `scan error state can be set and cleared`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // Directly access setScanError method via reflection to test error handling
                // In production, this is called from onScanFailed callback
                val setScanErrorMethod =
                    RNodeWizardViewModel::class.java.getDeclaredMethod("setScanError", String::class.java)
                setScanErrorMethod.isAccessible = true
                setScanErrorMethod.invoke(viewModel, "BLE scan failed: 1")
                advanceUntilIdle()

                val stateWithError = awaitItem()
                assertEquals("BLE scan failed: 1", stateWithError.scanError)
                assertFalse("Scanning should be stopped on error", stateWithError.isScanning)
            }
        }
}
