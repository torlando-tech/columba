package network.columba.app.ui.screens.settings.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.components.HashSection
import network.columba.app.ui.components.IdentityQrCodeDialogContent

@Composable
fun IdentityQrCodeDialog(
    displayName: String,
    identityHash: String?,
    destinationHash: String?,
    qrCodeData: String?,
    onDismiss: () -> Unit,
    onShareClick: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    IdentityQrCodeDialogContent(
        displayName = displayName,
        qrCodeData = qrCodeData,
        onDismiss = onDismiss,
    ) {
        // Share Button
        Button(
            onClick = onShareClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.identity_share_identity))
        }

        HorizontalDivider()

        // Identity Hash
        if (identityHash != null) {
            HashSection(
                title = stringResource(R.string.identity_hash),
                hash = identityHash,
                onCopy = { clipboardManager.setText(AnnotatedString(identityHash)) },
            )
        }

        // Destination Hash
        if (destinationHash != null) {
            HashSection(
                title = stringResource(R.string.identity_destination_hash_lxmf),
                onCopy = { clipboardManager.setText(AnnotatedString(destinationHash)) },
                hash = destinationHash,
            )
        }
    }
}
