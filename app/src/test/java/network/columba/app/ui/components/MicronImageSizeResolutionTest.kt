package network.columba.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicronImageSizeResolutionTest {

    @Test
    fun `percentage width resolves to a parent-relative fraction`() {
        assertEquals(1.0f, resolveImageFraction("100%")!!, 0.0001f)
        assertEquals(0.5f, resolveImageFraction("50%")!!, 0.0001f)
        assertEquals(0.25f, resolveImageFraction("25%")!!, 0.0001f)
    }

    @Test
    fun `out-of-range percentage is coerced to the valid fraction`() {
        assertEquals(1.0f, resolveImageFraction("150%")!!, 0.0001f)
        assertEquals(0.0f, resolveImageFraction("-20%")!!, 0.0001f)
        assertEquals(0.0f, resolveImageFraction("0%")!!, 0.0001f)
    }

    @Test
    fun `non-percentage and malformed specs are not fractions`() {
        assertNull(resolveImageFraction(null))
        assertNull(resolveImageFraction("n"))
        assertNull(resolveImageFraction("40"))
        assertNull(resolveImageFraction("abc%"))
        assertNull(resolveImageFraction("%"))
    }

    @Test
    fun `numeric width spec is a count of columns in dp`() {
        // 40 columns * 8 dp/col = 320 dp (no density conversion).
        assertEquals(320.dp, resolveImageSize("40", 8f))
        assertEquals(8.dp, resolveImageSize("1", 8f))
    }

    @Test
    fun `numeric height spec is a count of rows in dp`() {
        // 10 rows * 16 dp/row = 160 dp.
        assertEquals(160.dp, resolveImageSize("10", 16f))
    }

    @Test
    fun `percentage and intrinsic width resolve to null from the dp resolver`() {
        // Percentages are handled by resolveImageFraction, not the dp resolver.
        assertNull(resolveImageSize("100%", 8f))
        assertNull(resolveImageSize("n", 8f))
        assertNull(resolveImageSize(null, 8f))
        assertNull(resolveImageSize("abc", 8f))
    }
}
