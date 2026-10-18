package network.columba.app.ui.util

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import network.columba.app.R
import java.util.Locale

// Color constants for signal quality indicators
private val ColorExcellent = Color(0xFF4CAF50) // Green
private val ColorGood = Color(0xFF8BC34A) // Light Green
private val ColorFair = Color(0xFFFFC107) // Amber
private val ColorPoor = Color(0xFFFF9800) // Orange
private val ColorVeryPoor = Color(0xFFF44336) // Red

/**
 * Information about signal quality for display in UI.
 * Used for both RSSI (signal strength) and SNR (signal-to-noise ratio) metrics.
 *
 * [textRes]/[subtitleRes] are localized resource ids; render with
 * [resolvedText] / `stringResource(info.subtitleRes)` at the UI edge.
 */
data class SignalQualityInfo(
    val icon: ImageVector,
    val color: Color,
    @StringRes val textRes: Int,
    val textArgs: List<Any> = emptyList(),
    @StringRes val subtitleRes: Int,
)

/** Resolves [textRes] with [textArgs] into display text at the UI edge. */
@Composable
@Suppress("SpreadOperator") // stringResource's vararg format-args API requires the spread
fun SignalQualityInfo.resolvedText(): String = stringResource(textRes, *textArgs.toTypedArray())

/**
 * Get signal strength (RSSI) display information.
 *
 * RSSI thresholds are based on typical LoRa/BLE radio characteristics:
 * - Excellent: > -50 dBm (very close proximity or ideal conditions)
 * - Good: -50 to -70 dBm (reliable communication)
 * - Fair: -70 to -85 dBm (usable but may have occasional issues)
 * - Weak: -85 to -100 dBm (marginal, may need retries)
 * - Very Weak: < -100 dBm (at the edge of usability)
 *
 * @param rssi RSSI value in dBm (typically -30 to -120)
 * @return SignalQualityInfo with appropriate icon, color, and text
 */
fun getRssiInfo(rssi: Int): SignalQualityInfo =
    when {
        rssi > -50 ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellular4Bar,
                color = ColorExcellent,
                textRes = R.string.signal_quality_rssi_excellent,
                textArgs = listOf(rssi),
                subtitleRes = R.string.signal_quality_rssi_sub_excellent,
            )
        rssi > -70 ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellular4Bar,
                color = ColorGood,
                textRes = R.string.signal_quality_rssi_good,
                textArgs = listOf(rssi),
                subtitleRes = R.string.signal_quality_rssi_sub_good,
            )
        rssi > -85 ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularAlt,
                color = ColorFair,
                textRes = R.string.signal_quality_rssi_fair,
                textArgs = listOf(rssi),
                subtitleRes = R.string.signal_quality_rssi_sub_fair,
            )
        rssi > -100 ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularAlt,
                color = ColorPoor,
                textRes = R.string.signal_quality_rssi_weak,
                textArgs = listOf(rssi),
                subtitleRes = R.string.signal_quality_rssi_sub_weak,
            )
        else ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularConnectedNoInternet0Bar,
                color = ColorVeryPoor,
                textRes = R.string.signal_quality_rssi_very_weak,
                textArgs = listOf(rssi),
                subtitleRes = R.string.signal_quality_rssi_sub_very_weak,
            )
    }

/**
 * Get signal quality (SNR) display information.
 *
 * SNR thresholds are based on typical LoRa radio characteristics:
 * - Excellent: > 10 dB (clear signal, very high reliability)
 * - Good: 5 to 10 dB (reliable communication)
 * - Fair: 0 to 5 dB (usable but may have some noise)
 * - Poor: -5 to 0 dB (noisy, may need retries)
 * - Very Poor: < -5 dB (very noisy, at the edge of decodability)
 *
 * @param snr SNR value in dB (typically -20 to +20)
 * @return SignalQualityInfo with appropriate icon, color, and text
 */
fun getSnrInfo(snr: Float): SignalQualityInfo {
    val snrStr = String.format(Locale.US, "%.1f", snr)
    return when {
        snr > 10f ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellular4Bar,
                color = ColorExcellent,
                textRes = R.string.signal_quality_snr_excellent,
                textArgs = listOf(snrStr),
                subtitleRes = R.string.signal_quality_snr_sub_excellent,
            )
        snr > 5f ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellular4Bar,
                color = ColorGood,
                textRes = R.string.signal_quality_snr_good,
                textArgs = listOf(snrStr),
                subtitleRes = R.string.signal_quality_snr_sub_good,
            )
        snr > 0f ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularAlt,
                color = ColorFair,
                textRes = R.string.signal_quality_snr_fair,
                textArgs = listOf(snrStr),
                subtitleRes = R.string.signal_quality_snr_sub_fair,
            )
        snr > -5f ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularAlt,
                color = ColorPoor,
                textRes = R.string.signal_quality_snr_poor,
                textArgs = listOf(snrStr),
                subtitleRes = R.string.signal_quality_snr_sub_poor,
            )
        else ->
            SignalQualityInfo(
                icon = Icons.Default.SignalCellularConnectedNoInternet0Bar,
                color = ColorVeryPoor,
                textRes = R.string.signal_quality_snr_very_poor,
                textArgs = listOf(snrStr),
                subtitleRes = R.string.signal_quality_snr_sub_very_poor,
            )
    }
}
