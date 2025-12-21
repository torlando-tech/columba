package com.lxmf.messenger.viewmodel

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.service.InterfaceConfigManager
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for safety and resource management in RNodeWizardViewModel.
 * Extracted from RNodeWizardViewModelTest to keep class size under detekt limits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RNodeWizardViewModelSafetyTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScheduler = testDispatcher.scheduler

    private lateinit var viewModel: RNodeWizardViewModel
    private lateinit var mockContext: Context
    private lateinit var mockInterfaceRepository: InterfaceRepository
    private lateinit var mockConfigManager: InterfaceConfigManager
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockSharedPreferences: SharedPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockInterfaceRepository = mockk(relaxed = true)
        mockConfigManager = mockk(relaxed = true)
        mockBluetoothManager = mockk(relaxed = true)
        mockBluetoothAdapter = mockk(relaxed = true)
        mockSharedPreferences = mockk(relaxed = true)

        // Setup mocks for BluetoothManager - return null adapter to test error handling
        every { mockContext.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockBluetoothManager
        every { mockBluetoothManager.adapter } returns null // Simulate no Bluetooth

        // Mock shared preferences for device type cache
        every {
            mockContext.getSharedPreferences("rnode_device_types", Context.MODE_PRIVATE)
        } returns mockSharedPreferences
        every { mockSharedPreferences.edit() } returns mockk(relaxed = true)

        viewModel = RNodeWizardViewModel(mockContext, mockInterfaceRepository, mockConfigManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Permission/Bluetooth Availability Tests ==========

    @Test
    fun `startDeviceScan sets error when bluetooth adapter is null`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.startDeviceScan()
                advanceUntilIdle()

                val state = awaitItem()
                assertNotNull("Should have scan error when adapter is null", state.scanError)
                assertFalse("Should not be scanning", state.isScanning)
            }
        }

    // ========== Memory Leak Prevention Tests ==========

    @Test
    fun `rssiPollingJob is cancelled when onCleared is called`() =
        runTest {
            // Access private rssiPollingJob field via reflection
            val rssiPollingJobField =
                RNodeWizardViewModel::class.java.getDeclaredField("rssiPollingJob")
            rssiPollingJobField.isAccessible = true

            // Access private startRssiPolling method
            val startRssiPollingMethod =
                RNodeWizardViewModel::class.java.getDeclaredMethod("startRssiPolling")
            startRssiPollingMethod.isAccessible = true

            // Start RSSI polling to create a job
            startRssiPollingMethod.invoke(viewModel)
            testScheduler.advanceTimeBy(100)

            // Verify job was created and is active
            val jobBefore = rssiPollingJobField.get(viewModel) as? kotlinx.coroutines.Job
            assertNotNull("RSSI polling job should be created after startRssiPolling()", jobBefore)
            assertTrue("RSSI polling job should be active", jobBefore!!.isActive)

            // Call onCleared() via reflection (it's protected)
            val onClearedMethod =
                RNodeWizardViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(viewModel)

            // Verify the job is cancelled after cleanup
            val jobAfter = rssiPollingJobField.get(viewModel) as? kotlinx.coroutines.Job
            assertTrue(
                "RSSI polling job should be cancelled or null after onCleared()",
                jobAfter == null || jobAfter.isCancelled,
            )
        }

    @Test
    fun `rssiPollingJob is cancelled when navigating away from DEVICE_DISCOVERY`() =
        runTest {
            // Access private rssiPollingJob field via reflection
            val rssiPollingJobField =
                RNodeWizardViewModel::class.java.getDeclaredField("rssiPollingJob")
            rssiPollingJobField.isAccessible = true

            // Access private startRssiPolling method
            val startRssiPollingMethod =
                RNodeWizardViewModel::class.java.getDeclaredMethod("startRssiPolling")
            startRssiPollingMethod.isAccessible = true

            // Start RSSI polling
            startRssiPollingMethod.invoke(viewModel)
            testScheduler.advanceTimeBy(100)

            // Verify job is active
            val jobBefore = rssiPollingJobField.get(viewModel) as? kotlinx.coroutines.Job
            assertNotNull("RSSI polling job should be active", jobBefore)
            assertTrue("RSSI polling job should be active", jobBefore!!.isActive)

            // Navigate to next step (leaving DEVICE_DISCOVERY)
            viewModel.goToNextStep()
            advanceUntilIdle()

            // Verify job is cancelled
            val jobAfter = rssiPollingJobField.get(viewModel) as? kotlinx.coroutines.Job
            assertTrue(
                "RSSI polling job should be cancelled when leaving DEVICE_DISCOVERY",
                jobAfter == null || jobAfter.isCancelled,
            )
        }
}
