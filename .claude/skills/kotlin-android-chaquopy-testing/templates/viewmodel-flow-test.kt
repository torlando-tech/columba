// ViewModel with Flow Testing Template
// Copy this template for testing ViewModels that use StateFlow/SharedFlow

package com.lxmf.messenger.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.clearAllMocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExampleViewModelTest {
    
    // InstantTaskExecutorRule ensures LiveData/Flow operations execute synchronously
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()
    
    // TestDispatcher for controlling coroutine execution
    private val testDispatcher = StandardTestDispatcher()
    
    // Dependencies (mocked)
    private lateinit var repository: YourRepository
    
    // System under test
    private lateinit var viewModel: YourViewModel
    
    @Before
    fun setup() {
        // Set Main dispatcher to TestDispatcher
        Dispatchers.setMain(testDispatcher)
        
        // Create mocks
        repository = mockk()
        
        // Create ViewModel
        viewModel = YourViewModel(repository)
    }
    
    @After
    fun teardown() {
        // Reset Main dispatcher
        Dispatchers.resetMain()
        
        // Clear all mocks
        clearAllMocks()
    }
    
    @Test
    fun `when action called, state updates correctly`() = runTest {
        // Arrange
        val expectedData = TestData()
        coEvery { repository.getData() } returns Result.success(expectedData)
        
        // Act
        viewModel.loadData()
        advanceUntilIdle() // Process all pending coroutines
        
        // Assert using Turbine for Flow testing
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            assertEquals(expectedData, (state as UiState.Success).data)
        }
    }
    
    @Test
    fun `when action fails, error state is set`() = runTest {
        // Arrange
        val errorMessage = "Test error"
        coEvery { repository.getData() } returns Result.failure(Exception(errorMessage))
        
        // Act
        viewModel.loadData()
        advanceUntilIdle()
        
        // Assert
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Error)
            assertEquals(errorMessage, (state as UiState.Error).message)
        }
    }
    
    @Test
    fun `initial state is correct`() = runTest {
        // Assert
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Initial)
        }
    }
}

// Example UiState sealed class (customize to your needs)
sealed class UiState {
    object Initial : UiState()
    object Loading : UiState()
    data class Success(val data: Any) : UiState()
    data class Error(val message: String) : UiState()
}
