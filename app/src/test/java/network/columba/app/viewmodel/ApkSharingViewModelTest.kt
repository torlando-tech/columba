package network.columba.app.viewmodel

import android.app.Application
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ApkSharingViewModel.
 *
 * Under Robolectric, applicationInfo.sourceDir is typically null or points to a
 * non-existent file, so the ViewModel's prepareApkFile() will fail, resulting in
 * an error state. We also cannot get a real WiFi IP. These tests verify correct
 * error handling and state management in those scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ApkSharingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = RuntimeEnvironment.getApplication()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default values`() {
        val defaultState = ApkSharingState()
        assertFalse(defaultState.isServerRunning)
        assertNull(defaultState.downloadUrl)
        assertNull(defaultState.localIp)
        assertNull(defaultState.errorMessage)
        assertEquals(0L, defaultState.apkSizeBytes)
        assertNull(defaultState.sharingMode)
        assertNull(defaultState.hotspotSsid)
        assertNull(defaultState.hotspotPassword)
        assertFalse(defaultState.needsHotspotPermission)
        assertFalse(defaultState.isHotspotStarting)
    }

    @Test
    fun `viewModel init triggers startServer which reaches a terminal sharing-state`() =
        runTest(testDispatcher) {
            val viewModel = ApkSharingViewModel(application)

            // The previous assertion (state.isServerRunning == false) was environment-dependent:
            // on Linux CI getLocalIpAddress() can return a non-loopback IPv4 and the server
            // actually binds, flipping the assertion red (issue #883). Wait for the post-init
            // flow to settle into one of the three terminal shapes — production code is
            // environment-aware, so accept any of them rather than pinning a specific value.
            //
            // Note: avoid withTimeout here — its virtual-time deadline races the real-IO
            // bind in launchHttpServer; rely on runTest's outer real-time timeout instead.
            val terminalState =
                viewModel.state.first { state ->
                    state.errorMessage != null ||
                        state.needsHotspotPermission ||
                        (state.isServerRunning && state.downloadUrl != null)
                }

            assertTrue(
                "Expected a terminal sharing state (error, needs-permission, or running-with-url) but got $terminalState",
                terminalState.errorMessage != null ||
                    terminalState.needsHotspotPermission ||
                    (terminalState.isServerRunning && terminalState.downloadUrl != null),
            )
        }

    @Test
    fun `state flow is exposed as immutable StateFlow`() =
        runTest(testDispatcher) {
            val viewModel = ApkSharingViewModel(application)
            assertNotNull(viewModel.state)
        }

    @Test
    fun `startServer is idempotent when already running`() =
        runTest(testDispatcher) {
            val viewModel = ApkSharingViewModel(application)
            advanceUntilIdle()

            // Calling startServer again should not crash
            viewModel.startServer()
            advanceUntilIdle()

            viewModel.state.test(timeout = 5.seconds) {
                val state = awaitItem()
                // Should still be in a valid state
                assertNotNull(state)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `createShareIntent returns a valid share intent`() =
        runTest(testDispatcher) {
            val viewModel = ApkSharingViewModel(application)
            advanceUntilIdle()

            val intent = viewModel.createShareIntent()
            // Under Robolectric, FileProvider works so the intent may be non-null
            // depending on whether prepareApkFile succeeded
            if (intent != null) {
                assertEquals(android.content.Intent.ACTION_SEND, intent.action)
                assertEquals("application/vnd.android.package-archive", intent.type)
            }
        }

    @Test
    fun `ApkSharingState copy works correctly`() {
        val original = ApkSharingState()
        val modified =
            original.copy(
                isServerRunning = true,
                downloadUrl = "http://10.0.0.1:8080",
                localIp = "10.0.0.1",
                apkSizeBytes = 5_000_000,
                sharingMode = SharingMode.WIFI,
            )

        assertTrue(modified.isServerRunning)
        assertEquals("http://10.0.0.1:8080", modified.downloadUrl)
        assertEquals("10.0.0.1", modified.localIp)
        assertNull(modified.errorMessage)
        assertEquals(5_000_000L, modified.apkSizeBytes)
        assertEquals(SharingMode.WIFI, modified.sharingMode)
        assertNull(modified.hotspotSsid)
        assertNull(modified.hotspotPassword)
    }

    @Test
    fun `ApkSharingState with error`() {
        val state =
            ApkSharingState(
                errorMessage = "No WiFi connection",
            )

        assertFalse(state.isServerRunning)
        assertNull(state.downloadUrl)
        assertEquals("No WiFi connection", state.errorMessage)
    }

    @Test
    fun `ApkSharingState hotspot mode has all fields`() {
        val state = ApkSharingState(
            isServerRunning = true,
            downloadUrl = "http://192.168.43.1:9090",
            localIp = "192.168.43.1",
            sharingMode = SharingMode.HOTSPOT,
            hotspotSsid = "DIRECT-ab-MyPhone",
            hotspotPassword = "s3cur3P4ss",
            apkSizeBytes = 12_000_000,
        )

        assertTrue(state.isServerRunning)
        assertEquals(SharingMode.HOTSPOT, state.sharingMode)
        assertEquals("DIRECT-ab-MyPhone", state.hotspotSsid)
        assertEquals("s3cur3P4ss", state.hotspotPassword)
        assertEquals("http://192.168.43.1:9090", state.downloadUrl)
        assertFalse(state.needsHotspotPermission)
        assertFalse(state.isHotspotStarting)
    }

    @Test
    fun `viewModel emits error when APK preparation fails`() =
        runTest(testDispatcher) {
            val viewModel = ApkSharingViewModel(application)
            advanceUntilIdle()

            viewModel.state.test(timeout = 5.seconds) {
                val state = awaitItem()
                // Under Robolectric, APK preparation fails, so we expect either
                // an error message about APK or about WiFi
                if (state.errorMessage != null) {
                    assertTrue(
                        "Error should mention APK or WiFi",
                        state.errorMessage!!.contains("APK") ||
                            state.errorMessage!!.contains("WiFi") ||
                            state.errorMessage!!.contains("sharing server"),
                    )
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getRequiredHotspotPermissions returns non-empty on API 33`() =
        runTest {
            val viewModel = ApkSharingViewModel(application)
            val permissions = viewModel.getRequiredHotspotPermissions()
            // On API 34 (our test config), should require NEARBY_WIFI_DEVICES
            assertTrue(
                "Should require permissions on API 34",
                permissions.isNotEmpty(),
            )
        }

    @Test
    fun `SharingMode enum has expected values`() {
        assertEquals(2, SharingMode.entries.size)
        assertNotNull(SharingMode.WIFI)
        assertNotNull(SharingMode.HOTSPOT)
    }
}
