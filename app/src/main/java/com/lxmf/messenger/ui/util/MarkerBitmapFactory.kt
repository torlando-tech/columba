package com.lxmf.messenger.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Factory for creating marker bitmaps for the map.
 */
object MarkerBitmapFactory {

    /**
     * Generate a consistent color from a hash string.
     * Uses HSV color space to ensure good saturation and brightness.
     *
     * @param hash The hash string (e.g., destinationHash)
     * @return An ARGB color int
     */
    fun hashToColor(hash: String): Int {
        val hue = (hash.hashCode() and 0xFFFFFF) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.7f, 0.8f))
    }

    /**
     * Creates a circular marker with a colored background, initial letter, and display name.
     *
     * @param initial The initial letter to display in the circle
     * @param displayName The full display name to show below the circle
     * @param backgroundColor The background color (use hashToColor for consistency)
     * @param sizeDp The diameter of the circle in dp
     * @param density Screen density for dp to px conversion
     * @return A bitmap with the marker (circle + name label)
     */
    fun createInitialMarker(
        initial: Char,
        displayName: String,
        backgroundColor: Int,
        sizeDp: Float = 40f,
        density: Float,
    ): Bitmap {
        val circleSizePx = (sizeDp * density).toInt()
        val textPadding = (4 * density).toInt()
        val labelHeight = (18 * density).toInt()

        // Measure text width to size bitmap appropriately
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = namePaint.measureText(displayName)
        val haloPadding = 6f * density // Extra space for halo stroke

        // Total bitmap dimensions - width based on max of circle or text
        val totalHeight = circleSizePx + textPadding + labelHeight
        val minWidth = circleSizePx // At least as wide as the circle
        val textRequiredWidth = (textWidth + haloPadding * 2).toInt()
        val totalWidth = maxOf(minWidth, textRequiredWidth)
        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centerX = totalWidth / 2f
        val circleY = circleSizePx / 2f
        val radius = circleSizePx / 2f - (2f * density)

        // Draw circle background
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, circleY, radius, circlePaint)

        // Draw white border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        canvas.drawCircle(centerX, circleY, radius, borderPaint)

        // Draw initial letter in circle
        val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = circleSizePx * 0.45f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val initialY = circleY - (initialPaint.descent() + initialPaint.ascent()) / 2
        canvas.drawText(initial.uppercase().toString(), centerX, initialY, initialPaint)

        // Draw display name below circle with halo effect for readability
        val nameY = circleSizePx.toFloat() + textPadding + labelHeight * 0.7f

        // Configure namePaint for centered drawing (already has size and typeface from measurement)
        namePaint.textAlign = Paint.Align.CENTER

        // White halo/outline
        namePaint.color = Color.WHITE
        namePaint.style = Paint.Style.STROKE
        namePaint.strokeWidth = 3f * density
        canvas.drawText(displayName, centerX, nameY, namePaint)

        // Dark text on top
        namePaint.color = Color.parseColor("#212121")
        namePaint.style = Paint.Style.FILL
        canvas.drawText(displayName, centerX, nameY, namePaint)

        return bitmap
    }

    /**
     * Creates a dashed circle ring bitmap for stale location markers.
     *
     * @param sizeDp The diameter of the circle in density-independent pixels
     * @param strokeWidthDp The stroke width in dp
     * @param color The stroke color
     * @param dashLengthDp The length of each dash in dp
     * @param gapLengthDp The length of each gap in dp
     * @param density Screen density for dp to px conversion
     * @return A bitmap with a dashed circle ring (transparent center)
     */
    fun createDashedCircle(
        sizeDp: Float = 28f,
        strokeWidthDp: Float = 3f,
        color: Int,
        dashLengthDp: Float = 4f,
        gapLengthDp: Float = 4f,
        density: Float,
    ): Bitmap {
        val sizePx = (sizeDp * density).toInt()
        val strokeWidthPx = strokeWidthDp * density
        val dashLengthPx = dashLengthDp * density
        val gapLengthPx = gapLengthDp * density

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(dashLengthPx, gapLengthPx), 0f)
        }

        // Draw circle with stroke inset by half stroke width to keep within bounds
        val radius = (sizePx - strokeWidthPx) / 2f
        val center = sizePx / 2f
        canvas.drawCircle(center, center, radius, paint)

        return bitmap
    }

    /**
     * Creates a solid circle with dashed outline for stale markers.
     * Combines a filled circle with a dashed stroke.
     *
     * @param sizeDp The diameter of the circle in dp
     * @param fillColor The fill color of the circle
     * @param strokeColor The stroke color (for dashed outline)
     * @param fillOpacity Opacity for the fill (0-1)
     * @param strokeWidthDp Stroke width in dp
     * @param dashLengthDp Length of each dash in dp
     * @param gapLengthDp Length of each gap in dp
     * @param density Screen density
     * @return A bitmap with filled circle and dashed outline
     */
    fun createFilledCircleWithDashedOutline(
        sizeDp: Float = 28f,
        fillColor: Int,
        strokeColor: Int,
        fillOpacity: Float = 0.6f,
        strokeWidthDp: Float = 3f,
        dashLengthDp: Float = 4f,
        gapLengthDp: Float = 4f,
        density: Float,
    ): Bitmap {
        val sizePx = (sizeDp * density).toInt()
        val strokeWidthPx = strokeWidthDp * density
        val dashLengthPx = dashLengthDp * density
        val gapLengthPx = gapLengthDp * density

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val radius = (sizePx - strokeWidthPx) / 2f

        // Draw filled circle
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
            alpha = (fillOpacity * 255).toInt()
        }
        canvas.drawCircle(center, center, radius, fillPaint)

        // Draw dashed outline
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = strokeColor
            alpha = (fillOpacity * 255).toInt()
            pathEffect = DashPathEffect(floatArrayOf(dashLengthPx, gapLengthPx), 0f)
        }
        canvas.drawCircle(center, center, radius, strokePaint)

        return bitmap
    }
}
