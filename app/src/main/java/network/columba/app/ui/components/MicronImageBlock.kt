package network.columba.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import network.columba.app.micron.MicronElement
import network.columba.app.nomadnet.PageImageState
import network.columba.app.nomadnet.PageImageStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders one Micron `Image` element, upstream-parity chrome rules (Torlando
 * 2026-09-07):
 *
 * - LOADED: the image, raw and inline. No border, no card, no scrim — the
 *   terminal draws graphics with no chrome and neither do we. Tap does
 *   nothing; long-press is the only affordance (M3 modal bottom sheet:
 *   Save to gallery / Reload / Copy link).
 * - Everything else (placeholder, loading, denied, failed) is *text in place
 *   of the image* — a subtle dashed-outline low-emphasis box, mirroring the
 *   terminal's one-line placeholder, not a Material component.
 *
 * Sizing (locked decision: fixed constant, clamp to viewport):
 * `w=40` -> 40 * 8.dp, `h=10` -> 10 * 16.dp (2:1 terminal cell aspect),
 * `50%` -> fraction of available width, `n` -> intrinsic size clamped to
 * width, none -> full width with height derived from the image aspect.
 */

private const val DP_PER_COLUMN = 8f
private const val DP_PER_ROW = 16f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicronImageBlock(
    element: MicronElement.Image,
    state: PageImageState?,
    maxBlockSize: Dp = Dp.Unspecified,
    indentLevel: Int = 0,
    onImageTapToLoad: () -> Unit = {},
    onImageReload: () -> Unit = {},
    onCopyLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val indent = (indentLevel * 8).dp
    val effectiveState = state ?: PageImageState()

    // Long-press action sheet (M3: non-destructive item actions -> modal
    // bottom sheet, the Google Photos idiom).
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val blockModifier =
        modifier
            .padding(start = indent, top = 6.dp, bottom = 6.dp)
            .let { m ->
                if (maxBlockSize != Dp.Unspecified) {
                    m.graphicsLayer { clip = false }
                } else {
                    m
                }
            }

    when (effectiveState.status) {
        PageImageStatus.LOADED -> {
            val file = effectiveState.file
            if (file != null) {
                val targetWidth = resolveImageWidth(element.width, Dp.Unspecified)
                val targetHeight = resolveImageWidth(element.height, Dp.Unspecified)
                val painter =
                    rememberAsyncImagePainter(
                        ImageRequest.Builder(context)
                            .data(file)
                            .build(),
                    )
                Row(
                    modifier =
                        blockModifier
                            .fillMaxWidth()
                            .testTag("micron-image-loaded"),
                    horizontalArrangement = horizontalArrangementFor(element.align),
                ) {
                    val imageModifier =
                        Modifier
                            .combinedClickable(
                                onClick = {}, // tap-inert, upstream parity
                                onLongClick = { showSheet = true },
                            )
                            .let { m ->
                                val w = targetWidth
                                if (w != null) m.width(w.coerceAtLeast(0.dp)) else m.fillMaxWidth()
                            }
                            .let { m ->
                                val h = targetHeight
                                if (h != null) m.height(h) else m
                            }
                    Image(
                        painter = painter,
                        contentDescription = element.alt.ifBlank { "Image" },
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier,
                    )
                }
            } else {
                ImagePlaceholder(
                    element = element,
                    statusLine = null,
                    showTapAffordance = false,
                    onClick = onImageTapToLoad,
                    modifier = blockModifier,
                )
            }
        }

        PageImageStatus.LOADING -> {
            ImagePlaceholder(
                element = element,
                statusLine = loadingStatusLine(effectiveState),
                showTapAffordance = false,
                onClick = {},
                modifier = blockModifier.testTag("micron-image-loading"),
            )
        }

        PageImageStatus.DENIED -> {
            ImagePlaceholder(
                element = element,
                statusLine = "Access denied by node",
                showTapAffordance = false,
                onClick = {},
                icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                modifier = blockModifier.testTag("micron-image-denied"),
            )
        }

        PageImageStatus.FAILED -> {
            ImagePlaceholder(
                element = element,
                statusLine = effectiveState.error ?: "Error loading image",
                showTapAffordance = true,
                onClick = onImageReload,
                modifier = blockModifier.testTag("micron-image-failed"),
            )
        }

        PageImageStatus.PLACEHOLDER -> {
            ImagePlaceholder(
                element = element,
                statusLine = null,
                showTapAffordance = true,
                onClick = onImageTapToLoad,
                modifier = blockModifier.testTag("micron-image-placeholder"),
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            modifier = Modifier.testTag("micron-image-sheet"),
        ) {
            Text(
                text = element.alt.ifBlank { "Image" },
                style = typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Outlined.SaveAlt, "Save to gallery") {
                effectiveState.file?.let { f ->
                    saveImageToGallery(context, f.absolutePath, f.name)
                }
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            }
            SheetAction(Icons.Outlined.Refresh, "Reload image") {
                onImageReload()
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            }
            SheetAction(Icons.Outlined.Image, "Copy image link") {
                onCopyLink(element.url)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = typography.bodyLarge, color = colorScheme.onSurface)
    }
}

@Composable
private fun ImagePlaceholder(
    element: MicronElement.Image,
    statusLine: String?,
    showTapAffordance: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val dashColor = colorScheme.outlineVariant
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        color = dashColor,
                        style =
                            Stroke(
                                width = 1.dp.toPx(),
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                                        0f,
                                    ),
                            ),
                    )
                }
                .combinedClickable(
                    onClick = onClick,
                    enabled = showTapAffordance,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
                ?: Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                )
            Spacer(Modifier.width(8.dp))
            Text(
                text = element.alt.ifBlank { "image" },
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (statusLine != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusLine,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        if (showTapAffordance) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to load image",
                style = typography.labelSmall,
                color = colorScheme.primary,
            )
        }
    }
}

@Composable
private fun loadingStatusLine(state: PageImageState): String {
    val pct = (state.progress * 100).roundToInt()
    val sizePart =
        if (state.totalBytes > 0) {
            " (${formatBytes(state.receivedBytes)} of ${formatBytes(state.totalBytes)})"
        } else {
            ""
        }
    val speedPart = state.speedBps?.let { " - ${formatSpeed(it)}" } ?: ""
    return "$pct%$sizePart$speedPart".ifBlank { "Loading..." }
}

/** Terminal-cell size spec -> Dp (locked: 1 col = 8.dp, 1 row = 16.dp). */
@Composable
internal fun resolveImageWidth(
    spec: String?,
    @Suppress("UNUSED_PARAMETER") fallback: Dp,
): Dp? {
    if (spec == null) return null
    if (spec == "n") return null // native: intrinsic size, clamped by parent
    val density = LocalDensity.current
    return when {
        spec.endsWith("%") -> {
            val pct = spec.removeSuffix("%").toFloatOrNull() ?: return null
            with(density) { (max(0f, min(100f, pct)) * 4.11f).toDp() }
        }
        spec.toFloatOrNull() != null -> with(density) { (spec.toFloat() * DP_PER_COLUMN).toDp() }
        else -> null
    }
}

private fun horizontalArrangementFor(align: String?): Arrangement.Horizontal =
    when (align) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

internal fun formatSpeed(bps: Long): String =
    when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.1f kbps".format(bps / 1000.0)
        else -> "$bps bps"
    }

/**
 * Insert a media file into the system gallery via MediaStore (user-initiated,
 * so no storage permission needed on API 29+).
 */
fun saveImageToGallery(
    context: android.content.Context,
    filePath: String,
    displayName: String,
) {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/Columba",
            )
        }
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { out ->
            java.io.File(filePath).inputStream().use { it.copyTo(out) }
        }
        android.widget.Toast
            .makeText(context, "Saved to gallery", android.widget.Toast.LENGTH_SHORT)
            .show()
    } else {
        android.widget.Toast
            .makeText(context, "Could not save image", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
