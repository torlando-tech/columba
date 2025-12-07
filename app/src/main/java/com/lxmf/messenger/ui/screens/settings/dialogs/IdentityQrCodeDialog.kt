package com.lxmf.messenger.ui.screens.settings.dialogs

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.components.HashSection
import com.lxmf.messenger.ui.components.IdentityQrCodeDialogContent

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
            Text("Share Identity")
        }

        HorizontalDivider()

        // Identity Hash
        if (identityHash != null) {
            HashSection(
                title = "Identity Hash",
                hash = identityHash,
                onCopy = { clipboardManager.setText(AnnotatedString(identityHash)) },
            )
        }

        // Destination Hash
        if (destinationHash != null) {
            HashSection(
                title = "Destination Hash (LXMF)",
                hash = destinationHash,
                onCopy = { clipboardManager.setText(AnnotatedString(destinationHash)) },
            )
        }
    }
}
