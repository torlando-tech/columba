package com.lxmf.messenger.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unwrap a Context to find the Activity, since Dialog wraps context in ContextThemeWrapper. */
internal fun Context.findActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("No Activity found in context chain")
}

/**
 * Composable that displays a QR code image generated from a data string.
 *
 * @param data The data to encode in the QR code
 * @param size The size of the QR code (width and height)
 * @param modifier Modifier for the composable
 */
@Composable
fun QrCodeImage(
    data: String,
    size: Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    var qrBitmap by remember(data) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(data) { mutableStateOf(true) }
    var error by remember(data) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(data) {
        if (data.isBlank()) {
            isLoading = false
            error = "No data to encode"
            return@LaunchedEffect
        }

        scope.launch {
            try {
                isLoading = true
                error = null

                val bitmap =
                    withContext(Dispatchers.Default) {
                        generateQrCodeBitmap(data, size.value.toInt())
                    }

                qrBitmap = bitmap
                isLoading = false
            } catch (e: Exception) {
                error = "Failed to generate QR code"
                isLoading = false
            }
        }
    }

    Surface(
        modifier =
            modifier
                .size(size)
                .clickable(enabled = qrBitmap != null) { expanded = true },
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                error != null -> {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                qrBitmap != null -> {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // Full-screen expanded QR code overlay
    val activity = LocalContext.current.findActivity()
    if (expanded && qrBitmap != null) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Max brightness + keep screen on while expanded
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
                        .clickable { expanded = false },
                color = Color.White,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code (tap to close)",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

/**
 * Generates a QR code bitmap from the given data.
 *
 * @param data The data to encode
 * @param sizePx The size in pixels (width and height)
 * @return The generated QR code bitmap
 */
private fun generateQrCodeBitmap(
    data: String,
    sizePx: Int,
): Bitmap {
    val hints =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )

    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(
                x,
                y,
                if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }

    return bitmap
}

/**
 * Composable that displays a hash value with a title and copy button.
 * Used in identity dialogs to display hash values.
 *
 * @param title The title to display above the hash
 * @param hash The hash value to display
 * @param onCopy Callback when the copy button is clicked
 */
@Composable
fun HashSection(
    title: String,
    hash: String,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
