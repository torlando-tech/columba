package tech.torlando.rns.stats.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.torlando.rns.stats.data.InterfaceHistoryPoint
import tech.torlando.rns.stats.data.SpeedSample
import tech.torlando.rns.stats.data.formatSpeed
import tech.torlando.rns.stats.data.toSpeedSamples

/**
 * A traffic speed chart showing RX and TX bytes/sec over time.
 * Ported from Carina's Canvas-based chart implementation.
 *
 * @param history List of timestamped byte counter snapshots
 * @param title Optional title displayed above the chart
 * @param rxColor Color for the RX (receive) line
 * @param txColor Color for the TX (transmit) line
 */
@Composable
fun TrafficSpeedChart(
    history: List<InterfaceHistoryPoint>,
    title: String = "Traffic Speed",
    rxColor: Color = MaterialTheme.colorScheme.primary,
    txColor: Color = MaterialTheme.colorScheme.tertiary,
    modifier: Modifier = Modifier,
) {
    val speeds = remember(history) { history.toSpeedSamples() }

    val maxSpeed = remember(speeds) {
        val m = speeds.maxOfOrNull { maxOf(it.rxBytesPerSec, it.txBytesPerSec) } ?: 0f
        if (m < 1f) 1024f else m * 1.1f
    }

    val animatedMax by animateFloatAsState(
        targetValue = maxSpeed,
        animationSpec = tween(600),
        label = "maxScale",
    )

    // Align animation by sample timestamp. Once the bounded history starts
    // rolling, retained samples move to a new list index but still represent
    // the same point in time and must not be interpolated with their neighbor.
    var startSpeeds by remember { mutableStateOf(emptyList<SpeedSample>()) }
    var endSpeeds by remember { mutableStateOf(emptyList<SpeedSample>()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(speeds) {
        startSpeeds = interpolateSpeedSamples(startSpeeds, endSpeeds, progress.value)
        endSpeeds = speeds
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }

    val displaySpeeds = interpolateSpeedSamples(startSpeeds, endSpeeds, progress.value)
    val displayRx = displaySpeeds.map { it.rxBytesPerSec }
    val displayTx = displaySpeeds.map { it.txBytesPerSec }

    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChartLegendItem(label = "RX", color = rxColor)
                Spacer(Modifier.width(16.dp))
                ChartLegendItem(label = "TX", color = txColor)
            }
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            ) {
                val leftPad = 50f
                val chartWidth = size.width - leftPad
                val chartHeight = size.height - 18.dp.toPx()

                // Y-axis labels
                drawText(
                    textMeasurer = textMeasurer,
                    text = formatSpeed(animatedMax),
                    topLeft = Offset(0f, 0f),
                    style = labelStyle,
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = "0",
                    topLeft = Offset(0f, chartHeight),
                    style = labelStyle,
                )

                // Baseline
                drawLine(
                    color = labelColor.copy(alpha = 0.2f),
                    start = Offset(leftPad, chartHeight),
                    end = Offset(size.width, chartHeight),
                    strokeWidth = 1f,
                )

                // Draw lines
                drawSpeedLine(displayRx, animatedMax, rxColor, leftPad, chartWidth, chartHeight)
                drawSpeedLine(displayTx, animatedMax, txColor, leftPad, chartWidth, chartHeight)
            }
        }
    }
}

@Composable
private fun ChartLegendItem(
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier =
                Modifier
                    .width(24.dp)
                    .height(2.dp)
                    .background(color),
        )
    }
}

private fun DrawScope.drawSpeedLine(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    leftPad: Float,
    chartWidth: Float,
    chartHeight: Float,
) {
    if (values.size < 2 || maxValue <= 0f) return

    val path = Path()
    val fillPath = Path()
    val step = chartWidth / (values.size - 1).coerceAtLeast(1)

    for (i in values.indices) {
        val x = leftPad + i * step
        val y = chartHeight - (values[i] / maxValue) * chartHeight

        if (i == 0) {
            path.moveTo(x, y)
            fillPath.moveTo(x, chartHeight)
            fillPath.lineTo(x, y)
        } else {
            path.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
    }

    fillPath.lineTo(leftPad + (values.size - 1) * step, chartHeight)
    fillPath.close()

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.2f), color.copy(alpha = 0.02f)),
        ),
    )

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

internal fun interpolateSpeedSamples(
    from: List<SpeedSample>,
    to: List<SpeedSample>,
    progress: Float,
): List<SpeedSample> {
    if (from.isEmpty()) return to
    if (to.isEmpty()) return from
    val fromByTimestamp = from.associateBy { it.timestamp }
    return to.map { target ->
        val source = fromByTimestamp[target.timestamp] ?: target
        target.copy(
            rxBytesPerSec = source.rxBytesPerSec + (target.rxBytesPerSec - source.rxBytesPerSec) * progress,
            txBytesPerSec = source.txBytesPerSec + (target.txBytesPerSec - source.txBytesPerSec) * progress,
        )
    }
}
