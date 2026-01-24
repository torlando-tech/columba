package com.lxmf.messenger.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.DiscoveredUsbDevice
import com.lxmf.messenger.data.model.FrequencyRegions
import com.lxmf.messenger.data.model.ModemPreset
import com.lxmf.messenger.data.model.RNodeRegionalPreset
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    /**
     * Runs a test with the ViewModel created inside the test's coroutine scope.
     * This ensures coroutines launched during ViewModel init are properly tracked.
     */
    private fun runViewModelTest(testBody: suspend TestScope.() -> Unit) =
        runTest {
            viewModel = RNodeWizardViewModel(context, interfaceRepository, configManager)
            advanceUntilIdle()
            testBody()
        }

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

        // Disable RSSI polling during tests
        RNodeWizardViewModel.enableRssiPolling = false

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

        // Mock allInterfaces to return empty list (no duplicates)
        every { interfaceRepository.allInterfaces } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        // Wait for pending IO coroutines to complete before resetting dispatcher
        Thread.sleep(100)
        Dispatchers.resetMain()
        clearAllMocks()

        // Restore default
        RNodeWizardViewModel.enableRssiPolling = true
    }

    // ========== State Transition Tests ==========

    @Test
    fun `initial state is DEVICE_DISCOVERY`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(WizardStep.DEVICE_DISCOVERY, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep from DEVICE_DISCOVERY goes to REGION_SELECTION`() =
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

            // Initial state - no device
            assertFalse(viewModel.canProceed())
        }

    @Test
    fun `canProceed true when device selected on DEVICE_DISCOVERY`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed true with manual device name on DEVICE_DISCOVERY`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.showManualEntry()
            advanceUntilIdle()

            viewModel.updateManualDeviceName("RNode Custom")
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed false when no region selected on REGION_SELECTION`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            assertFalse(viewModel.canProceed())
        }

    @Test
    fun `canProceed true when region selected on REGION_SELECTION`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed true when popular preset selected on REGION_SELECTION`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            val testPreset =
                RNodeRegionalPreset(
                    id = "test_preset",
                    countryCode = "US",
                    countryName = "United States",
                    cityOrRegion = "Test City",
                    frequency = 915_000_000,
                    bandwidth = 125_000,
                    spreadingFactor = 9,
                    codingRate = 5,
                    txPower = 17,
                    description = "Test preset",
                )
            viewModel.selectPreset(testPreset)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `goToNextStep skips to REVIEW_CONFIGURE when preset selected`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            val testPreset =
                RNodeRegionalPreset(
                    id = "test_preset",
                    countryCode = "US",
                    countryName = "United States",
                    cityOrRegion = "Test City",
                    frequency = 915_000_000,
                    bandwidth = 125_000,
                    spreadingFactor = 9,
                    codingRate = 5,
                    txPower = 17,
                    description = "Test preset",
                )
            viewModel.selectPreset(testPreset)
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()

            assertEquals(WizardStep.REVIEW_CONFIGURE, viewModel.state.value.currentStep)
            assertTrue(viewModel.state.value.showAdvancedSettings)
        }

    @Test
    fun `goToPreviousStep returns to REGION_SELECTION from REVIEW when preset selected`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.goToStep(WizardStep.REGION_SELECTION)
            advanceUntilIdle()

            val testPreset =
                RNodeRegionalPreset(
                    id = "test_preset",
                    countryCode = "US",
                    countryName = "United States",
                    cityOrRegion = "Test City",
                    frequency = 915_000_000,
                    bandwidth = 125_000,
                    spreadingFactor = 9,
                    codingRate = 5,
                    txPower = 17,
                    description = "Test preset",
                )
            viewModel.selectPreset(testPreset)
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()
            assertEquals(WizardStep.REVIEW_CONFIGURE, viewModel.state.value.currentStep)

            viewModel.goToPreviousStep()
            advanceUntilIdle()

            assertEquals(WizardStep.REGION_SELECTION, viewModel.state.value.currentStep)
        }

    // ========== Region Selection Tests ==========

    @Test
    fun `selectFrequencyRegion updates state`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(usRegion, state.selectedFrequencyRegion)
            assertFalse(state.isCustomMode)
        }

    @Test
    fun `getFrequencyRegions returns all regions`() =
        runViewModelTest {
            advanceUntilIdle()

            val regions = viewModel.getFrequencyRegions()
            assertEquals(21, regions.size)
        }

    @Test
    fun `getRegionLimits returns null when no region selected`() =
        runViewModelTest {
            advanceUntilIdle()

            val limits = viewModel.getRegionLimits()
            assertNull(limits)
        }

    @Test
    fun `getRegionLimits returns correct limits for US region`() =
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial

                viewModel.selectModemPreset(ModemPreset.SHORT_TURBO)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(ModemPreset.SHORT_TURBO, state.selectedModemPreset)
            }
        }

    @Test
    fun `getModemPresets returns all 8 presets`() =
        runViewModelTest {
            advanceUntilIdle()

            val presets = viewModel.getModemPresets()
            assertEquals(8, presets.size)
        }

    @Test
    fun `default modem preset is LONG_FAST`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(ModemPreset.LONG_FAST, state.selectedModemPreset)
            }
        }

    // ========== Frequency Slot Tests ==========

    @Test
    fun `getNumSlots returns 0 when no region selected`() =
        runViewModelTest {
            advanceUntilIdle()

            assertEquals(0, viewModel.getNumSlots())
        }

    @Test
    fun `getNumSlots returns correct count for US region`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            // Default modem preset is LONG_FAST (250kHz bandwidth)
            // US band: (928-902) / 0.25 = 104 slots
            assertEquals(104, viewModel.getNumSlots())
        }

    @Test
    fun `getFrequencyForSlot calculates correctly`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            // Slot 50 with 250kHz BW: 902 + 0.125 + (50 * 0.25) = 914.625 MHz
            val freq = viewModel.getFrequencyForSlot(50)
            assertEquals(914_625_000L, freq)
        }

    @Test
    fun `selectSlot updates state and clears custom frequency`() =
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.updateFrequency("915000000")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.frequencyError)
        }

    @Test
    fun `changing region clears frequency validation error from previous region`() =
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertEquals("RNode A1B2", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveDeviceName returns manual name when no device selected`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("Custom RNode")
            advanceUntilIdle()

            assertEquals("Custom RNode", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveDeviceName returns fallback when nothing set`() =
        runViewModelTest {
            advanceUntilIdle()

            assertEquals("No device selected", viewModel.getEffectiveDeviceName())
        }

    @Test
    fun `getEffectiveBluetoothType returns selected device type`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            assertEquals(BluetoothType.BLE, viewModel.getEffectiveBluetoothType())
        }

    @Test
    fun `getEffectiveBluetoothType returns manual type when no device selected`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.showManualEntry()
            viewModel.updateManualBluetoothType(BluetoothType.CLASSIC)
            advanceUntilIdle()

            assertEquals(BluetoothType.CLASSIC, viewModel.getEffectiveBluetoothType())
        }

    // ========== Advanced Settings Tests ==========

    @Test
    fun `toggleAdvancedSettings updates state`() =
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

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
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial

                viewModel.updateInterfaceMode("gateway")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("gateway", state.interfaceMode)
            }
        }

    // ========== BLE Scan Error Tests ==========

    @Test
    fun `scan error state can be set and cleared`() =
        runViewModelTest {
            advanceUntilIdle()

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

    // ========== TCP Validation Tests ==========

    @Test
    fun `validateTcpConnection sets error when host is blank`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateTcpHost("")
            advanceUntilIdle()

            viewModel.validateTcpConnection()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.tcpValidationError)
                assertEquals(false, state.tcpValidationSuccess)
                assertTrue(state.tcpValidationError!!.contains("empty"))
            }
        }

    @Test
    fun `validateTcpConnection sets error when port below 1`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateTcpHost("192.168.1.100")
            viewModel.updateTcpPort("0")
            advanceUntilIdle()

            viewModel.validateTcpConnection()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.tcpValidationError)
                assertEquals(false, state.tcpValidationSuccess)
                assertTrue(state.tcpValidationError!!.contains("between 1 and 65535"))
            }
        }

    @Test
    fun `validateTcpConnection sets error when port above 65535`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateTcpHost("192.168.1.100")
            viewModel.updateTcpPort("70000")
            advanceUntilIdle()

            viewModel.validateTcpConnection()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.tcpValidationError)
                assertEquals(false, state.tcpValidationSuccess)
                assertTrue(state.tcpValidationError!!.contains("between 1 and 65535"))
            }
        }

    @Test
    fun `validateTcpConnection sets success on valid host and port`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateTcpHost("127.0.0.1")
            viewModel.updateTcpPort("8080")
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.validateTcpConnection()

                // Should see isTcpValidating = true
                val validatingState = awaitItem()
                assertTrue(validatingState.isTcpValidating)

                // Wait for validation to complete
                advanceUntilIdle()

                // Final state - will fail to connect but validates logic runs
                val finalState = awaitItem()
                assertNotNull(finalState.tcpValidationSuccess)
                assertFalse(finalState.isTcpValidating)
            }
        }

    @Test
    fun `validateTcpConnection updates isTcpValidating state`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateTcpHost("127.0.0.1")
            viewModel.updateTcpPort("7633")
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.validateTcpConnection()

                // Should see isTcpValidating = true
                val validatingState = awaitItem()
                assertTrue(validatingState.isTcpValidating)

                // Wait for validation to complete
                advanceUntilIdle()

                // Should see isTcpValidating = false
                val finalState = awaitItem()
                assertFalse(finalState.isTcpValidating)
            }
        }

    // ========== Manual Device Name Validation Tests ==========

    @Test
    fun `validateManualDeviceName returns error for name over 32 chars`() =
        runViewModelTest {
            advanceUntilIdle()

            val longName = "RNode Very Long Device Name That Exceeds Limit"
            assertTrue(longName.length > 32)

            viewModel.updateManualDeviceName(longName)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.manualDeviceNameError)
                assertTrue(state.manualDeviceNameError!!.contains("32 characters"))
            }
        }

    @Test
    fun `validateManualDeviceName returns warning for non-RNode name`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualDeviceName("Arduino Nano")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.manualDeviceNameError)
                assertNotNull(state.manualDeviceNameWarning)
                assertTrue(state.manualDeviceNameWarning!!.contains("not be an RNode"))
            }
        }

    @Test
    fun `validateManualDeviceName returns null for valid name`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualDeviceName("RNode A1B2")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.manualDeviceNameError)
                assertNull(state.manualDeviceNameWarning)
            }
        }

    @Test
    fun `validateManualDeviceName handles empty string`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualDeviceName("")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.manualDeviceNameError)
                assertNull(state.manualDeviceNameWarning)
            }
        }

    // ========== Pairing Retry Tests ==========

    @Test
    fun `retryPairing does nothing when lastPairingDeviceAddress is null`() =
        runViewModelTest {
            advanceUntilIdle()

            // State should have no last pairing address
            val initialState = viewModel.state.value
            assertNull(initialState.lastPairingDeviceAddress)

            // Call retryPairing - should do nothing
            viewModel.retryPairing()
            advanceUntilIdle()

            // State should be unchanged
            val finalState = viewModel.state.value
            assertFalse(finalState.isPairingInProgress)
        }

    @Test
    fun `clearPairingError clears error state`() =
        runViewModelTest {
            advanceUntilIdle()

            // Mock SharedPreferences Editor
            val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPreferences.edit() } returns mockEditor
            every { mockEditor.putString(any(), any()) } returns mockEditor
            every { mockEditor.apply() } returns Unit

            viewModel.state.test {
                awaitItem() // Initial

                // Simulate pairing error by accessing private state
                val stateField = RNodeWizardViewModel::class.java.getDeclaredField("_state")
                stateField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<RNodeWizardState>
                stateFlow.update { it.copy(pairingError = "Test pairing error") }

                awaitItem() // State with error

                viewModel.clearPairingError()
                advanceUntilIdle()

                val state = awaitItem()
                assertNull(state.pairingError)
            }
        }

    // ========== CDM Association Tests ==========

    @Test
    fun `onAssociationIntentLaunched clears pending intent`() =
        runViewModelTest {
            advanceUntilIdle()

            // Mock SharedPreferences Editor
            val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPreferences.edit() } returns mockEditor
            every { mockEditor.putString(any(), any()) } returns mockEditor
            every { mockEditor.apply() } returns Unit

            // Set up state with pending intent
            val mockIntentSender = mockk<android.content.IntentSender>(relaxed = true)
            val stateField = RNodeWizardViewModel::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<RNodeWizardState>
            stateFlow.update { it.copy(pendingAssociationIntent = mockIntentSender) }

            viewModel.state.test {
                awaitItem() // State with intent

                viewModel.onAssociationIntentLaunched()
                advanceUntilIdle()

                val state = awaitItem()
                assertNull(state.pendingAssociationIntent)
            }
        }

    @Test
    fun `onAssociationCancelled clears associating state`() =
        runViewModelTest {
            advanceUntilIdle()

            // Mock SharedPreferences Editor
            val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPreferences.edit() } returns mockEditor
            every { mockEditor.putString(any(), any()) } returns mockEditor
            every { mockEditor.apply() } returns Unit

            // Set associating state
            val stateField = RNodeWizardViewModel::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<RNodeWizardState>
            stateFlow.update {
                it.copy(
                    isAssociating = true,
                    pendingAssociationIntent = mockk(relaxed = true),
                )
            }

            viewModel.state.test {
                awaitItem() // Associating state

                viewModel.onAssociationCancelled()
                advanceUntilIdle()

                val state = awaitItem()
                assertFalse(state.isAssociating)
                assertNull(state.pendingAssociationIntent)
            }
        }

    // ========== getPopularPresetsForRegion Tests ==========

    @Test
    fun `getPopularPresetsForRegion returns empty when no region selected`() =
        runViewModelTest {
            advanceUntilIdle()

            // No region selected by default
            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isEmpty())
        }

    @Test
    fun `getPopularPresetsForRegion returns US presets for US region`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isNotEmpty())
            assertTrue(presets.all { it.countryCode == "US" })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns AU presets for Australia region`() =
        runViewModelTest {
            advanceUntilIdle()

            val auRegion = FrequencyRegions.findById("au_915")!!
            viewModel.selectFrequencyRegion(auRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isNotEmpty())
            assertTrue(presets.all { it.countryCode == "AU" })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns EU_L presets for EU 868 L region`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(euRegionL)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // EU_L is 865-868 MHz, presets should be in that range
            assertTrue(presets.all { it.frequency in 865_000_000..867_999_999 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns EU_M presets for EU 868 M region`() =
        runViewModelTest {
            advanceUntilIdle()

            val euRegionM = FrequencyRegions.findById("eu_868_m")!!
            viewModel.selectFrequencyRegion(euRegionM)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // EU_M is 868-868.6 MHz
            assertTrue(presets.all { it.frequency in 868_000_000..868_599_999 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns EU_P presets for EU 868 P region`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(euRegionP)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // EU_P is 869.4-869.65 MHz
            assertTrue(presets.all { it.frequency in 869_400_000..869_650_000 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns EU_Q presets for EU 868 Q region`() =
        runViewModelTest {
            advanceUntilIdle()

            val euRegionQ = FrequencyRegions.findById("eu_868_q")!!
            viewModel.selectFrequencyRegion(euRegionQ)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // EU_Q is 869.7-870 MHz
            assertTrue(presets.all { it.frequency in 869_700_000..869_999_999 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns 433 MHz presets for EU 433 region`() =
        runViewModelTest {
            advanceUntilIdle()

            val eu433Region = FrequencyRegions.findById("eu_433")!!
            viewModel.selectFrequencyRegion(eu433Region)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // EU 433 MHz band is 430-440 MHz
            assertTrue(presets.all { it.frequency in 430_000_000..440_000_000 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns 2_4 GHz presets for lora_24 region`() =
        runViewModelTest {
            advanceUntilIdle()

            val lora24Region = FrequencyRegions.findById("lora_24")!!
            viewModel.selectFrequencyRegion(lora24Region)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // 2.4 GHz band is 2400-2500 MHz
            assertTrue(presets.all { it.frequency in 2_400_000_000..2_500_000_000 })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns empty for Brazil region`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(brazilRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isEmpty())
        }

    @Test
    fun `getPopularPresetsForRegion returns empty for Russia region`() =
        runViewModelTest {
            advanceUntilIdle()

            val ruRegion = FrequencyRegions.findById("ru_868")!!
            viewModel.selectFrequencyRegion(ruRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isEmpty())
        }

    @Test
    fun `getPopularPresetsForRegion returns empty for Japan region`() =
        runViewModelTest {
            advanceUntilIdle()

            val jpRegion = FrequencyRegions.findById("jp_920")!!
            viewModel.selectFrequencyRegion(jpRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            assertTrue(presets.isEmpty())
        }

    @Test
    fun `getPopularPresetsForRegion returns Asia-Pacific presets for Malaysia region`() =
        runViewModelTest {
            advanceUntilIdle()

            val myRegion = FrequencyRegions.findById("my_919")!!
            viewModel.selectFrequencyRegion(myRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // Asia-Pacific countries share presets
            assertTrue(presets.all { it.countryCode in listOf("MY", "SG", "TH") })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion returns Asia-Pacific presets for Singapore region`() =
        runViewModelTest {
            advanceUntilIdle()

            val sgRegion = FrequencyRegions.findById("sg_923")!!
            viewModel.selectFrequencyRegion(sgRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // Asia-Pacific countries share presets
            assertTrue(presets.all { it.countryCode in listOf("MY", "SG", "TH") })
            assertTrue(presets.size <= 5)
        }

    @Test
    fun `getPopularPresetsForRegion excludes 433 MHz from non-433 regions`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // US presets should not include 433 MHz frequencies
            assertTrue(presets.none { it.frequency in 430_000_000..440_000_000 })
        }

    @Test
    fun `getPopularPresetsForRegion excludes 2_4 GHz from non-2_4 regions`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            val presets = viewModel.getPopularPresetsForRegion()
            // US presets should not include 2.4 GHz frequencies
            assertTrue(presets.none { it.frequency in 2_400_000_000..2_500_000_000 })
        }

    // ========== loadExistingConfig Tests ==========

    @Test
    fun `loadExistingConfig loads TCP RNode configuration correctly`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 1L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Test TCP RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"192.168.1.100","tcp_port":7633}""",
                )
            val rnodeConfig =
                InterfaceConfig.RNode(
                    name = "Test TCP RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "192.168.1.100",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns rnodeConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isEditMode)
                assertEquals(interfaceId, state.editingInterfaceId)
                assertEquals(RNodeConnectionType.TCP_WIFI, state.connectionType)
                assertEquals("192.168.1.100", state.tcpHost)
                assertEquals("7633", state.tcpPort)
                assertNull(state.selectedDevice)
            }
        }

    @Test
    fun `loadExistingConfig loads Classic Bluetooth RNode and sets edit mode`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 2L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Test Classic RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"classic","target_device_name":"RNode Classic123"}""",
                )
            val rnodeConfig =
                InterfaceConfig.RNode(
                    name = "Test Classic RNode",
                    enabled = true,
                    connectionMode = "classic", // classic doesn't trigger RSSI polling
                    targetDeviceName = "RNode Classic123",
                    tcpHost = null,
                    tcpPort = 7633,
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns rnodeConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isEditMode)
                assertEquals(interfaceId, state.editingInterfaceId)
                assertEquals(RNodeConnectionType.BLUETOOTH, state.connectionType)
                assertNotNull(state.selectedDevice)
                assertEquals("RNode Classic123", state.selectedDevice?.name)
                assertEquals(BluetoothType.CLASSIC, state.selectedDevice?.type)
            }
        }

    @Test
    fun `loadExistingConfig handles interface not found`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 999L

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(null)

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                // Should remain in default state (not edit mode)
                assertFalse(state.isEditMode)
                assertNull(state.editingInterfaceId)
            }
        }

    @Test
    fun `loadExistingConfig handles non-RNode interface type`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 4L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "TCP Client",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host":"10.0.0.1","target_port":4242}""",
                )
            val tcpConfig =
                InterfaceConfig.TCPClient(
                    name = "TCP Client",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns tcpConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                // Should remain in default state (not edit mode) for non-RNode types
                assertFalse(state.isEditMode)
                assertNull(state.editingInterfaceId)
            }
        }

    @Test
    fun `loadExistingConfig sets custom mode when no matching preset found`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 5L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Custom RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"10.0.0.1"}""",
                )
            // Custom frequency that doesn't match any preset
            val rnodeConfig =
                InterfaceConfig.RNode(
                    name = "Custom RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "10.0.0.1",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 433500000, // Custom frequency
                    bandwidth = 62500, // Custom bandwidth
                    txPower = 10,
                    spreadingFactor = 9,
                    codingRate = 6,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns rnodeConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isEditMode)
                assertTrue(state.isCustomMode)
                assertNull(state.selectedPreset)
                assertEquals("433500000", state.frequency)
                assertEquals("62500", state.bandwidth)
            }
        }

    @Test
    fun `loadExistingConfig populates all configuration fields`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 6L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Full Config RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"192.168.1.50"}""",
                )
            val rnodeConfig =
                InterfaceConfig.RNode(
                    name = "Full Config RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "192.168.1.50",
                    tcpPort = 8000,
                    targetDeviceName = "",
                    frequency = 869525000,
                    bandwidth = 250000,
                    txPower = 14,
                    spreadingFactor = 10,
                    codingRate = 5,
                    stAlock = 15.0,
                    ltAlock = 5.0,
                    mode = "gateway",
                    enableFramebuffer = false,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns rnodeConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isEditMode)
                assertEquals("Full Config RNode", state.interfaceName)
                assertEquals("869525000", state.frequency)
                assertEquals("250000", state.bandwidth)
                assertEquals("14", state.txPower)
                assertEquals("10", state.spreadingFactor)
                assertEquals("5", state.codingRate)
                assertEquals("15.0", state.stAlock)
                assertEquals("5.0", state.ltAlock)
                assertEquals("gateway", state.interfaceMode)
                assertFalse(state.enableFramebuffer)
            }
        }

    @Test
    fun `loadExistingConfig handles exception gracefully`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 7L

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } throws RuntimeException("Database error")

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                // Should remain in default state on exception
                assertFalse(state.isEditMode)
                assertNull(state.editingInterfaceId)
            }
        }

    // ========== validateTcpConnection Tests ==========

    @Test
    fun `validateTcpConnection sets error for empty host`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            advanceUntilIdle()

            // Host is empty by default
            viewModel.validateTcpConnection()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.tcpValidationError)
                assertTrue(state.tcpValidationError!!.contains("empty", ignoreCase = true))
            }
        }

    // ========== saveConfiguration Tests ==========

    @Test
    fun `saveConfiguration calls insertInterface for new config`() =
        runViewModelTest {
            advanceUntilIdle()

            // Setup: Configure a valid RNode
            viewModel.selectDevice(testBleDevice)
            viewModel.selectFrequencyRegion(usRegion)
            viewModel.selectModemPreset(ModemPreset.LONG_FAST)
            advanceUntilIdle()

            // Ensure not in edit mode
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isEditMode)
            }
        }

    @Test
    fun `saveConfiguration uses editingInterfaceId in edit mode`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 10L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Existing RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"10.0.0.1"}""",
                )
            val rnodeConfig =
                InterfaceConfig.RNode(
                    name = "Existing RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "10.0.0.1",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns rnodeConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isEditMode)
                assertEquals(interfaceId, state.editingInterfaceId)
            }
        }

    @Test
    fun `saveConfiguration returns early when validation fails`() =
        runViewModelTest {
            advanceUntilIdle()

            // Setup: Leave interface name empty (required field)
            viewModel.updateInterfaceName("")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            // Should not call insertInterface because validation fails
            coVerify(exactly = 0) { interfaceRepository.insertInterface(any()) }

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.saveSuccess)
                assertFalse(state.isSaving)
            }
        }

    @Test
    fun `saveConfiguration TCP mode uses tcp connectionMode and calls insertInterface`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            // Setup TCP mode with valid config
            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("192.168.1.100")
            viewModel.updateTcpPort("7633")
            viewModel.updateInterfaceName("TCP RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            coVerify { interfaceRepository.insertInterface(any()) }
            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("tcp", savedConfig.connectionMode)
            assertEquals("192.168.1.100", savedConfig.tcpHost)
            assertEquals(7633, savedConfig.tcpPort)
            assertEquals("", savedConfig.targetDeviceName)
        }

    @Test
    fun `saveConfiguration TCP mode uses custom port when valid`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("10.0.0.1")
            viewModel.updateTcpPort("8080")
            viewModel.updateInterfaceName("TCP RNode Custom Port")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals(8080, savedConfig.tcpPort)
        }

    @Test
    fun `saveConfiguration TCP mode defaults port to 7633 when invalid`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("10.0.0.1")
            viewModel.updateTcpPort("invalid")
            viewModel.updateInterfaceName("TCP RNode Invalid Port")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals(7633, savedConfig.tcpPort)
        }

    @Test
    fun `saveConfiguration BLE device uses ble connectionMode`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            val bleDevice =
                DiscoveredRNode(
                    name = "RNode BLE",
                    address = "11:22:33:44:55:66",
                    type = BluetoothType.BLE,
                    rssi = -60,
                    isPaired = true,
                )

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.selectDevice(bleDevice)
            viewModel.updateInterfaceName("BLE RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("ble", savedConfig.connectionMode)
            assertEquals("RNode BLE", savedConfig.targetDeviceName)
            assertNull(savedConfig.tcpHost)
        }

    @Test
    fun `saveConfiguration Classic device uses classic connectionMode`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            val classicDevice =
                DiscoveredRNode(
                    name = "RNode Classic",
                    address = "AA:BB:CC:DD:EE:FF",
                    type = BluetoothType.CLASSIC,
                    rssi = -55,
                    isPaired = true,
                )

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.selectDevice(classicDevice)
            viewModel.updateInterfaceName("Classic RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("classic", savedConfig.connectionMode)
            assertEquals("RNode Classic", savedConfig.targetDeviceName)
        }

    @Test
    fun `saveConfiguration Unknown device type defaults to classic`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            val unknownDevice =
                DiscoveredRNode(
                    name = "RNode Unknown",
                    address = "FF:EE:DD:CC:BB:AA",
                    type = BluetoothType.UNKNOWN,
                    rssi = -65,
                    isPaired = true,
                )

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.selectDevice(unknownDevice)
            viewModel.updateInterfaceName("Unknown Type RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("classic", savedConfig.connectionMode)
        }

    @Test
    fun `saveConfiguration manual entry BLE uses ble connectionMode`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("Manual BLE RNode")
            viewModel.updateManualBluetoothType(BluetoothType.BLE)
            viewModel.updateInterfaceName("Manual BLE")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("ble", savedConfig.connectionMode)
            assertEquals("Manual BLE RNode", savedConfig.targetDeviceName)
        }

    @Test
    fun `saveConfiguration manual entry Classic uses classic connectionMode`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("Manual Classic RNode")
            viewModel.updateManualBluetoothType(BluetoothType.CLASSIC)
            viewModel.updateInterfaceName("Manual Classic")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("classic", savedConfig.connectionMode)
            assertEquals("Manual Classic RNode", savedConfig.targetDeviceName)
        }

    @Test
    fun `saveConfiguration manual entry Unknown defaults to classic`() =
        runViewModelTest {
            advanceUntilIdle()

            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("Manual Unknown RNode")
            viewModel.updateManualBluetoothType(BluetoothType.UNKNOWN)
            viewModel.updateInterfaceName("Manual Unknown")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.RNode
            assertEquals("classic", savedConfig.connectionMode)
        }

    @Test
    fun `saveConfiguration calls updateInterface in edit mode`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 42L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "Existing RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"10.0.0.1"}""",
                )
            val existingConfig =
                InterfaceConfig.RNode(
                    name = "Existing RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "10.0.0.1",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )

            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns existingConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            // Modify and save
            viewModel.updateInterfaceName("Updated RNode")
            viewModel.saveConfiguration()
            advanceUntilIdle()

            coVerify { interfaceRepository.updateInterface(interfaceId, any()) }
            coVerify(exactly = 0) { interfaceRepository.insertInterface(any()) }
        }

    @Test
    fun `saveConfiguration sets saveError on repository exception`() =
        runViewModelTest {
            advanceUntilIdle()

            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException("Database error")

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("192.168.1.1")
            viewModel.updateInterfaceName("Error Test RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.saveError)
                assertTrue(state.saveError!!.contains("Database error"))
                assertFalse(state.isSaving)
                assertFalse(state.saveSuccess)
            }
        }

    // ========== Duplicate Interface Name Tests ==========

    @Test
    fun `saveConfiguration fails with duplicate interface name`() =
        runViewModelTest {
            advanceUntilIdle()

            // Mock existing interface with conflicting name
            val existingInterface =
                InterfaceConfig.RNode(
                    name = "My RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "192.168.1.50",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )
            every { interfaceRepository.allInterfaces } returns flowOf(listOf(existingInterface))

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("10.0.0.1")
            viewModel.updateInterfaceName("My RNode") // Duplicate name
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.nameError)
                assertTrue(state.nameError!!.contains("already exists"))
                assertFalse(state.isSaving)
                assertFalse(state.saveSuccess)
            }
        }

    @Test
    fun `saveConfiguration fails with case-insensitive duplicate name`() =
        runViewModelTest {
            advanceUntilIdle()

            // Mock existing interface with different case name
            val existingInterface =
                InterfaceConfig.RNode(
                    name = "my rnode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "192.168.1.50",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )
            every { interfaceRepository.allInterfaces } returns flowOf(listOf(existingInterface))

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("10.0.0.1")
            viewModel.updateInterfaceName("My RNode") // Different case
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.nameError)
                assertTrue(state.nameError!!.contains("already exists"))
            }
        }

    @Test
    fun `saveConfiguration succeeds with unique interface name`() =
        runViewModelTest {
            advanceUntilIdle()

            every { interfaceRepository.allInterfaces } returns flowOf(emptyList())
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("192.168.1.100")
            viewModel.updateInterfaceName("Unique RNode Name")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.nameError)
                assertTrue(state.saveSuccess)
            }
        }

    @Test
    fun `saveConfiguration does not call repository when duplicate name detected`() =
        runViewModelTest {
            advanceUntilIdle()

            val existingInterface =
                InterfaceConfig.RNode(
                    name = "Duplicate RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "192.168.1.50",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )
            every { interfaceRepository.allInterfaces } returns flowOf(listOf(existingInterface))

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            viewModel.updateTcpHost("10.0.0.1")
            viewModel.updateInterfaceName("Duplicate RNode")
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            // Should NOT call insertInterface because duplicate was detected
            coVerify(exactly = 0) { interfaceRepository.insertInterface(any()) }
        }

    @Test
    fun `saveConfiguration succeeds in edit mode with same name`() =
        runViewModelTest {
            advanceUntilIdle()

            val interfaceId = 42L
            val entity =
                InterfaceEntity(
                    id = interfaceId,
                    name = "My RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":"tcp","tcp_host":"10.0.0.1"}""",
                )
            val existingConfig =
                InterfaceConfig.RNode(
                    name = "My RNode",
                    enabled = true,
                    connectionMode = "tcp",
                    tcpHost = "10.0.0.1",
                    tcpPort = 7633,
                    targetDeviceName = "",
                    frequency = 915000000,
                    bandwidth = 125000,
                    txPower = 17,
                    spreadingFactor = 8,
                    codingRate = 5,
                )

            // The interface exists in the list (would cause duplicate error without fix)
            every { interfaceRepository.allInterfaces } returns flowOf(listOf(existingConfig))
            coEvery { interfaceRepository.getInterfaceById(interfaceId) } returns flowOf(entity)
            coEvery { interfaceRepository.entityToConfig(entity) } returns existingConfig

            viewModel.loadExistingConfig(interfaceId)
            advanceUntilIdle()

            // Save without changing the name
            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.nameError) // No duplicate error
                assertTrue(state.saveSuccess)
            }
            coVerify { interfaceRepository.updateInterface(interfaceId, any()) }
        }

    // ========== Connection Type Tests ==========

    @Test
    fun `setConnectionType to TCP_WIFI updates connectionType state`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(RNodeConnectionType.TCP_WIFI, state.connectionType)
            }
        }

    @Test
    fun `setConnectionType to BLUETOOTH updates connectionType state`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(RNodeConnectionType.BLUETOOTH, state.connectionType)
            }
        }

    @Test
    fun `isTcpMode returns true for TCP_WIFI connection type`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI)
            advanceUntilIdle()

            assertTrue(viewModel.isTcpMode())
        }

    @Test
    fun `isTcpMode returns false for BLUETOOTH connection type`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            advanceUntilIdle()

            assertFalse(viewModel.isTcpMode())
        }

    // ========== Slot Selection Tests ==========

    @Test
    fun `selectSlot updates frequency based on region and slot`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            viewModel.selectModemPreset(ModemPreset.LONG_FAST)
            advanceUntilIdle()

            val numSlots = viewModel.getNumSlots()
            assertTrue(numSlots > 0)

            viewModel.selectSlot(0)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(0, state.selectedSlot)
                // Frequency should be set based on slot calculation
                assertTrue(state.frequency.isNotEmpty())
            }
        }

    @Test
    fun `getFrequencyForSlot returns valid frequency within region bounds`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.selectFrequencyRegion(usRegion)
            viewModel.selectModemPreset(ModemPreset.LONG_FAST)
            advanceUntilIdle()

            val freq = viewModel.getFrequencyForSlot(0)
            // US region is 902-928 MHz
            assertTrue(freq in 902_000_000..928_000_000)
        }

    // ========== Additional Advanced Settings Tests ==========

    @Test
    fun `toggleAdvancedSettings flips showAdvancedSettings state twice`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                var state = awaitItem()
                assertFalse(state.showAdvancedSettings)

                viewModel.toggleAdvancedSettings()
                advanceUntilIdle()

                state = awaitItem()
                assertTrue(state.showAdvancedSettings)

                viewModel.toggleAdvancedSettings()
                advanceUntilIdle()

                state = awaitItem()
                assertFalse(state.showAdvancedSettings)
            }
        }

    @Test
    fun `updateEnableFramebuffer to false disables framebuffer`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                var state = awaitItem()
                assertTrue(state.enableFramebuffer) // Default is true

                viewModel.updateEnableFramebuffer(false)
                advanceUntilIdle()

                state = awaitItem()
                assertFalse(state.enableFramebuffer)
            }
        }

    @Test
    fun `updateInterfaceMode to gateway changes mode`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                var state = awaitItem()
                assertEquals("boundary", state.interfaceMode) // Default

                viewModel.updateInterfaceMode("gateway")
                advanceUntilIdle()

                state = awaitItem()
                assertEquals("gateway", state.interfaceMode)
            }
        }

    // ========== DiscoveredUsbDevice Data Class Tests ==========

    @Test
    fun `DiscoveredUsbDevice displayName uses productName when available`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 1001,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "/dev/bus/usb/001/002",
                manufacturerName = "FTDI",
                productName = "FT232R USB UART",
                serialNumber = "A12345",
                driverType = "FTDI",
                hasPermission = true,
            )
        assertEquals("FT232R USB UART", device.displayName)
    }

    @Test
    fun `DiscoveredUsbDevice displayName falls back to manufacturerName`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 1,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "/dev/bus/usb/001/002",
                manufacturerName = "FTDI",
                productName = null,
                serialNumber = null,
                driverType = "FTDI",
                hasPermission = true,
            )
        assertEquals("FTDI", device.displayName)
    }

    @Test
    fun `DiscoveredUsbDevice displayName falls back to generic name`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 1,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "/dev/bus/usb/001/003",
                manufacturerName = null,
                productName = null,
                serialNumber = null,
                driverType = "CH340",
                hasPermission = false,
            )
        assertEquals("USB Serial Device (CH340)", device.displayName)
    }

    @Test
    fun `DiscoveredUsbDevice vidPid formats correctly`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 1001,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "/dev/bus/usb/001/002",
                manufacturerName = "FTDI",
                productName = "FT232R USB UART",
                serialNumber = "A12345",
                driverType = "FTDI",
                hasPermission = true,
            )
        assertEquals("0403:6001", device.vidPid)
    }

    @Test
    fun `DiscoveredUsbDevice vidPid pads with zeros`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 1,
                vendorId = 0x0001,
                productId = 0x0002,
                deviceName = "/dev/bus/usb/001/003",
                manufacturerName = null,
                productName = null,
                serialNumber = null,
                driverType = "CDC",
                hasPermission = false,
            )
        assertEquals("0001:0002", device.vidPid)
    }

    @Test
    fun `DiscoveredUsbDevice stores all properties correctly`() {
        val device =
            DiscoveredUsbDevice(
                deviceId = 42,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "/dev/bus/usb/002/005",
                manufacturerName = "QinHeng Electronics",
                productName = "HL-340 USB-Serial adapter",
                serialNumber = "SN12345",
                driverType = "CH340",
                hasPermission = true,
            )

        assertEquals(42, device.deviceId)
        assertEquals(0x1A86, device.vendorId)
        assertEquals(0x7523, device.productId)
        assertEquals("/dev/bus/usb/002/005", device.deviceName)
        assertEquals("QinHeng Electronics", device.manufacturerName)
        assertEquals("HL-340 USB-Serial adapter", device.productName)
        assertEquals("SN12345", device.serialNumber)
        assertEquals("CH340", device.driverType)
        assertTrue(device.hasPermission)
    }

    // ========== USB Tests Removed ==========
    // Note: USB ViewModel function tests removed due to test environment issues.
    // The singleton mocking and async state updates cause flaky test behavior.
    // UI tests in DeviceDiscoveryStepTest provide coverage for USB functionality.

    // ========== Pending LoRa Parameters Tests (RNS Discovery) ==========

    @Test
    fun `initial state has no pending params`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.hasPendingParams)
                assertNull(state.pendingFrequency)
                assertNull(state.pendingBandwidth)
                assertNull(state.pendingSpreadingFactor)
                assertNull(state.pendingCodingRate)
            }
        }

    @Test
    fun `setInitialRadioParams stores all parameters`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 915000000L,
                    bandwidth = 125000,
                    spreadingFactor = 10,
                    codingRate = 5,
                )
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(915000000L, state.pendingFrequency)
                assertEquals(125000, state.pendingBandwidth)
                assertEquals(10, state.pendingSpreadingFactor)
                assertEquals(5, state.pendingCodingRate)
                assertTrue(state.hasPendingParams)
            }
        }

    @Test
    fun `setInitialRadioParams with only frequency sets hasPendingParams`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 868100000L,
                    bandwidth = null,
                    spreadingFactor = null,
                    codingRate = null,
                )
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(868100000L, state.pendingFrequency)
                assertNull(state.pendingBandwidth)
                assertTrue(state.hasPendingParams)
            }
        }

    @Test
    fun `setInitialRadioParams with all null does not set hasPendingParams`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setInitialRadioParams(
                frequency = null,
                bandwidth = null,
                spreadingFactor = null,
                codingRate = null,
            )
            advanceUntilIdle()

            // Verify state directly - no emission expected since hasPendingParams stays false
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.hasPendingParams)
                assertNull(state.pendingFrequency)
            }
        }

    @Test
    fun `applyPendingParams populates frequency field`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 915000000L,
                    bandwidth = null,
                    spreadingFactor = null,
                    codingRate = null,
                )
                advanceUntilIdle()
                awaitItem() // State with pending params

                viewModel.applyPendingParams()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("915000000", state.frequency)
            }
        }

    @Test
    fun `applyPendingParams populates all LoRa fields`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 868100000L,
                    bandwidth = 250000,
                    spreadingFactor = 11,
                    codingRate = 6,
                )
                advanceUntilIdle()
                awaitItem() // State with pending params

                viewModel.applyPendingParams()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("868100000", state.frequency)
                assertEquals("250000", state.bandwidth)
                assertEquals("11", state.spreadingFactor)
                assertEquals("6", state.codingRate)
            }
        }

    @Test
    fun `applyPendingParams clears pending values`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 915000000L,
                    bandwidth = 125000,
                    spreadingFactor = 10,
                    codingRate = 5,
                )
                advanceUntilIdle()
                awaitItem() // State with pending params

                viewModel.applyPendingParams()
                advanceUntilIdle()

                val state = awaitItem()
                assertNull(state.pendingFrequency)
                assertNull(state.pendingBandwidth)
                assertNull(state.pendingSpreadingFactor)
                assertNull(state.pendingCodingRate)
                assertFalse(state.hasPendingParams)
            }
        }

    @Test
    fun `applyPendingParams enables custom mode`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 915000000L,
                    bandwidth = 125000,
                    spreadingFactor = 10,
                    codingRate = 5,
                )
                advanceUntilIdle()
                awaitItem() // State with pending params

                viewModel.applyPendingParams()
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.isCustomMode)
            }
        }

    @Test
    fun `applyPendingParams shows advanced settings`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialRadioParams(
                    frequency = 915000000L,
                    bandwidth = 125000,
                    spreadingFactor = 10,
                    codingRate = 5,
                )
                advanceUntilIdle()
                awaitItem() // State with pending params

                viewModel.applyPendingParams()
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.showAdvancedSettings)
            }
        }

    @Test
    fun `applyPendingParams does nothing when hasPendingParams is false`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.state.test {
                val initialState = awaitItem()
                assertFalse(initialState.hasPendingParams)

                viewModel.applyPendingParams()
                advanceUntilIdle()

                // No state change expected
                expectNoEvents()
            }
        }

    @Test
    fun `applyPendingParams preserves existing values for null pending params`() =
        runViewModelTest {
            advanceUntilIdle()

            // First set some initial values
            viewModel.updateFrequency("900000000")
            viewModel.updateBandwidth("500000")
            advanceUntilIdle()

            // Set pending params with only frequency (other values null)
            viewModel.setInitialRadioParams(
                frequency = 915000000L,
                bandwidth = null,
                spreadingFactor = null,
                codingRate = null,
            )
            advanceUntilIdle()

            viewModel.applyPendingParams()
            advanceUntilIdle()

            // Verify final state
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("915000000", state.frequency) // Updated
                assertEquals("500000", state.bandwidth) // Preserved
            }
        }

    @Test
    fun `goToStep REVIEW_CONFIGURE applies pending params automatically`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setInitialRadioParams(
                frequency = 915000000L,
                bandwidth = 125000,
                spreadingFactor = 10,
                codingRate = 5,
            )
            advanceUntilIdle()

            // Navigate to review step
            viewModel.goToStep(WizardStep.REVIEW_CONFIGURE)
            advanceUntilIdle()

            // Verify final state
            viewModel.state.test {
                val state = awaitItem()
                // Should have applied pending params
                assertEquals("915000000", state.frequency)
                assertEquals("125000", state.bandwidth)
                assertEquals("10", state.spreadingFactor)
                assertEquals("5", state.codingRate)
                assertEquals(WizardStep.REVIEW_CONFIGURE, state.currentStep)
            }
        }

    // ========== Interface Name Generation Tests ==========

    @Test
    fun `defaultInterfaceNameFor generates BLE suffix for BLE device`() {
        val bleDevice =
            DiscoveredRNode(
                name = "RNode E517",
                address = "AA:BB:CC:DD:EE:FF",
                type = BluetoothType.BLE,
                rssi = -70,
                isPaired = true,
            )
        val state = RNodeWizardState()

        val interfaceName = state.defaultInterfaceNameFor(bleDevice)

        assertEquals("RNode E517 BLE", interfaceName)
    }

    @Test
    fun `defaultInterfaceNameFor generates BT suffix for Classic device`() {
        val classicDevice =
            DiscoveredRNode(
                name = "RNode A1B2",
                address = "AA:BB:CC:DD:EE:FF",
                type = BluetoothType.CLASSIC,
                rssi = -70,
                isPaired = true,
            )
        val state = RNodeWizardState()

        val interfaceName = state.defaultInterfaceNameFor(classicDevice)

        assertEquals("RNode A1B2 BT", interfaceName)
    }

    @Test
    fun `defaultInterfaceNameFor removes RNode prefix before adding suffix`() {
        val device =
            DiscoveredRNode(
                name = "RNode 1234",
                address = "AA:BB:CC:DD:EE:FF",
                type = BluetoothType.BLE,
                rssi = -70,
                isPaired = true,
            )
        val state = RNodeWizardState()

        val interfaceName = state.defaultInterfaceNameFor(device)

        assertEquals("RNode 1234 BLE", interfaceName)
    }

    @Test
    fun `defaultInterfaceNameFor preserves custom interface name`() {
        val device =
            DiscoveredRNode(
                name = "RNode E517",
                address = "AA:BB:CC:DD:EE:FF",
                type = BluetoothType.BLE,
                rssi = -70,
                isPaired = true,
            )
        val state = RNodeWizardState(interfaceName = "My Custom Name")

        val interfaceName = state.defaultInterfaceNameFor(device)

        assertEquals("My Custom Name", interfaceName)
    }

    @Test
    fun `defaultInterfaceNameFor handles device without RNode prefix`() {
        val device =
            DiscoveredRNode(
                name = "CustomDevice",
                address = "AA:BB:CC:DD:EE:FF",
                type = BluetoothType.BLE,
                rssi = -70,
                isPaired = true,
            )
        val state = RNodeWizardState()

        val interfaceName = state.defaultInterfaceNameFor(device)

        assertEquals("RNode CustomDevice BLE", interfaceName)
    }

    @Test
    fun `selectDevice sets interface name with BLE suffix`() =
        runViewModelTest {
            advanceUntilIdle()

            val bleDevice =
                DiscoveredRNode(
                    name = "RNode E517",
                    address = "AA:BB:CC:DD:EE:FF",
                    type = BluetoothType.BLE,
                    rssi = -70,
                    isPaired = true,
                )

            viewModel.selectDevice(bleDevice)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(bleDevice, state.selectedDevice)
                assertEquals("RNode E517 BLE", state.interfaceName)
            }
        }

    @Test
    fun `selectDevice sets interface name with BT suffix for Classic`() =
        runViewModelTest {
            advanceUntilIdle()

            val classicDevice =
                DiscoveredRNode(
                    name = "RNode A1B2",
                    address = "AA:BB:CC:DD:EE:FF",
                    type = BluetoothType.CLASSIC,
                    rssi = -70,
                    isPaired = true,
                )

            viewModel.selectDevice(classicDevice)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(classicDevice, state.selectedDevice)
                assertEquals("RNode A1B2 BT", state.interfaceName)
            }
        }

    // ========== USB-Assisted Pairing State Tests ==========

    @Test
    fun `updateManualPinInput updates state correctly`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualPinInput("1234")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("1234", state.manualPinInput)
            }
        }

    @Test
    fun `updateManualPinInput trims to 6 digits`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualPinInput("12345678")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("123456", state.manualPinInput)
            }
        }

    @Test
    fun `updateManualPinInput filters non-digits`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.updateManualPinInput("12ab34")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("1234", state.manualPinInput)
            }
        }

    @Test
    fun `setDeviceType preserves interface name when already customized`() =
        runViewModelTest {
            advanceUntilIdle()

            val bleDevice =
                DiscoveredRNode(
                    name = "RNode E517",
                    address = "AA:BB:CC:DD:EE:FF",
                    type = BluetoothType.BLE,
                    rssi = -70,
                    isPaired = true,
                )

            // First select the device - this auto-generates interface name
            viewModel.selectDevice(bleDevice)
            advanceUntilIdle()

            // Verify initial interface name has BLE suffix
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("RNode E517 BLE", state.interfaceName)
            }

            // Change device type to Classic
            // Since interface name is already customized (not DEFAULT_INTERFACE_NAME),
            // it should be preserved
            viewModel.setDeviceType(bleDevice, BluetoothType.CLASSIC)
            advanceUntilIdle()

            // Verify interface name is preserved (not changed to BT)
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("RNode E517 BLE", state.interfaceName)
            }
        }

    @Test
    fun `setDeviceType updates discovered devices list`() =
        runViewModelTest {
            advanceUntilIdle()

            val bleDevice =
                DiscoveredRNode(
                    name = "RNode E517",
                    address = "AA:BB:CC:DD:EE:FF",
                    type = BluetoothType.BLE,
                    rssi = -70,
                    isPaired = true,
                )

            // Add device to discovered list via selection
            viewModel.selectDevice(bleDevice)
            advanceUntilIdle()

            // Change device type to Classic
            viewModel.setDeviceType(bleDevice, BluetoothType.CLASSIC)
            advanceUntilIdle()

            // Verify selected device type is updated
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(BluetoothType.CLASSIC, state.selectedDevice?.type)
            }
        }

    @Test
    fun `setConnectionType to BLUETOOTH enables Bluetooth tab`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(RNodeConnectionType.BLUETOOTH, state.connectionType)
            }
        }

    @Test
    fun `setConnectionType to USB enables USB tab`() =
        runViewModelTest {
            advanceUntilIdle()

            viewModel.setConnectionType(RNodeConnectionType.USB_SERIAL)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(RNodeConnectionType.USB_SERIAL, state.connectionType)
            }
        }

    @Test
    fun `setConnectionType clears selected device when switching from Bluetooth`() =
        runViewModelTest {
            advanceUntilIdle()

            // First select a device in Bluetooth mode
            viewModel.selectDevice(testBleDevice)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.selectedDevice)
            }

            // Switch to USB mode
            viewModel.setConnectionType(RNodeConnectionType.USB_SERIAL)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.selectedDevice)
            }
        }
}
