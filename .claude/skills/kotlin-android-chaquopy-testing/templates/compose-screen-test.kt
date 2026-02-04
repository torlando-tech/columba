// Compose UI Testing Template
// Copy this template for testing Compose screens

package com.lxmf.messenger.ui.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests MUST be instrumented tests (androidTest).
 * Use ComposeTestRule to interact with Compose UI.
 */
@RunWith(AndroidJUnit4::class)
class ExampleScreenTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun screenDisplaysCorrectInitialState() {
        // Arrange & Act
        composeTestRule.setContent {
            YourScreen(
                uiState = UiState.Initial,
                onAction = {}
            )
        }
        
        // Assert
        composeTestRule
            .onNodeWithText("Welcome")
            .assertIsDisplayed()
    }
    
    @Test
    fun buttonClickTriggersAction() {
        // Arrange
        var actionCalled = false
        
        composeTestRule.setContent {
            YourScreen(
                uiState = UiState.Ready,
                onAction = { actionCalled = true }
            )
        }
        
        // Act
        composeTestRule
            .onNodeWithContentDescription("Action Button")
            .performClick()
        
        // Assert
        assert(actionCalled)
    }
    
    @Test
    fun textInputWorksCorrectly() {
        // Arrange
        var inputText = ""
        
        composeTestRule.setContent {
            YourScreen(
                onTextInput = { inputText = it }
            )
        }
        
        // Act
        composeTestRule
            .onNodeWithContentDescription("Text Input")
            .performTextInput("Test input")
        
        // Assert
        assert(inputText == "Test input")
    }
    
    @Test
    fun loadingStateDisplaysProgressIndicator() {
        // Arrange & Act
        composeTestRule.setContent {
            YourScreen(uiState = UiState.Loading)
        }
        
        // Assert
        composeTestRule
            .onNodeWithContentDescription("Loading")
            .assertIsDisplayed()
    }
    
    @Test
    fun errorStateDisplaysErrorMessage() {
        // Arrange
        val errorMessage = "Test error"
        
        // Act
        composeTestRule.setContent {
            YourScreen(uiState = UiState.Error(errorMessage))
        }
        
        // Assert
        composeTestRule
            .onNodeWithText(errorMessage)
            .assertIsDisplayed()
    }
    
    @Test
    fun listDisplaysAllItems() {
        // Arrange
        val items = listOf("Item 1", "Item 2", "Item 3")
        
        // Act
        composeTestRule.setContent {
            YourListScreen(items = items)
        }
        
        // Assert
        items.forEach { item ->
            composeTestRule
                .onNodeWithText(item)
                .assertIsDisplayed()
        }
    }
    
    @Test
    fun navigationOccursOnItemClick() {
        // Arrange
        var navigatedTo: String? = null
        
        composeTestRule.setContent {
            YourScreen(
                onNavigate = { route -> navigatedTo = route }
            )
        }
        
        // Act
        composeTestRule
            .onNodeWithContentDescription("Navigate Button")
            .performClick()
        
        // Assert
        assert(navigatedTo == "expected_route")
    }
}
