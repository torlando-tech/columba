package network.columba.app.nomadnet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import network.columba.app.MainActivity

/**
 * Publishes a [NomadNetShortcutSpec] as a launcher home-screen shortcut.
 *
 * The shortcut carries the same `nomadnetwork://` deep link an in-app page
 * share does, and targets [MainActivity] explicitly. Because MainActivity is
 * `singleTask`, tapping the icon either cold-starts the app or arrives at
 * `onNewIntent`, and both paths run
 * [network.columba.app.MainActivityIntentHandler] -> `PendingNavigation.NomadNetBrowser`,
 * which switches to the NomadNet tab with the page loaded. No shortcut-specific
 * intent handling exists anywhere else in the app.
 */
object NomadNetShortcutPinner {
    /** 108dp-ish at xxxhdpi; launchers downscale, so render once at a fixed size. */
    private const val ICON_SIZE_PX = 192

    private const val GRID = 5

    /** Left half of the 5-wide identicon grid is drawn, then mirrored. */
    private const val GRID_HALF_COLUMNS = 3

    /** Bytes above this value fill a cell (matches the in-app identicon). */
    private const val FILL_THRESHOLD = 127

    /** Luminance above which the dot color flips to dark for contrast. */
    private const val LIGHT_BACKGROUND_LUMINANCE = 0.55

    /**
     * Builds the shortcut without publishing it. Split from [requestPin] so the
     * intent wiring (the part that breaks silently if the scheme or component
     * changes) is unit-testable.
     */
    fun buildShortcutInfo(
        context: Context,
        spec: NomadNetShortcutSpec,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setIcon(nodeIcon(spec.nodeHash))
            .setIntent(shortcutIntent(context, spec.deepLink))
            .build()

    /**
     * The launch intent a launcher fires for this shortcut. Explicit component
     * (no intent-filter matching needed) + `NEW_TASK`, which `singleTask`
     * collapses onto the live task.
     */
    fun shortcutIntent(
        context: Context,
        deepLink: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Shows the launcher's "add to home screen" confirmation for [spec].
     *
     * @return false when the launcher has no pin-shortcut support (older
     * launchers, some OEM launchers); callers surface that instead of failing
     * silently.
     */
    fun requestPin(
        context: Context,
        spec: NomadNetShortcutSpec,
    ): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(context, spec), null)
    }

    /**
     * Per-node icon: the 5x5 symmetric identicon pattern the app already uses
     * for peers, rendered to a bitmap because launcher shortcuts need a drawable
     * per shortcut and a single shared app icon would make every NomadNet page
     * look identical on the home screen.
     */
    fun nodeIcon(nodeHash: String): IconCompat = IconCompat.createWithBitmap(identiconBitmap(nodeHash, ICON_SIZE_PX))

    /** Visible for tests: renders the identicon for [nodeHash] at [sizePx] square. */
    fun identiconBitmap(
        nodeHash: String,
        sizePx: Int,
    ): Bitmap {
        val hash = nodeHash.hexBytes()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = colorFrom(hash, 0)
        canvas.drawColor(background)
        val dotPaint =
            Paint().apply {
                isAntiAlias = true
                color =
                    if (luminance(background) > LIGHT_BACKGROUND_LUMINANCE) {
                        Color.BLACK
                    } else {
                        Color.WHITE
                    }
            }
        val cell = sizePx.toFloat() / GRID
        val radius = cell / 2.5f
        for (row in 0 until GRID) {
            for (col in 0 until GRID_HALF_COLUMNS) {
                val byteValue = hash[(row * GRID_HALF_COLUMNS + col) % hash.size].toInt() and 0xFF
                if (byteValue <= FILL_THRESHOLD) continue
                canvas.drawCircle((col + 0.5f) * cell, (row + 0.5f) * cell, radius, dotPaint)
                if (col < GRID_HALF_COLUMNS - 1) {
                    canvas.drawCircle((GRID - 1 - col + 0.5f) * cell, (row + 0.5f) * cell, radius, dotPaint)
                }
            }
        }
        return bitmap
    }

    /** RGB from the first three bytes of [hash] (wraps when short). */
    private fun colorFrom(
        hash: ByteArray,
        offset: Int,
    ): Int =
        Color.rgb(
            hash[offset % hash.size].toInt() and 0xFF,
            hash[(offset + 1) % hash.size].toInt() and 0xFF,
            hash[(offset + 2) % hash.size].toInt() and 0xFF,
        )

    private fun luminance(color: Int): Double = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

    /**
     * Bytes the 5x5 identicon is drawn from, for callers that render the node
     * with the shared [network.columba.app.ui.components.ProfileIcon] (which
     * takes a hash for its identicon fallback) instead of a launcher icon.
     */
    fun identiconBytes(nodeHash: String): ByteArray = nodeHash.hexBytes()

    /** Destination hashes are hex; anything else (test fixtures, odd formats) hashes to bytes via UTF-8. */
    private fun String.hexBytes(): ByteArray {
        if (length % 2 == 0 && all { it.isHexDigit() }) {
            val bytes = ByteArray(length / 2)
            for (i in bytes.indices) {
                bytes[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            // A few bytes are not enough for the grid lookup: pad deterministically.
            return if (bytes.size >= GRID_HALF_COLUMNS * GRID) bytes else padded(bytes)
        }
        return padded(toByteArray(Charsets.UTF_8))
    }

    private fun padded(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(GRID_HALF_COLUMNS * GRID) { (it * 37).toByte() }
        if (bytes.size >= GRID_HALF_COLUMNS * GRID) return bytes
        return ByteArray(GRID_HALF_COLUMNS * GRID) { bytes[it % bytes.size] }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
