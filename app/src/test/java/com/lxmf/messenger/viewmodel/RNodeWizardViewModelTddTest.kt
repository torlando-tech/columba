package com.lxmf.messenger.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.FrequencyRegions
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * TDD tests for RNodeWizardViewModel.
 * Tests added during TDD implementation of PR #36 critical/high priority fixes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RNodeWizardViewModelTddTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var viewModel: RNodeWizardViewModel

    private val usRegion = FrequencyRegions.findById("us_915")!!
    private val euRegionL = FrequencyRegions.findById("eu_868_l")!!

    private val testBleDevice =
        DiscoveredRNode(
            name = "RNode A1B2",
            address = "AA:BB:CC:DD:EE:FF",
            type = BluetoothType.BLE,
            rssi = -70,
            isPaired = true,
        )

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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        interfaceRepository = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.getString(any(), any()) } returns "{}"
        every { sharedPreferences.edit() } returns mockk(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Issue #3: Manual Device Name Validation Tests ==========

    @Test
    fun `manual device name rejects empty input for proceed`() =
        runViewModelTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(
                    "Should not be able to proceed with empty device name",
                    state.selectedDevice != null ||
                        (state.showManualEntry && state.manualDeviceName.isNotBlank()),
                )
            }
        }

    @Test
    fun `manual device name rejects names over 32 characters`() =
        runViewModelTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("A".repeat(33))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull("Should have error for name over 32 characters", state.manualDeviceNameError)
                assertEquals("Device name must be 32 characters or less", state.manualDeviceNameError)
            }
        }

    @Test
    fun `manual device name accepts valid RNode name`() =
        runViewModelTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("RNode A1B2")
            viewModel.updateManualBluetoothType(BluetoothType.BLE)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull("Should have no error for valid RNode name", state.manualDeviceNameError)
                assertTrue(
                    "Should be able to proceed with valid device name",
                    state.showManualEntry && state.manualDeviceName.isNotBlank(),
                )
            }
        }

    @Test
    fun `manual device name shows warning for non-RNode name`() =
        runViewModelTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("My Device")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNotNull("Should have warning for non-RNode name", state.manualDeviceNameWarning)
                assertNull("Should have no error for non-RNode name", state.manualDeviceNameError)
            }
        }

    @Test
    fun `manual device name at exactly 32 characters is valid`() =
        runViewModelTest {
            viewModel.showManualEntry()
            viewModel.updateManualDeviceName("A".repeat(32))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull("32 characters should be valid (no error)", state.manualDeviceNameError)
            }
        }

    // ========== Issue #4: Regulatory Warning Tests ==========

    @Test
    fun `custom mode allows proceeding without region selection`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep() // REGION_SELECTION
            advanceUntilIdle()

            // Enable custom mode (no region selected)
            viewModel.enableCustomMode()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue("Custom mode should be enabled", state.isCustomMode)
                assertNull("No region should be selected in custom mode", state.selectedFrequencyRegion)
                assertTrue("Should be able to proceed in custom mode", viewModel.canProceed())
            }
        }

    @Test
    fun `custom mode shows regulatory warning when no region selected`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.enableCustomMode()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue("Should show regulatory warning in custom mode", state.showRegulatoryWarning)
                assertEquals(
                    "No region selected. You are responsible for ensuring compliance with local regulations.",
                    state.regulatoryWarningMessage,
                )
            }
        }

    @Test
    fun `custom mode with region selected has no regulatory warning`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse("Should not show regulatory warning with region selected", state.showRegulatoryWarning)
            }
        }

    @Test
    fun `custom mode without airtime limits shows duty cycle warning for EU`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(euRegionL)
            advanceUntilIdle()

            viewModel.updateStAlock("")
            viewModel.updateLtAlock("")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue("Should show regulatory warning when airtime limits cleared for EU", state.showRegulatoryWarning)
                assertTrue(
                    "Warning should mention duty cycle",
                    state.regulatoryWarningMessage?.contains("duty cycle") == true,
                )
            }
        }

    // ========== Issue #2: State Preservation on Navigation Tests ==========

    @Test
    fun `user-modified frequency error is preserved when navigating forward from region`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.updateFrequency("999999999999")
            advanceUntilIdle()

            viewModel.state.test {
                val stateBeforeNav = awaitItem()
                assertNotNull("Frequency error should exist before navigation", stateBeforeNav.frequencyError)
            }

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val stateAfterNav = awaitItem()
                assertNotNull(
                    "User-modified frequency error should be preserved after navigation",
                    stateAfterNav.frequencyError,
                )
            }
        }

    @Test
    fun `region defaults replace values when user re-selects same region`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.updateFrequency("920000000")
            advanceUntilIdle()

            viewModel.state.test {
                val stateBeforeReselect = awaitItem()
                assertEquals("920000000", stateBeforeReselect.frequency)
            }

            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.state.test {
                val stateAfterReselect = awaitItem()
                assertEquals(
                    "Region defaults should be applied when re-selecting region",
                    usRegion.frequency.toString(),
                    stateAfterReselect.frequency,
                )
            }
        }

    @Test
    fun `navigation forward preserves user-modified txPower error`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(usRegion)
            advanceUntilIdle()

            viewModel.updateTxPower("999")
            advanceUntilIdle()

            viewModel.state.test {
                val stateBeforeNav = awaitItem()
                assertNotNull("TX power error should exist before navigation", stateBeforeNav.txPowerError)
            }

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val stateAfterNav = awaitItem()
                assertNotNull(
                    "User-modified TX power error should be preserved after navigation",
                    stateAfterNav.txPowerError,
                )
            }
        }

    @Test
    fun `navigation forward does not clear errors when user has modified fields`() =
        runViewModelTest {
            viewModel.selectDevice(testBleDevice)
            viewModel.goToNextStep()
            viewModel.selectFrequencyRegion(euRegionL)
            advanceUntilIdle()

            viewModel.updateStAlock("")
            advanceUntilIdle()

            viewModel.goToNextStep()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(
                    "User-cleared stAlock should be preserved after navigation",
                    "",
                    state.stAlock,
                )
            }
        }
}
