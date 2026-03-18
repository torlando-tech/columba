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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R

/**
 * Welcome page - introduces privacy-first messaging.
 * Shows that no phone, email, or signup is required.
 */
@Composable
fun WelcomePage(
    onGetStarted: () -> Unit,
    onRestoreFromBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        // App icon
        Icon(
            imageVector = Icons.Default.Sensors,
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitle
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Privacy features
        PrivacyFeature(text = stringResource(R.string.onboarding_privacy_no_phone))
        Spacer(modifier = Modifier.height(12.dp))
        PrivacyFeature(text = stringResource(R.string.onboarding_privacy_no_email))
        Spacer(modifier = Modifier.height(12.dp))
        PrivacyFeature(text = stringResource(R.string.onboarding_privacy_no_signup))

        Spacer(modifier = Modifier.height(32.dp))

        // Identity explanation
        Text(
            text = stringResource(R.string.onboarding_identity_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Get Started button
        Button(
            onClick = onGetStarted,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_get_started),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Restore from backup
        TextButton(
            onClick = onRestoreFromBackup,
        ) {
            Text(
                text = stringResource(R.string.onboarding_restore_backup),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PrivacyFeature(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cancel,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
