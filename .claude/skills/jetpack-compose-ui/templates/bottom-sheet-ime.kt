/**
 * ModalBottomSheet with IME Handling Template
 *
 * PROBLEM THIS SOLVES:
 * - Standard ModalBottomSheet has double padding issues with keyboard
 * - TextField in bottom sheet doesn't scroll into view properly
 * - Keyboard obscures content
 *
 * SOLUTION:
 * ✅ windowInsets = WindowInsets(0) - Disables default inset handling
 * ✅ Manual .imePadding() - Applies keyboard padding correctly
 * ✅ .systemBarsPadding() - Handles navigation bar
 *
 * TESTING:
 * [ ] TextField scrolls into view when focused
 * [ ] No double padding visible
 * [ ] Content not obscured by keyboard
 * [ ] Works in landscape mode
 */

package com.example.yourapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetWithForm(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            windowInsets = WindowInsets(0), // CRITICAL: Disable default insets
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .systemBarsPadding() // Handle navigation bar
                .imePadding() // Handle keyboard manually
        ) {
            BottomSheetContent(
                text = text,
                onTextChange = { text = it },
                onSubmit = {
                    onSubmit(text)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun BottomSheetContent(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Enter Information",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Your text") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit")
        }
    }
}
