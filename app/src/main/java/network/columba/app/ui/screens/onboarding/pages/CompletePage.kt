package network.columba.app.ui.screens.onboarding.pages

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.screens.onboarding.OnboardingInterfaceType
import network.columba.app.ui.screens.settings.dialogs.IdentityQrCodeDialog

/**
 * Complete page - shows summary and starts messaging.
 */
@Composable
fun CompletePage(
    displayName: String,
    selectedInterfaces: Set<OnboardingInterfaceType>,
    notificationsEnabled: Boolean,
    batteryOptimizationExempt: Boolean,
    isSaving: Boolean,
    onStartMessaging: () -> Unit,
    modifier: Modifier = Modifier,
    identityHash: String? = null,
    destinationHash: String? = null,
    qrCodeData: String? = null,
) {
    val context = LocalContext.current
    var showQrDialog by remember { mutableStateOf(false) }
    val hasLoRaSelected = selectedInterfaces.contains(OnboardingInterfaceType.RNODE)
    val anonymousPeer = stringResource(R.string.onboarding_anonymous_peer)

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

        // Success icon
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = stringResource(R.string.onboarding_complete_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_complete_summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )

                SummaryRow(
                    label = stringResource(R.string.onboarding_complete_identity),
                    value = displayName.ifEmpty { anonymousPeer },
                )

                SummaryRow(
                    label = stringResource(R.string.onboarding_complete_networks),
                    value =
                        if (selectedInterfaces.isEmpty()) {
                            stringResource(R.string.onboarding_complete_none)
                        } else {
                            selectedInterfaces.joinToString(", ") { context.getString(it.displayNameRes) }
                        },
                )

                SummaryRow(
                    label = stringResource(R.string.onboarding_complete_notifications),
                    value =
                        if (notificationsEnabled) {
                            stringResource(R.string.onboarding_complete_enabled)
                        } else {
                            stringResource(R.string.onboarding_complete_disabled)
                        },
                    isEnabled = notificationsEnabled,
                )

                SummaryRow(
                    label = stringResource(R.string.onboarding_complete_battery),
                    value =
                        if (batteryOptimizationExempt) {
                            stringResource(R.string.onboarding_complete_unrestricted)
                        } else {
                            stringResource(R.string.onboarding_complete_restricted)
                        },
                    isEnabled = batteryOptimizationExempt,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Show QR Code button
        FilledTonalButton(
            onClick = { showQrDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = qrCodeData != null,
        ) {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_complete_show_qr))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // QR code hint
        Text(
            text = stringResource(R.string.onboarding_complete_qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Start messaging button (or Configure LoRa Radio if LoRa is selected)
        Button(
            onClick = onStartMessaging,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isSaving,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text =
                        if (hasLoRaSelected) {
                            stringResource(R.string.onboarding_complete_configure_lora)
                        } else {
                            stringResource(R.string.onboarding_complete_start_messaging)
                        },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // QR Code Dialog
    if (showQrDialog && qrCodeData != null) {
        IdentityQrCodeDialog(
            displayName = displayName.ifEmpty { anonymousPeer },
            identityHash = identityHash,
            destinationHash = destinationHash,
            qrCodeData = qrCodeData,
            onDismiss = { showQrDialog = false },
            onShareClick = {
                val shareText = context.getString(R.string.onboarding_complete_share_text, displayName.ifEmpty { anonymousPeer }, qrCodeData)
                val sendIntent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        type = "text/plain"
                    }
                context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.onboarding_complete_share_chooser)))
            },
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isEnabled: Boolean? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isEnabled == true) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color =
                    when (isEnabled) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.onSurfaceVariant
                        null -> MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}
