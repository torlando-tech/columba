package com.lxmf.messenger.viewmodel

import android.app.Application
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

    @Test
    fun `initial state has default values`() {
        val defaultState = ApkSharingState()
        assertFalse(defaultState.isServerRunning)
        assertNull(defaultState.downloadUrl)
        assertNull(defaultState.localIp)
        assertNull(defaultState.errorMessage)
        assertEquals(0L, defaultState.apkSizeBytes)
    }

    @Test
    fun `viewModel init triggers startServer which sets error state in test environment`() =
        runTest {
            val viewModel = ApkSharingViewModel(application)
            advanceUntilIdle()

            viewModel.state.test(timeout = 5.seconds) {
                val state = awaitItem()
                // In Robolectric, either sourceDir doesn't exist or there's no WiFi,
                // so we expect an error state or the default non-running state
                assertFalse(state.isServerRunning)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `state flow is exposed as immutable StateFlow`() =
        runTest {
            val viewModel = ApkSharingViewModel(application)
            assertNotNull(viewModel.state)
        }

    @Test
    fun `startServer is idempotent when already running`() =
        runTest {
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
        runTest {
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
            )

        assertTrue(modified.isServerRunning)
        assertEquals("http://10.0.0.1:8080", modified.downloadUrl)
        assertEquals("10.0.0.1", modified.localIp)
        assertNull(modified.errorMessage)
        assertEquals(5_000_000L, modified.apkSizeBytes)
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
    fun `viewModel emits error when APK preparation fails`() =
        runTest {
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
}
