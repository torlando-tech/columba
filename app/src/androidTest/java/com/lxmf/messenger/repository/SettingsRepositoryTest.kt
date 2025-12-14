package com.lxmf.messenger.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.CustomThemeRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

// Test-specific DataStore to avoid conflicts with production data
private val Context.testDataStore by preferencesDataStore(name = "test_settings")

/**
 * Instrumented tests for SettingsRepository.
 * Tests transport node preference storage and retrieval.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var mockCustomThemeRepository: CustomThemeRepository
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockCustomThemeRepository = mockk(relaxed = true)

        // Mock theme repository flows to prevent null pointer exceptions
        every { mockCustomThemeRepository.getAllThemes() } returns flowOf(emptyList())
        every { mockCustomThemeRepository.getThemeByIdFlow(any()) } returns flowOf(null)

        repository = SettingsRepository(context, mockCustomThemeRepository)

        // Clear any existing test data
        runBlocking {
            clearTestData()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            clearTestData()
        }
        clearAllMocks()
    }

    private suspend fun clearTestData() {
        // Clear DataStore by editing preferences
        try {
            context.testDataStore.edit { preferences ->
                preferences.clear()
            }
        } catch (e: Exception) {
            // DataStore may not exist yet, ignore
        }
    }

    // ========== Transport Node Flow Tests ==========

    @Test
    fun transportNodeEnabledFlow_defaultsToTrue() = runTest {
        // When - Fresh repository with no saved preferences
        val result = repository.transportNodeEnabledFlow.first()

        // Then - Default should be true
        assertTrue("Transport node should default to enabled", result)
    }

    @Test
    fun transportNodeEnabledFlow_emitsUpdatesWhenChanged() = runTest {
        // Given
        repository.transportNodeEnabledFlow.test(timeout = 5.seconds) {
            // First emission should be default (true)
            val initial = awaitItem()
            assertTrue("Initial value should be true", initial)

            // When - Save false
            repository.saveTransportNodeEnabled(false)

            // Then - Should emit false
            val updated = awaitItem()
            assertFalse("Updated value should be false", updated)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== getTransportNodeEnabled() Tests ==========

    @Test
    fun getTransportNodeEnabled_returnsDefaultTrue() = runTest {
        // When - No value saved yet
        val result = repository.getTransportNodeEnabled()

        // Then
        assertTrue("getTransportNodeEnabled should default to true", result)
    }

    @Test
    fun getTransportNodeEnabled_returnsSavedValueFalse() = runTest {
        // Given - Save false
        repository.saveTransportNodeEnabled(false)

        // When
        val result = repository.getTransportNodeEnabled()

        // Then
        assertFalse("getTransportNodeEnabled should return saved value", result)
    }

    @Test
    fun getTransportNodeEnabled_returnsSavedValueTrue() = runTest {
        // Given - First save false, then save true
        repository.saveTransportNodeEnabled(false)
        repository.saveTransportNodeEnabled(true)

        // When
        val result = repository.getTransportNodeEnabled()

        // Then
        assertTrue("getTransportNodeEnabled should return true after saving", result)
    }

    // ========== saveTransportNodeEnabled() Tests ==========

    @Test
    fun saveTransportNodeEnabled_persistsFalse() = runTest {
        // When
        repository.saveTransportNodeEnabled(false)

        // Then - Read back should be false
        val result = repository.getTransportNodeEnabled()
        assertFalse("Saved false should persist", result)
    }

    @Test
    fun saveTransportNodeEnabled_persistsTrue() = runTest {
        // Given - First set to false
        repository.saveTransportNodeEnabled(false)

        // When - Then set to true
        repository.saveTransportNodeEnabled(true)

        // Then - Read back should be true
        val result = repository.getTransportNodeEnabled()
        assertTrue("Saved true should persist", result)
    }

    @Test
    fun saveTransportNodeEnabled_updatesFlow() = runTest {
        // Given
        repository.transportNodeEnabledFlow.test(timeout = 5.seconds) {
            // Skip initial default value
            awaitItem()

            // When
            repository.saveTransportNodeEnabled(false)

            // Then - Flow should emit new value
            val emitted = awaitItem()
            assertFalse("Flow should emit false after save", emitted)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Multiple Save Tests ==========

    @Test
    fun saveTransportNodeEnabled_multipleSaves_lastValueWins() = runTest {
        // When - Multiple rapid saves
        repository.saveTransportNodeEnabled(true)
        repository.saveTransportNodeEnabled(false)
        repository.saveTransportNodeEnabled(true)
        repository.saveTransportNodeEnabled(false)

        // Then - Last value should win
        val result = repository.getTransportNodeEnabled()
        assertFalse("Last saved value should persist", result)
    }

    @Test
    fun transportNodeEnabled_toggleBackAndForth() = runTest {
        // Test toggling the value back and forth

        // Initially should be true (default)
        assertTrue(repository.getTransportNodeEnabled())

        // Toggle to false
        repository.saveTransportNodeEnabled(false)
        assertFalse(repository.getTransportNodeEnabled())

        // Toggle back to true
        repository.saveTransportNodeEnabled(true)
        assertTrue(repository.getTransportNodeEnabled())

        // Toggle to false again
        repository.saveTransportNodeEnabled(false)
        assertFalse(repository.getTransportNodeEnabled())
    }

    // ========== Flow Emission Count Tests ==========

    @Test
    fun transportNodeEnabledFlow_emitsOnlyOnChange() = runTest {
        // Given
        var emissionCount = 0

        repository.transportNodeEnabledFlow.test(timeout = 5.seconds) {
            // Initial emission
            awaitItem()
            emissionCount++

            // Save same value (true -> true should not emit)
            repository.saveTransportNodeEnabled(true)

            // Save different value (true -> false should emit)
            repository.saveTransportNodeEnabled(false)
            awaitItem()
            emissionCount++

            // Save same value again (false -> false should not emit)
            repository.saveTransportNodeEnabled(false)

            // Brief wait to ensure no extra emissions
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("Should have 2 emissions (initial + one change)", 2, emissionCount)
    }

    // ========== Consistency Tests ==========

    @Test
    fun flowAndGetMethod_returnSameValue() = runTest {
        // Given - Set to false
        repository.saveTransportNodeEnabled(false)

        // When - Read from both sources
        val flowValue = repository.transportNodeEnabledFlow.first()
        val methodValue = repository.getTransportNodeEnabled()

        // Then - Both should return same value
        assertEquals(
            "Flow and method should return same value",
            flowValue,
            methodValue,
        )
    }

    @Test
    fun flowAndGetMethod_bothReturnDefault() = runTest {
        // Fresh state - no saves yet

        // When - Read from both sources
        val flowValue = repository.transportNodeEnabledFlow.first()
        val methodValue = repository.getTransportNodeEnabled()

        // Then - Both should return default (true)
        assertTrue("Flow should return default true", flowValue)
        assertTrue("Method should return default true", methodValue)
        assertEquals(
            "Flow and method should return same default",
            flowValue,
            methodValue,
        )
    }
}
