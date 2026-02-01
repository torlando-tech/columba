package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.CustomThemeRepository
import com.lxmf.messenger.data.repository.ThemeColorSet
import io.mockk.*
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for ThemeEditorViewModel.
 * Tests theme creation, editing, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeEditorViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var customThemeRepository: CustomThemeRepository
    private lateinit var settingsRepository: com.lxmf.messenger.repository.SettingsRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: ThemeEditorViewModel

    private val testLightColors =
        ThemeColorSet(
            primary = 0xFF6200EE.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFF3700B3.toInt(),
            onPrimaryContainer = 0xFFFFFFFF.toInt(),
            secondary = 0xFF03DAC6.toInt(),
            onSecondary = 0xFF000000.toInt(),
            secondaryContainer = 0xFF018786.toInt(),
            onSecondaryContainer = 0xFFFFFFFF.toInt(),
            tertiary = 0xFFCF6679.toInt(),
            onTertiary = 0xFF000000.toInt(),
            tertiaryContainer = 0xFFB00020.toInt(),
            onTertiaryContainer = 0xFFFFFFFF.toInt(),
            error = 0xFFB00020.toInt(),
            onError = 0xFFFFFFFF.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            background = 0xFFFFFFFF.toInt(),
            onBackground = 0xFF000000.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            onSurface = 0xFF000000.toInt(),
            surfaceVariant = 0xFFF5F5F5.toInt(),
            onSurfaceVariant = 0xFF666666.toInt(),
            outline = 0xFF999999.toInt(),
            outlineVariant = 0xFFCCCCCC.toInt(),
        )

    private val testDarkColors =
        testLightColors.copy(
            background = 0xFF121212.toInt(),
            onBackground = 0xFFFFFFFF.toInt(),
            surface = 0xFF121212.toInt(),
            onSurface = 0xFFFFFFFF.toInt(),
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        customThemeRepository = mockk<CustomThemeRepository>()
        settingsRepository = mockk<com.lxmf.messenger.repository.SettingsRepository>()
        savedStateHandle = SavedStateHandle()

        // Default stubs for repository methods
        coEvery { customThemeRepository.getThemeById(any()) } returns null
        coEvery {
            customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1L
        coEvery {
            customThemeRepository.updateTheme(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Initialization Tests ==========

    @Test
    fun `init with no themeId starts with empty state`() =
        runTest {
            // Given - no themeId in SavedStateHandle
            savedStateHandle["themeId"] = null

            // When
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.themeId)
                assertEquals("", state.themeName)
                assertEquals("", state.themeDescription)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `init with themeId loads existing theme`() =
        runTest {
            // Given
            val themeId = 123L
            val existingTheme =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = themeId,
                    name = "Ocean Theme",
                    description = "Blue ocean colors",
                    seedPrimary = 0xFF6200EE.toInt(),
                    seedSecondary = 0xFF03DAC6.toInt(),
                    seedTertiary = 0xFFCF6679.toInt(),
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )
            savedStateHandle["themeId"] = themeId
            coEvery { customThemeRepository.getThemeById(themeId) } returns existingTheme

            // When
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(themeId, state.themeId)
                assertEquals("Ocean Theme", state.themeName)
                assertEquals("Blue ocean colors", state.themeDescription)
                assertFalse(state.isLoading)
            }

            coVerify { customThemeRepository.getThemeById(themeId) }
        }

    @Test
    fun `init with invalid themeId sets error state`() =
        runTest {
            // Given
            val invalidThemeId = 999L
            savedStateHandle["themeId"] = invalidThemeId
            coEvery { customThemeRepository.getThemeById(invalidThemeId) } returns null

            // When
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertEquals("Theme not found", state.error)
            }
        }

    // ========== State Update Tests ==========

    @Test
    fun `updateThemeName updates state correctly`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            viewModel.updateThemeName("My Custom Theme")
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("My Custom Theme", state.themeName)
            }
        }

    @Test
    fun `updateThemeDescription updates state correctly`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            viewModel.updateThemeDescription("A beautiful theme")
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("A beautiful theme", state.themeDescription)
            }
        }

    @Test
    fun `updateSelectedColor updates state correctly`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            val newColor = Color.Red
            viewModel.updateSelectedColor(newColor)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newColor, state.primarySeedColor)
            }
        }

    // ========== Save Theme Tests ==========

    @Test
    fun `saveTheme creates new theme when themeId is null`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("New Theme")
            viewModel.updateThemeDescription("Test description")
            viewModel.updateSelectedColor(Color.Blue)
            advanceUntilIdle()

            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 1L

            // When
            val result = runCatching { viewModel.saveTheme() }
            advanceUntilIdle()

            // Then
            assertTrue("saveTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.saveTheme(
                    name = "New Theme",
                    description = "Test description",
                    seedPrimary = any(),
                    seedSecondary = any(),
                    seedTertiary = any(),
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
        }

    @Test
    fun `saveTheme updates existing theme when themeId is set`() =
        runTest {
            // Given
            val themeId = 123L
            val existingTheme =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = themeId,
                    name = "Ocean Theme",
                    description = "Blue ocean colors",
                    seedPrimary = 0xFF6200EE.toInt(),
                    seedSecondary = 0xFF03DAC6.toInt(),
                    seedTertiary = 0xFFCF6679.toInt(),
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )
            savedStateHandle["themeId"] = themeId
            coEvery { customThemeRepository.getThemeById(themeId) } returns existingTheme
            coEvery {
                customThemeRepository.updateTheme(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } just Runs

            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Updated Theme")
            advanceUntilIdle()

            // When
            val result = runCatching { viewModel.saveTheme() }
            advanceUntilIdle()

            // Then
            assertTrue("saveTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.updateTheme(
                    id = themeId,
                    name = "Updated Theme",
                    description = any(),
                    seedPrimary = any(),
                    seedSecondary = any(),
                    seedTertiary = any(),
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
        }

    @Test
    fun `saveTheme does not save when theme name is empty`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("") // Empty name
            advanceUntilIdle()

            // When
            viewModel.saveTheme()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("Theme name cannot be empty", state.error)
            }

            coVerify(exactly = 0) {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `saveTheme sets error state on repository failure`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Test Theme")
            advanceUntilIdle()

            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } throws Exception("Database error")

            // When
            viewModel.saveTheme()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSaving)
                assertTrue(state.error?.contains("Failed to save theme") == true)
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `clearError removes error message`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Trigger error
            viewModel.updateThemeName("")
            viewModel.saveTheme()
            advanceUntilIdle()

            // When
            viewModel.clearError()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.error)
            }
        }

    // ========== Color Role Selection Tests ==========

    @Test
    fun `selectColorRole updates selectedColorRole to PRIMARY`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            viewModel.selectColorRole(ColorRole.PRIMARY)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(ColorRole.PRIMARY, state.selectedColorRole)
            }
        }

    @Test
    fun `selectColorRole updates selectedColorRole to SECONDARY`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            viewModel.selectColorRole(ColorRole.SECONDARY)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(ColorRole.SECONDARY, state.selectedColorRole)
            }
        }

    @Test
    fun `selectColorRole updates selectedColorRole to TERTIARY`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // When
            viewModel.selectColorRole(ColorRole.TERTIARY)
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(ColorRole.TERTIARY, state.selectedColorRole)
            }
        }

    // ========== Get Selected Color Tests ==========

    @Test
    fun `getSelectedColor returns primary when PRIMARY is selected`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()
            viewModel.selectColorRole(ColorRole.PRIMARY)
            advanceUntilIdle()

            // When
            val selectedColor = viewModel.getSelectedColor()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(state.primarySeedColor, selectedColor)
            }
        }

    @Test
    fun `getSelectedColor returns secondary when SECONDARY is selected`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()
            viewModel.selectColorRole(ColorRole.SECONDARY)
            advanceUntilIdle()

            // When
            val selectedColor = viewModel.getSelectedColor()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(state.secondarySeedColor, selectedColor)
            }
        }

    @Test
    fun `getSelectedColor returns tertiary when TERTIARY is selected`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()
            viewModel.selectColorRole(ColorRole.TERTIARY)
            advanceUntilIdle()

            // When
            val selectedColor = viewModel.getSelectedColor()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(state.tertiarySeedColor, selectedColor)
            }
        }

    // ========== Update Selected Color Tests ==========

    @Test
    fun `updateSelectedColor in harmonized mode with PRIMARY regenerates secondary and tertiary`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Enable harmonized mode and select PRIMARY
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.useHarmonizedColors) // Harmonized mode is on by default
            }
            viewModel.selectColorRole(ColorRole.PRIMARY)
            advanceUntilIdle()

            val newPrimaryColor = Color.Red

            val initialState = viewModel.state.value
            val initialSecondary = initialState.secondarySeedColor
            val initialTertiary = initialState.tertiarySeedColor

            // When
            viewModel.updateSelectedColor(newPrimaryColor)
            advanceUntilIdle()

            // Then - primary updated and secondary/tertiary regenerated (should differ from initial)
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newPrimaryColor, state.primarySeedColor)
                // Secondary and tertiary should have been regenerated (not equal to initial values)
                assertNotEquals(initialSecondary, state.secondarySeedColor)
                assertNotEquals(initialTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `updateSelectedColor in custom mode with SECONDARY updates color and backup`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Disable harmonized mode (custom mode)
            viewModel.toggleHarmonizedMode() // OFF (was ON by default)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.SECONDARY)
            advanceUntilIdle()

            val newSecondaryColor = Color.Green

            // When
            viewModel.updateSelectedColor(newSecondaryColor)
            advanceUntilIdle()

            // Then - secondary updated and backup saved
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newSecondaryColor, state.secondarySeedColor)
                assertEquals(newSecondaryColor, state.lastCustomSecondaryColor)
            }
        }

    @Test
    fun `updateSelectedColor in custom mode with TERTIARY updates color and backup`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Disable harmonized mode (custom mode)
            viewModel.toggleHarmonizedMode() // OFF (was ON by default)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            advanceUntilIdle()

            val newTertiaryColor = Color.Yellow

            // When
            viewModel.updateSelectedColor(newTertiaryColor)
            advanceUntilIdle()

            // Then - tertiary updated and backup saved
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newTertiaryColor, state.tertiarySeedColor)
                assertEquals(newTertiaryColor, state.lastCustomTertiaryColor)
            }
        }

    @Test
    fun `updateSelectedColor in custom mode with PRIMARY only updates primary`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Disable harmonized mode (custom mode)
            viewModel.toggleHarmonizedMode() // OFF (was ON by default)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.PRIMARY)
            advanceUntilIdle()

            val newPrimaryColor = Color.Magenta

            val initialState = viewModel.state.value
            val initialSecondary = initialState.secondarySeedColor
            val initialTertiary = initialState.tertiarySeedColor

            // When
            viewModel.updateSelectedColor(newPrimaryColor)
            advanceUntilIdle()

            // Then - only primary updated, secondary/tertiary unchanged
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newPrimaryColor, state.primarySeedColor)
                assertEquals(initialSecondary, state.secondarySeedColor)
                assertEquals(initialTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `updateSelectedColor in harmonized mode with SECONDARY does not regenerate from primary`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.SECONDARY)
            advanceUntilIdle()

            val newSecondaryColor = Color.Cyan

            val initialState = viewModel.state.value
            assertTrue(initialState.useHarmonizedColors) // Harmonized mode on by default
            val initialPrimary = initialState.primarySeedColor
            val initialTertiary = initialState.tertiarySeedColor

            // When
            viewModel.updateSelectedColor(newSecondaryColor)
            advanceUntilIdle()

            // Then - only secondary updated, primary/tertiary unchanged
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(initialPrimary, state.primarySeedColor)
                assertEquals(newSecondaryColor, state.secondarySeedColor)
                assertEquals(initialTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `updateSelectedColor in harmonized mode with TERTIARY does not regenerate from primary`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            advanceUntilIdle()

            val newTertiaryColor = Color.Magenta

            val initialState = viewModel.state.value
            assertTrue(initialState.useHarmonizedColors) // Harmonized mode on by default
            val initialPrimary = initialState.primarySeedColor
            val initialSecondary = initialState.secondarySeedColor

            // When
            viewModel.updateSelectedColor(newTertiaryColor)
            advanceUntilIdle()

            // Then - only tertiary updated, primary/secondary unchanged
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(initialPrimary, state.primarySeedColor)
                assertEquals(initialSecondary, state.secondarySeedColor)
                assertEquals(newTertiaryColor, state.tertiarySeedColor)
            }
        }

    // ========== Toggle Harmonized Mode Tests ==========

    @Test
    fun `toggleHarmonizedMode OFF to ON saves custom colors and generates harmonized colors`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Turn off harmonized mode first (it's on by default)
            viewModel.toggleHarmonizedMode()
            advanceUntilIdle()

            // Set custom colors
            viewModel.selectColorRole(ColorRole.SECONDARY)
            viewModel.updateSelectedColor(Color.Green)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            viewModel.updateSelectedColor(Color.Yellow)
            advanceUntilIdle()

            val beforeToggleState = viewModel.state.value
            assertFalse(beforeToggleState.useHarmonizedColors)
            val customSecondary = beforeToggleState.secondarySeedColor
            val customTertiary = beforeToggleState.tertiarySeedColor

            // When - toggle back ON
            viewModel.toggleHarmonizedMode()
            advanceUntilIdle()

            // Then - harmonized mode on, custom colors backed up, harmonized colors generated
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.useHarmonizedColors)
                // Backup should contain the custom colors
                assertEquals(customSecondary, state.lastCustomSecondaryColor)
                assertEquals(customTertiary, state.lastCustomTertiaryColor)
                // Current colors should be harmonized (different from custom)
                assertNotEquals(customSecondary, state.secondarySeedColor)
                assertNotEquals(customTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `toggleHarmonizedMode ON to OFF restores colors from backup`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Harmonized mode is ON by default, turn it OFF then back ON to create backups
            viewModel.toggleHarmonizedMode() // OFF
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.SECONDARY)
            viewModel.updateSelectedColor(Color.Blue)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            viewModel.updateSelectedColor(Color.Red)
            advanceUntilIdle()

            val customSecondary = Color.Blue
            val customTertiary = Color.Red

            viewModel.toggleHarmonizedMode() // ON - this creates backup
            advanceUntilIdle()

            // When - toggle back OFF
            viewModel.toggleHarmonizedMode()
            advanceUntilIdle()

            // Then - custom colors restored from backup
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.useHarmonizedColors)
                assertEquals(customSecondary, state.secondarySeedColor)
                assertEquals(customTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `toggleHarmonizedMode ON to OFF with no backup keeps current colors`() =
        runTest {
            // Given - start with harmonized mode ON (default), no backup exists
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            val initialState = viewModel.state.value
            assertTrue(initialState.useHarmonizedColors)
            assertNull(initialState.lastCustomSecondaryColor) // No backup
            assertNull(initialState.lastCustomTertiaryColor)
            val initialSecondary = initialState.secondarySeedColor
            val initialTertiary = initialState.tertiarySeedColor

            // When - toggle OFF
            viewModel.toggleHarmonizedMode()
            advanceUntilIdle()

            // Then - colors remain unchanged (no backup to restore)
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.useHarmonizedColors)
                assertEquals(initialSecondary, state.secondarySeedColor)
                assertEquals(initialTertiary, state.tertiarySeedColor)
            }
        }

    @Test
    fun `toggleHarmonizedMode multiple times preserves original custom colors`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Turn off harmonized mode and set custom colors
            viewModel.toggleHarmonizedMode() // OFF
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.SECONDARY)
            viewModel.updateSelectedColor(Color.Cyan)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            viewModel.updateSelectedColor(Color.Magenta)
            advanceUntilIdle()

            val originalSecondary = Color.Cyan
            val originalTertiary = Color.Magenta

            // When - toggle ON then OFF again
            viewModel.toggleHarmonizedMode() // ON
            advanceUntilIdle()
            viewModel.toggleHarmonizedMode() // OFF
            advanceUntilIdle()

            // Then - original custom colors preserved
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.useHarmonizedColors)
                assertEquals(originalSecondary, state.secondarySeedColor)
                assertEquals(originalTertiary, state.tertiarySeedColor)
            }
        }

    // ========== Save and Apply Theme Tests ==========

    @Test
    fun `saveAndApplyTheme saves new theme and calls saveThemePreference`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Applied Theme")
            viewModel.updateThemeDescription("A theme that gets applied")
            advanceUntilIdle()

            val savedThemeId = 42L
            val savedTheme =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = savedThemeId,
                    name = "Applied Theme",
                    description = "A theme that gets applied",
                    seedPrimary = 0xFF6200EE.toInt(),
                    seedSecondary = 0xFF03DAC6.toInt(),
                    seedTertiary = 0xFFB00020.toInt(),
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )

            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } returns savedThemeId

            coEvery { customThemeRepository.getThemeById(savedThemeId) } returns savedTheme

            // Data class with complex ColorScheme fields - relaxed mock is appropriate
            @Suppress("NoRelaxedMocks")
            val mockCustomTheme = mockk<com.lxmf.messenger.ui.theme.CustomTheme>(relaxed = true)
            every { settingsRepository.customThemeDataToAppTheme(savedTheme) } returns mockCustomTheme
            coEvery { settingsRepository.saveThemePreference(mockCustomTheme) } just Runs

            // When
            val result = runCatching { viewModel.saveAndApplyTheme() }
            advanceUntilIdle()

            // Then
            assertTrue("saveAndApplyTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.saveTheme(
                    name = "Applied Theme",
                    description = "A theme that gets applied",
                    seedPrimary = any(),
                    seedSecondary = any(),
                    seedTertiary = any(),
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
            coVerify { customThemeRepository.getThemeById(savedThemeId) }
            verify { settingsRepository.customThemeDataToAppTheme(savedTheme) }
            coVerify { settingsRepository.saveThemePreference(mockCustomTheme) }
        }

    @Test
    fun `saveAndApplyTheme updates existing theme and applies it`() =
        runTest {
            // Given
            val themeId = 123L
            val existingTheme =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = themeId,
                    name = "Existing Theme",
                    description = "Original description",
                    seedPrimary = 0xFF6200EE.toInt(),
                    seedSecondary = 0xFF03DAC6.toInt(),
                    seedTertiary = 0xFFB00020.toInt(),
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )
            savedStateHandle["themeId"] = themeId
            coEvery { customThemeRepository.getThemeById(themeId) } returns existingTheme

            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Updated Theme")
            advanceUntilIdle()

            val updatedTheme = existingTheme.copy(name = "Updated Theme")
            coEvery {
                customThemeRepository.updateTheme(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } just Runs
            coEvery { customThemeRepository.getThemeById(themeId) } returns updatedTheme

            // Data class with complex ColorScheme fields - relaxed mock is appropriate
            @Suppress("NoRelaxedMocks")
            val mockCustomTheme = mockk<com.lxmf.messenger.ui.theme.CustomTheme>(relaxed = true)
            every { settingsRepository.customThemeDataToAppTheme(updatedTheme) } returns mockCustomTheme
            coEvery { settingsRepository.saveThemePreference(mockCustomTheme) } just Runs

            // When
            val result = runCatching { viewModel.saveAndApplyTheme() }
            advanceUntilIdle()

            // Then
            assertTrue("saveAndApplyTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.updateTheme(
                    id = themeId,
                    name = "Updated Theme",
                    description = any(),
                    seedPrimary = any(),
                    seedSecondary = any(),
                    seedTertiary = any(),
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
            verify { settingsRepository.customThemeDataToAppTheme(updatedTheme) }
            coVerify { settingsRepository.saveThemePreference(mockCustomTheme) }
        }

    @Test
    fun `saveAndApplyTheme validates empty theme name`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("") // Empty name
            advanceUntilIdle()

            // When
            viewModel.saveAndApplyTheme()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("Theme name cannot be empty", state.error)
                assertFalse(state.isSaving)
            }

            coVerify(exactly = 0) {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                settingsRepository.saveThemePreference(any())
            }
        }

    @Test
    fun `saveAndApplyTheme handles save errors`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Test Theme")
            advanceUntilIdle()

            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } throws Exception("Database error")

            // When
            viewModel.saveAndApplyTheme()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSaving)
                assertTrue(state.error?.contains("Failed to save and apply theme") == true)
            }

            coVerify(exactly = 0) {
                settingsRepository.saveThemePreference(any())
            }
        }

    @Test
    fun `saveAndApplyTheme handles theme retrieval errors after save`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Test Theme")
            advanceUntilIdle()

            val savedThemeId = 42L
            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } returns savedThemeId

            // Theme retrieval fails
            coEvery { customThemeRepository.getThemeById(savedThemeId) } returns null

            // When
            viewModel.saveAndApplyTheme()
            advanceUntilIdle()

            // Then - should not crash, theme preference not saved
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSaving)
                assertNull(state.error) // No error since save succeeded, just couldn't apply
            }

            coVerify { customThemeRepository.getThemeById(savedThemeId) }
            coVerify(exactly = 0) {
                settingsRepository.saveThemePreference(any())
            }
        }

    // ========== Three-Seed Storage Tests ==========

    @Test
    fun `saveTheme passes all three seed ARGBs to repository`() =
        runTest {
            // Given
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Three Seed Theme")
            advanceUntilIdle()

            // Set custom colors for each role
            viewModel.selectColorRole(ColorRole.PRIMARY)
            viewModel.updateSelectedColor(Color.Red)
            advanceUntilIdle()

            viewModel.toggleHarmonizedMode() // Turn off harmonized to set custom colors
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.SECONDARY)
            viewModel.updateSelectedColor(Color.Green)
            advanceUntilIdle()

            viewModel.selectColorRole(ColorRole.TERTIARY)
            viewModel.updateSelectedColor(Color.Blue)
            advanceUntilIdle()

            coEvery {
                customThemeRepository.saveTheme(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 1L

            // When
            val result = runCatching { viewModel.saveTheme() }
            advanceUntilIdle()

            // Then - verify all three seed colors passed to repository
            assertTrue("saveTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.saveTheme(
                    name = "Three Seed Theme",
                    description = any(),
                    seedPrimary = Color.Red.toArgb(),
                    seedSecondary = Color.Green.toArgb(),
                    seedTertiary = Color.Blue.toArgb(),
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
        }

    @Test
    fun `loadTheme loads seed colors not palette colors`() =
        runTest {
            // Given
            val themeId = 99L
            val seedPrimaryArgb = 0xFFFF0000.toInt() // Red
            val seedSecondaryArgb = 0xFF00FF00.toInt() // Green
            val seedTertiaryArgb = 0xFF0000FF.toInt() // Blue

            val themeWithSeeds =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = themeId,
                    name = "Seed Test Theme",
                    description = "Tests seed color loading",
                    seedPrimary = seedPrimaryArgb,
                    seedSecondary = seedSecondaryArgb,
                    seedTertiary = seedTertiaryArgb,
                    // Palette colors are different from seeds
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )

            savedStateHandle["themeId"] = themeId
            coEvery { customThemeRepository.getThemeById(themeId) } returns themeWithSeeds

            // When
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            // Then - state contains seed colors, not palette colors
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(Color(seedPrimaryArgb), state.primarySeedColor)
                assertEquals(Color(seedSecondaryArgb), state.secondarySeedColor)
                assertEquals(Color(seedTertiaryArgb), state.tertiarySeedColor)
                // Verify these are seeds, not palette colors
                assertNotEquals(Color(testLightColors.primary), state.primarySeedColor)
            }
        }

    @Test
    fun `seed color preservation across edit cycles`() =
        runTest {
            // Given
            val themeId = 88L
            val originalPrimarySeed = 0xFFAA0000.toInt()
            val originalSecondarySeed = 0xFF00AA00.toInt()
            val originalTertiarySeed = 0xFF0000AA.toInt()

            val originalTheme =
                com.lxmf.messenger.data.repository.CustomThemeData(
                    id = themeId,
                    name = "Original Theme",
                    description = "Original",
                    seedPrimary = originalPrimarySeed,
                    seedSecondary = originalSecondarySeed,
                    seedTertiary = originalTertiarySeed,
                    lightColors = testLightColors,
                    darkColors = testDarkColors,
                    baseTheme = null,
                    createdTimestamp = 1000L,
                    modifiedTimestamp = 2000L,
                )

            savedStateHandle["themeId"] = themeId
            coEvery { customThemeRepository.getThemeById(themeId) } returns originalTheme
            coEvery {
                customThemeRepository.updateTheme(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } just Runs

            // When - load, edit name, save
            viewModel = ThemeEditorViewModel(customThemeRepository, settingsRepository, savedStateHandle)
            advanceUntilIdle()

            viewModel.updateThemeName("Modified Theme")
            advanceUntilIdle()

            val result = runCatching { viewModel.saveTheme() }
            advanceUntilIdle()

            // Then - seed colors preserved in update call
            assertTrue("saveTheme should complete without exception", result.isSuccess)
            coVerify {
                customThemeRepository.updateTheme(
                    id = themeId,
                    name = "Modified Theme",
                    description = any(),
                    seedPrimary = originalPrimarySeed,
                    seedSecondary = originalSecondarySeed,
                    seedTertiary = originalTertiarySeed,
                    lightColors = any(),
                    darkColors = any(),
                    baseTheme = null,
                )
            }
        }
}
