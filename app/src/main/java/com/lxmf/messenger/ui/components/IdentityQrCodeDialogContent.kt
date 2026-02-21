package com.lxmf.messenger.ui.components

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Reusable full-screen dialog for displaying identity QR code and details.
 *
 * @param displayName The display name to show as the header
 * @param qrCodeData The QR code data string (null to hide QR code)
 * @param onDismiss Callback when dialog is dismissed
 * @param title The title shown in the TopAppBar
 * @param actionsContent Slot for action buttons and additional content
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityQrCodeDialogContent(
    displayName: String,
    qrCodeData: String?,
    onDismiss: () -> Unit,
    title: String = "Your Identity",
    actionsContent: @Composable ColumnScope.() -> Unit,
) {
    // Capture Activity context before Dialog (inside Dialog, LocalContext is a ContextThemeWrapper)
    val activity = LocalContext.current.findActivity()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Max brightness + keep screen on while showing QR code
        DisposableEffect(Unit) {
            val window = activity.window
            val originalBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.also { it.screenBrightness = 1f }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window.attributes =
                    window.attributes.also {
                        it.screenBrightness = originalBrightness
                    }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // Display Name
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // QR Code
                    if (qrCodeData != null) {
                        QrCodeImage(
                            data = qrCodeData,
                            size = 280.dp,
                        )

                        Text(
                            text = "Scan this QR code to add me as a contact",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Custom actions and content
                    actionsContent()

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
