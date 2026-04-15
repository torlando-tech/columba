package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.columba.app.data.repository.CustomThemeData
import network.columba.app.data.repository.CustomThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing custom themes.
 * Provides a list of all custom themes and handles deletion.
 */
@HiltViewModel
class ThemeManagementViewModel
    @Inject
    constructor(
        private val customThemeRepository: CustomThemeRepository,
    ) : ViewModel() {
        /**
         * Flow of all custom themes ordered by creation timestamp (newest first)
         */
        val themes: Flow<List<CustomThemeData>> = customThemeRepository.getAllThemes()

        /**
         * Delete a custom theme by ID
         */
        fun deleteTheme(themeId: Long) {
            viewModelScope.launch {
                customThemeRepository.deleteTheme(themeId)
            }
        }
    }
