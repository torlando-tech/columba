package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeBottomSheet(
    onDismiss: () -> Unit,
    onScanQrCode: () -> Unit,
    onShowQrCode: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "QR Code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Scan QR Code option
            ListItem(
                headlineContent = { Text("Scan QR Code") },
                supportingContent = { Text("Scan a contact's QR code") },
                leadingContent = {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onScanQrCode()
                    },
            )

            // Show QR Code option
            ListItem(
                headlineContent = { Text("Show QR Code") },
                supportingContent = { Text("Show your QR code for others to scan") },
                leadingContent = {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onShowQrCode()
                    },
            )
        }
    }
}
