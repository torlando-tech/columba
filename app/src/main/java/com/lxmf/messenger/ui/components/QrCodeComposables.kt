package com.lxmf.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        modifier = modifier.size(size),
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
