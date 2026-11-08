package network.columba.app.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.ui.graphics.Color
import network.columba.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignalQualityInfoTest {
    // RSSI: Excellent (>-50), Good (-50 to -70), Fair (-70 to -85), Weak (-85 to -100), Very Weak (<-100)
    // SNR: Excellent (>10), Good (5-10), Fair (0-5), Poor (-5-0), Very Poor (<-5)

    // Test color constants (match production code)
    companion object {
        private val ColorExcellent = Color(0xFF4CAF50)
        private val ColorGood = Color(0xFF8BC34A)
        private val ColorFair = Color(0xFFFFC107)
        private val ColorPoor = Color(0xFFFF9800)
        private val ColorVeryPoor = Color(0xFFF44336)
    }

    @Test
    fun `getRssiInfo returns excellent for very strong signal`() {
        val info = getRssiInfo(-40)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorExcellent, info.color)
        assertEquals(R.string.signal_quality_rssi_excellent, info.textRes)
        assertEquals(R.string.signal_quality_rssi_sub_excellent, info.subtitleRes)
        assertEquals(listOf<Any>(-40), info.textArgs)
    }

    @Test
    fun `getRssiInfo returns excellent at boundary`() {
        val info = getRssiInfo(-49)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(R.string.signal_quality_rssi_excellent, info.textRes)
    }

    @Test
    fun `getRssiInfo returns good for strong signal`() {
        val info = getRssiInfo(-60)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorGood, info.color)
        assertEquals(R.string.signal_quality_rssi_good, info.textRes)
        assertEquals(R.string.signal_quality_rssi_sub_good, info.subtitleRes)
        assertEquals(listOf<Any>(-60), info.textArgs)
    }

    @Test
    fun `getRssiInfo returns good at lower boundary`() {
        val info = getRssiInfo(-50)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(R.string.signal_quality_rssi_good, info.textRes)
    }

    @Test
    fun `getRssiInfo returns fair for moderate signal`() {
        val info = getRssiInfo(-75)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorFair, info.color)
        assertEquals(R.string.signal_quality_rssi_fair, info.textRes)
        assertEquals(R.string.signal_quality_rssi_sub_fair, info.subtitleRes)
        assertEquals(listOf<Any>(-75), info.textArgs)
    }

    @Test
    fun `getRssiInfo returns fair at lower boundary`() {
        val info = getRssiInfo(-70)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(R.string.signal_quality_rssi_fair, info.textRes)
    }

    @Test
    fun `getRssiInfo returns weak for low signal`() {
        val info = getRssiInfo(-90)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorPoor, info.color)
        assertEquals(R.string.signal_quality_rssi_weak, info.textRes)
        assertEquals(R.string.signal_quality_rssi_sub_weak, info.subtitleRes)
        assertEquals(listOf<Any>(-90), info.textArgs)
    }

    @Test
    fun `getRssiInfo returns weak at lower boundary`() {
        val info = getRssiInfo(-85)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(R.string.signal_quality_rssi_weak, info.textRes)
    }

    @Test
    fun `getRssiInfo returns very weak for marginal signal`() {
        val info = getRssiInfo(-110)

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(ColorVeryPoor, info.color)
        assertEquals(R.string.signal_quality_rssi_very_weak, info.textRes)
        assertEquals(R.string.signal_quality_rssi_sub_very_weak, info.subtitleRes)
        assertEquals(listOf<Any>(-110), info.textArgs)
    }

    @Test
    fun `getRssiInfo returns very weak at boundary`() {
        val info = getRssiInfo(-100)

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(R.string.signal_quality_rssi_very_weak, info.textRes)
    }

    @Test
    fun `getSnrInfo returns excellent for clear signal`() {
        val info = getSnrInfo(15.0f)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorExcellent, info.color)
        assertEquals(R.string.signal_quality_snr_excellent, info.textRes)
        assertEquals(R.string.signal_quality_snr_sub_excellent, info.subtitleRes)
        assertEquals(listOf<Any>("15.0"), info.textArgs)
    }

    @Test
    fun `getSnrInfo returns excellent at boundary`() {
        val info = getSnrInfo(10.1f)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(R.string.signal_quality_snr_excellent, info.textRes)
    }

    @Test
    fun `getSnrInfo returns good for low noise`() {
        val info = getSnrInfo(7.5f)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorGood, info.color)
        assertEquals(R.string.signal_quality_snr_good, info.textRes)
        assertEquals(R.string.signal_quality_snr_sub_good, info.subtitleRes)
        assertEquals(listOf<Any>("7.5"), info.textArgs)
    }

    @Test
    fun `getSnrInfo returns good at lower boundary`() {
        val info = getSnrInfo(5.1f)

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(R.string.signal_quality_snr_good, info.textRes)
    }

    @Test
    fun `getSnrInfo returns fair for moderate noise`() {
        val info = getSnrInfo(2.5f)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorFair, info.color)
        assertEquals(R.string.signal_quality_snr_fair, info.textRes)
        assertEquals(R.string.signal_quality_snr_sub_fair, info.subtitleRes)
        assertEquals(listOf<Any>("2.5"), info.textArgs)
    }

    @Test
    fun `getSnrInfo returns fair at lower boundary`() {
        val info = getSnrInfo(0.1f)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(R.string.signal_quality_snr_fair, info.textRes)
    }

    @Test
    fun `getSnrInfo returns poor for high noise`() {
        val info = getSnrInfo(-2.5f)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorPoor, info.color)
        assertEquals(R.string.signal_quality_snr_poor, info.textRes)
        assertEquals(R.string.signal_quality_snr_sub_poor, info.subtitleRes)
        assertEquals(listOf<Any>("-2.5"), info.textArgs)
    }

    @Test
    fun `getSnrInfo returns poor at lower boundary`() {
        val info = getSnrInfo(-4.9f)

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(R.string.signal_quality_snr_poor, info.textRes)
    }

    @Test
    fun `getSnrInfo returns very poor for very high noise`() {
        val info = getSnrInfo(-10.0f)

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(ColorVeryPoor, info.color)
        assertEquals(R.string.signal_quality_snr_very_poor, info.textRes)
        assertEquals(R.string.signal_quality_snr_sub_very_poor, info.subtitleRes)
        assertEquals(listOf<Any>("-10.0"), info.textArgs)
    }

    @Test
    fun `getSnrInfo returns very poor at boundary`() {
        val info = getSnrInfo(-5.0f)

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(R.string.signal_quality_snr_very_poor, info.textRes)
    }

    @Test
    fun `getSnrInfo formats decimal correctly`() {
        val info = getSnrInfo(8.333333f)

        assertEquals(listOf<Any>("8.3"), info.textArgs)
    }
}
