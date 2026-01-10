package com.lxmf.messenger.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for EXIF orientation handling and bitmap transformation logic.
 * Tests the actual ImageUtils functions (marked @VisibleForTesting).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageUtilsExifTest {
    // ========== Helper functions ==========

    private fun createTestBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
    }

    // ========== EXIF Orientation Constants ==========

    @Test
    fun `ORIENTATION_NORMAL value is 1`() {
        assertEquals(1, ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun `ORIENTATION_ROTATE_90 value is 6`() {
        assertEquals(6, ExifInterface.ORIENTATION_ROTATE_90)
    }

    @Test
    fun `ORIENTATION_ROTATE_180 value is 3`() {
        assertEquals(3, ExifInterface.ORIENTATION_ROTATE_180)
    }

    @Test
    fun `ORIENTATION_ROTATE_270 value is 8`() {
        assertEquals(8, ExifInterface.ORIENTATION_ROTATE_270)
    }

    // ========== Rotation Tests ==========

    // ========== EXIF Orientation Tests (using real ImageUtils.applyExifOrientation) ==========

    @Test
    fun `ORIENTATION_NORMAL does not change dimensions`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_NORMAL)

        assertEquals(200, result.width)
        assertEquals(100, result.height)

        original.recycle()
        if (result != original) result.recycle()
    }

    @Test
    fun `ORIENTATION_ROTATE_90 swaps width and height`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_ROTATE_90)

        assertEquals(100, result.width)
        assertEquals(200, result.height)

        original.recycle()
        result.recycle()
    }

    @Test
    fun `ORIENTATION_ROTATE_180 preserves dimensions`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_ROTATE_180)

        assertEquals(200, result.width)
        assertEquals(100, result.height)

        original.recycle()
        result.recycle()
    }

    @Test
    fun `ORIENTATION_ROTATE_270 swaps width and height`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_ROTATE_270)

        assertEquals(100, result.width)
        assertEquals(200, result.height)

        original.recycle()
        result.recycle()
    }

    // ========== Flip Tests ==========

    @Test
    fun `ORIENTATION_FLIP_HORIZONTAL preserves dimensions`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)

        assertEquals(200, result.width)
        assertEquals(100, result.height)

        original.recycle()
        result.recycle()
    }

    @Test
    fun `ORIENTATION_FLIP_VERTICAL preserves dimensions`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_FLIP_VERTICAL)

        assertEquals(200, result.width)
        assertEquals(100, result.height)

        original.recycle()
        result.recycle()
    }

    // ========== Transpose/Transverse Tests ==========

    @Test
    fun `ORIENTATION_TRANSPOSE swaps width and height`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_TRANSPOSE)

        assertEquals(100, result.width)
        assertEquals(200, result.height)

        original.recycle()
        result.recycle()
    }

    @Test
    fun `ORIENTATION_TRANSVERSE swaps width and height`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_TRANSVERSE)

        assertEquals(100, result.width)
        assertEquals(200, result.height)

        original.recycle()
        result.recycle()
    }

    // ========== Unknown Orientation ==========

    @Test
    fun `unknown orientation value returns original bitmap unchanged`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, 999) // Invalid orientation

        // Should return the same bitmap instance
        assertTrue(result === original)
        assertEquals(200, result.width)
        assertEquals(100, result.height)

        original.recycle()
    }

    @Test
    fun `ORIENTATION_UNDEFINED returns original bitmap unchanged`() {
        val original = createTestBitmap(200, 100)
        val result = ImageUtils.applyExifOrientation(original, ExifInterface.ORIENTATION_UNDEFINED)

        assertTrue(result === original)

        original.recycle()
    }

    // ========== Sample Size Calculation Tests (using real ImageUtils.calculateSampleSize) ==========

    @Test
    fun `sample size calculation for image within limits`() {
        // Image 1000x800 with max 2048: no sampling needed
        val sampleSize = ImageUtils.calculateSampleSize(1000, 800, 2048)
        assertEquals(1, sampleSize)
    }

    @Test
    fun `sample size calculation for large image`() {
        // Image 4000x3000 with max 2048
        // halfWidth=2000, halfHeight=1500, both < 2048 so sampleSize=1
        val sampleSize = ImageUtils.calculateSampleSize(4000, 3000, 2048)
        assertEquals(1, sampleSize)
    }

    @Test
    fun `sample size calculation for image exceeding limit`() {
        // Image 5000x4000 with max 2048
        // halfWidth=2500 >= 2048, so loop runs: sampleSize=2
        // Check: 2500/2=1250 < 2048, stop
        val sampleSize = ImageUtils.calculateSampleSize(5000, 4000, 2048)
        assertEquals(2, sampleSize)
    }

    @Test
    fun `sample size calculation for very large image`() {
        // Image 8000x6000 with max 2048
        // halfWidth=4000, halfHeight=3000
        // 4000/1 >= 2048: yes, sampleSize=2
        // 4000/2=2000 >= 2048: no, stop
        val sampleSize = ImageUtils.calculateSampleSize(8000, 6000, 2048)
        assertEquals(2, sampleSize)
    }

    @Test
    fun `sample size calculation for huge panorama`() {
        // Image 16000x4000 with max 2048
        // halfWidth=8000, halfHeight=2000
        // 8000/1 >= 2048: yes, sampleSize=2
        // 8000/2=4000 >= 2048: yes, sampleSize=4
        // 8000/4=2000 >= 2048: no, stop
        val sampleSize = ImageUtils.calculateSampleSize(16000, 4000, 2048)
        assertEquals(4, sampleSize)
    }

    @Test
    fun `sample size calculation for LOW preset`() {
        // Image 4000x3000 with max 320 (LOW preset)
        // halfWidth=2000, halfHeight=1500
        // 2000/1 >= 320: yes, 1500/1 >= 320: yes, sampleSize=2
        // 2000/2=1000 >= 320: yes, sampleSize=4
        // 2000/4=500 >= 320: yes, sampleSize=8
        // 2000/8=250 >= 320: no, stop
        val sampleSize = ImageUtils.calculateSampleSize(4000, 3000, 320)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `sample size calculation for extremely large image`() {
        // Image 16320x6992 (the real test case that caused the crash)
        // with max 320 (LOW preset)
        // halfWidth=8160, halfHeight=3496
        // Continue until halfWidth/sampleSize < 320
        // sampleSize=32: 8160/32=255 < 320, stop
        val sampleSize = ImageUtils.calculateSampleSize(16320, 6992, 320)
        assertEquals(32, sampleSize)
    }

    // ========== Matrix Transformation Tests ==========

    @Test
    fun `Matrix postRotate 90 creates correct transformation`() {
        val matrix = Matrix()
        matrix.postRotate(90f)

        val values = FloatArray(9)
        matrix.getValues(values)

        // After 90° rotation, MSCALE_X should be ~0 (cos 90)
        // The skew values depend on the rotation direction
        assertEquals(0f, values[Matrix.MSCALE_X], 0.001f)
        // MSKEW_X is -sin(90) = -1 for counterclockwise rotation
        assertEquals(-1f, values[Matrix.MSKEW_X], 0.001f)
    }

    @Test
    fun `Matrix preScale -1, 1 creates horizontal flip`() {
        val matrix = Matrix()
        matrix.preScale(-1f, 1f)

        val values = FloatArray(9)
        matrix.getValues(values)

        assertEquals(-1f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(1f, values[Matrix.MSCALE_Y], 0.001f)
    }
}
