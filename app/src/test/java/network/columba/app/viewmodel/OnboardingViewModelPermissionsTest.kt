package network.columba.app.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.ui.screens.onboarding.OnboardingInterfaceType
import network.columba.app.util.BatteryOptimizationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests the on-resume permission re-check entry points used by the onboarding
 * lifecycle observer. Robolectric is required so [android.os.Build.VERSION.SDK_INT]
 * resolves to a real API level (the SDK-gated branches in
 * checkNotificationPermissionStatus / checkBlePermissionsStatus are unreachable
 * under plain JVM tests where SDK_INT == 0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingViewModelPermissionsTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockIdentityRepository: IdentityRepository
    private lateinit var mockInterfaceRepository: InterfaceRepository
    private lateinit var mockInterfaceConfigManager: InterfaceConfigManager
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()

        mockSettingsRepository = mockk()
        mockIdentityRepository = mockk()
        mockInterfaceRepository = mockk()
        mockInterfaceConfigManager = mockk()

        coEvery { mockSettingsRepository.hasCompletedOnboardingFlow } returns MutableStateFlow(false)
        coEvery { mockSettingsRepository.needsIdentityUnlockFlow } returns MutableStateFlow(false)
        coEvery { mockSettingsRepository.markOnboardingCompleted() } just Runs
        coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
        every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(emptyList())
        every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
        coEvery { mockInterfaceConfigManager.applyInterfaceChanges() } returns kotlin.Result.success(Unit)

        mockkObject(BatteryOptimizationManager)
        mockkStatic(ContextCompat::class)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    private fun createViewModel(): OnboardingViewModel =
        OnboardingViewModel(
            mockSettingsRepository,
            mockIdentityRepository,
            mockInterfaceRepository,
            mockInterfaceConfigManager,
        )

    private fun stubBlePermissionsAllGranted() {
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_SCAN)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_CONNECT)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_ADVERTISE)
        } returns PackageManager.PERMISSION_GRANTED
    }

    private fun stubBlePermissionsScanDenied() {
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_SCAN)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_CONNECT)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.BLUETOOTH_ADVERTISE)
        } returns PackageManager.PERMISSION_GRANTED
    }

    // ========== checkBatteryOptimizationStatus ==========

    @Test
    fun checkBatteryOptimizationStatus_setsExemptToTrueWhenIgnoring() =
        runTest {
            every { BatteryOptimizationManager.isIgnoringBatteryOptimizations(any()) } returns true
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkBatteryOptimizationStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.batteryOptimizationExempt)
            }
        }

    @Test
    fun checkBatteryOptimizationStatus_setsExemptToFalseWhenNotIgnoring() =
        runTest {
            every { BatteryOptimizationManager.isIgnoringBatteryOptimizations(any()) } returns false
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkBatteryOptimizationStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(false, state.batteryOptimizationExempt)
            }
        }

    // ========== checkNotificationPermissionStatus ==========

    @Test
    fun checkNotificationPermissionStatus_setsGrantedTrueWhenPermissionGranted() =
        runTest {
            every {
                ContextCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_GRANTED
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkNotificationPermissionStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.notificationsGranted)
                assertEquals(true, state.notificationsEnabled)
            }
        }

    @Test
    fun checkNotificationPermissionStatus_setsGrantedFalseWhenPermissionDenied() =
        runTest {
            every {
                ContextCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_DENIED
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkNotificationPermissionStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(false, state.notificationsGranted)
                assertEquals(false, state.notificationsEnabled)
            }
        }

    // ========== checkBlePermissionsStatus ==========

    @Test
    fun checkBlePermissionsStatus_setsGrantedTrueWhenAllGranted() =
        runTest {
            stubBlePermissionsAllGranted()
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkBlePermissionsStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.blePermissionsGranted)
                assertEquals(false, state.blePermissionsDenied)
            }
        }

    @Test
    fun checkBlePermissionsStatus_doesNotSetDeniedTrueOnFreshInstall() =
        runTest {
            // Regression guard: on a fresh install, BLE runtime perms are never
            // pre-granted, so anyDenied=true on the very first ON_RESUME. The
            // re-check must NOT flip blePermissionsDenied to true from its initial
            // false state — that would render the red "Permissions denied" status
            // on the BLE card before the user has ever interacted with it.
            // The launcher callback (onBlePermissionsResult) is the sole setter
            // for the denied=true transition.
            stubBlePermissionsScanDenied()
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.checkBlePermissionsStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(false, state.blePermissionsGranted)
                assertEquals(false, state.blePermissionsDenied)
            }
        }

    @Test
    fun checkBlePermissionsStatus_clearsDeniedFlagWhenPermissionsNowGranted() =
        runTest {
            // After a launcher denial flagged blePermissionsDenied=true, a later
            // grant via the system Settings UI fires ON_RESUME with all perms
            // granted; the re-check must clear the denied flag so the card flips
            // back to "granted" without requiring another launcher round-trip.
            stubBlePermissionsAllGranted()
            val viewModel = createViewModel()
            advanceUntilIdle()
            // Simulate prior launcher denial that set the flag.
            viewModel.onBlePermissionsResult(allGranted = false, anyDenied = true)
            advanceUntilIdle()

            viewModel.checkBlePermissionsStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.blePermissionsGranted)
                assertEquals(false, state.blePermissionsDenied)
            }
        }

    @Test
    fun checkBlePermissionsStatus_preservesPriorDeniedFlagOnResumeWithDenials() =
        runTest {
            // After a real launcher denial set blePermissionsDenied=true, a later
            // ON_RESUME with perms still denied must preserve the flag (the user
            // hasn't done anything to change it). Resume only clears, never sets.
            stubBlePermissionsScanDenied()
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onBlePermissionsResult(allGranted = false, anyDenied = true)
            advanceUntilIdle()

            viewModel.checkBlePermissionsStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(false, state.blePermissionsGranted)
                assertEquals(true, state.blePermissionsDenied)
            }
        }

    @Test
    fun checkBlePermissionsStatus_doesNotMutateSelectedInterfaces() =
        runTest {
            // Pre-populate selectedInterfaces with BLE; then run the on-resume re-check
            // with denied permissions. The launcher-callback path is the only place
            // allowed to drop BLE from the selection — ON_RESUME re-checks must be
            // idempotent so a transient denial doesn't undo a user toggle.
            stubBlePermissionsScanDenied()
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            advanceUntilIdle()

            viewModel.checkBlePermissionsStatus(context)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(
                    "BLE must remain in selectedInterfaces after on-resume denial",
                    state.selectedInterfaces.contains(OnboardingInterfaceType.BLE),
                )
                assertFalse(state.blePermissionsGranted)
                // Resume re-check never SETS denied=true; only the launcher callback
                // can flip that flag, and we never invoked it in this test.
                assertFalse(state.blePermissionsDenied)
            }
        }
}
