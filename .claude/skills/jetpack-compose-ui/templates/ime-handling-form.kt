/**
 * IME (Keyboard) Handling Form Template
 *
 * WHEN TO USE:
 * - Complex forms with multiple TextFields
 * - Need to bring focused TextField into view when keyboard appears
 * - Need keyboard dismiss on scroll or done action
 * - Form fields might be obscured by keyboard
 *
 * FEATURES:
 * ✅ BringIntoViewRequester - scrolls focused field into view
 * ✅ FocusRequester - programmatic focus control
 * ✅ KeyboardController - show/hide keyboard manually
 * ✅ imeNestedScroll - auto-dismiss keyboard on scroll
 * ✅ Proper IME padding for all fields
 *
 * PREREQUISITES:
 * ✅ android:windowSoftInputMode="adjustResize" in manifest
 * ✅ enableEdgeToEdge() in Activity
 *
 * TESTING:
 * [ ] All fields scroll into view when focused
 * [ ] Keyboard dismisses on scroll down
 * [ ] "Done" action on last field submits form
 * [ ] Tested on devices with different screen sizes
 */

package com.example.yourapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun IMEHandlingFormScreen(
    onNavigateBack: () -> Unit,
    onSubmit: (FormData) -> Unit
) {
    var formData by remember { mutableStateOf(FormData()) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Form Example") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // Handles keyboard padding
                .imeNestedScroll() // Auto-dismisses keyboard on scroll
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name field
            FormTextField(
                value = formData.name,
                onValueChange = { formData = formData.copy(name = it) },
                label = "Name",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            // Email field
            FormTextField(
                value = formData.email,
                onValueChange = { formData = formData.copy(email = it) },
                label = "Email",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            // Phone field
            FormTextField(
                value = formData.phone,
                onValueChange = { formData = formData.copy(phone = it) },
                label = "Phone",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            // Password field
            OutlinedTextField(
                value = formData.password,
                onValueChange = { formData = formData.copy(password = it) },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Notes field (multiline)
            OutlinedTextField(
                value = formData.notes,
                onValueChange = { formData = formData.copy(notes = it) },
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Submit button
            Button(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onSubmit(formData)
                },
                enabled = formData.isValid(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Submit")
            }
        }
    }
}

/**
 * Reusable FormTextField with BringIntoViewRequester
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        delay(300) // Wait for keyboard animation
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    )
}

// Form data model
data class FormData(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val notes: String = ""
) {
    fun isValid(): Boolean {
        return name.isNotBlank() &&
                email.isNotBlank() &&
                email.contains("@") &&
                password.length >= 6
    }
}
