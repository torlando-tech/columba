package network.columba.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Default explanation shown inside the IFAC card. Used when the caller does
 * not supply its own [IfacConfigCard.description].
 */
internal const val DEFAULT_IFAC_DESCRIPTION: String =
    "Leave blank unless the remote interface requires an IFAC network " +
        "name and passphrase. Auto-filled when adding from a discovered interface."

/**
 * Reusable Material card that renders the two IFAC (network access) fields —
 * network name and passphrase — with consistent copy, spacing, and the
 * password-visibility eye icon used across the TCP Client wizard, the RNode
 * wizard, and the shared interface-config dialog.
 *
 * State is fully hoisted: the caller owns [networkName], [passphrase], and
 * [passphraseVisible] and handles changes via the corresponding callbacks.
 *
 * @param networkName Current value of the IFAC network name field.
 * @param passphrase Current value of the IFAC passphrase field.
 * @param passphraseVisible Whether the passphrase should be rendered as
 *   plaintext ([VisualTransformation.None]) or masked
 *   ([PasswordVisualTransformation]).
 * @param onNetworkNameChange Invoked when the network name text changes.
 * @param onPassphraseChange Invoked when the passphrase text changes.
 * @param onPassphraseVisibilityToggle Invoked when the user taps the eye icon.
 * @param modifier Applied to the outer [Card].
 * @param description Explanatory body text rendered under the header; defaults
 *   to [DEFAULT_IFAC_DESCRIPTION]. Pass a wizard-specific string when the
 *   guidance needs tweaking (e.g. "Only interfaces with matching credentials
 *   can communicate").
 * @param networkNameError Optional error message surfaced beneath the network
 *   name field via [OutlinedTextField.supportingText] / [OutlinedTextField.isError].
 * @param passphraseError Optional error message surfaced beneath the
 *   passphrase field.
 */
@Composable
fun IfacConfigCard(
    networkName: String,
    passphrase: String,
    passphraseVisible: Boolean,
    onNetworkNameChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onPassphraseVisibilityToggle: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = DEFAULT_IFAC_DESCRIPTION,
    networkNameError: String? = null,
    passphraseError: String? = null,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "IFAC (Network Access)",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = networkName,
                onValueChange = onNetworkNameChange,
                label = { Text("Network Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = networkNameError != null,
                supportingText = networkNameError?.let { { Text(it) } },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = { Text("Passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = passphraseError != null,
                supportingText = passphraseError?.let { { Text(it) } },
                visualTransformation =
                    if (passphraseVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    IconButton(onClick = onPassphraseVisibilityToggle) {
                        Icon(
                            imageVector =
                                if (passphraseVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                            contentDescription =
                                if (passphraseVisible) {
                                    "Hide passphrase"
                                } else {
                                    "Show passphrase"
                                },
                        )
                    }
                },
            )
        }
    }
}
