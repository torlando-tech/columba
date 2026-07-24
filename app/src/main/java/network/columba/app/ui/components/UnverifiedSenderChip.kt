package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Inline warning banner for messages whose LXMF signature could not be verified
 * against a known sender identity (`UnverifiedReason.SOURCE_UNKNOWN`). Mirrors
 * Sideband's "this message is likely to be fake" banner; rendered above a
 * message bubble when `MessageUi.signatureVerified == false`.
 *
 * Threat model: the LXMF wire layer encrypts to the recipient's public key, so
 * decryption alone does NOT prove the sender's identity — the signature does.
 * When we don't yet hold the sender's identity (their announce hasn't reached
 * us), the signature can't be checked, and the message could be a legitimate
 * first-contact OR a forgery from anyone who generated a fresh identity hash.
 * Rendered with `errorContainer` for the same warning affordance the app uses
 * for failed states.
 */
@Composable
fun UnverifiedSenderChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                // Decorative — the adjacent Text already conveys the meaning, so
                // a contentDescription here would double-read in TalkBack.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Unverified sender — may be forged",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
