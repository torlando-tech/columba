package com.lxmf.messenger.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint

/**
 * Factory for creating marker bitmaps for the map.
 */
object MarkerBitmapFactory {

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
