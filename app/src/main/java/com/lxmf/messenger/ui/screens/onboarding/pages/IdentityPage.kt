package com.lxmf.messenger.ui.screens.onboarding.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R

/**
 * Identity page - allows user to set their display name.
 */
@Composable
fun IdentityPage(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Icon
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = stringResource(R.string.onboarding_identity_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitle
        Text(
            text = stringResource(R.string.onboarding_identity_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Display name input
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text(stringResource(R.string.onboarding_display_name_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_anonymous_peer)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onContinue()
                    },
                ),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Helper text
        Text(
            text = stringResource(R.string.onboarding_identity_helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.action_back))
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onContinue()
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
