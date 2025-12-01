package com.lxmf.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Generates a unique visual identicon from a byte array hash.
 * Creates a symmetric 5x5 grid pattern with two colors derived from the hash.
 *
 * @param hash The byte array to generate the identicon from (typically a public key or hash)
 * @param size The size of the identicon
 * @param modifier Optional modifier for the composable
 */
@Composable
fun Identicon(
    hash: ByteArray,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Handle empty or invalid hashes defensively
    if (hash.isEmpty() || hash.size < 6) {
        // Show a default gray circle for invalid hashes
        Box(
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color.Gray),
        )
        return
    }

    // Generate colors from hash
    val primaryColor =
        Color(
            red = (hash[0].toInt() and 0xFF) / 255f,
            green = (hash[1].toInt() and 0xFF) / 255f,
            blue = (hash[2].toInt() and 0xFF) / 255f,
        )

    val secondaryColor =
        Color(
            red = (hash[3].toInt() and 0xFF) / 255f,
            green = (hash[4].toInt() and 0xFF) / 255f,
            blue = (hash[5].toInt() and 0xFF) / 255f,
        )

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size
            val cellSize = canvasSize.width / 5f

            // Draw a 5x5 grid identicon (symmetric)
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    val hashIndex = (row * 3 + col) % hash.size
                    val byteValue = hash[hashIndex].toInt() and 0xFF

                    // Use byte value to determine if cell should be filled
                    if (byteValue > 127) {
                        val color = if (byteValue % 2 == 0) primaryColor else secondaryColor

                        // Draw on both sides for symmetry
                        drawCircle(
                            color = color,
                            radius = cellSize / 2.5f,
                            center =
                                Offset(
                                    x = col * cellSize + cellSize / 2f,
                                    y = row * cellSize + cellSize / 2f,
                                ),
                        )

                        // Mirror on the right side
                        if (col < 2) {
                            drawCircle(
                                color = color,
                                radius = cellSize / 2.5f,
                                center =
                                    Offset(
                                        x = (4 - col) * cellSize + cellSize / 2f,
                                        y = row * cellSize + cellSize / 2f,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
