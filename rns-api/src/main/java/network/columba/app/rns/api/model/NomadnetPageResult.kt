package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result of a NomadNet page request.
 *
 * Returned by `RnsNomadnet.requestNomadnetPage`. For `type = "page"` the
 * `content` carries the rendered/raw micron text; for `type = "file"` the
 * caller receives a downloaded file at `filePath` (with `fileName` and
 * `fileSize` populated for UI presentation) and `content` is empty.
 *
 * @param content Page micron content, or empty for file responses.
 * @param path The path on the destination that was requested (e.g. `/page/index.mu`).
 * @param type Response kind — `"page"` (default) or `"file"`.
 * @param filePath Absolute path to the downloaded file when `type == "file"`.
 * @param fileName Display name of the downloaded file when `type == "file"`.
 * @param fileSize Size in bytes of the downloaded file when `type == "file"`.
 */
@Parcelize
data class NomadnetPageResult(
    val content: String,
    val path: String,
    val type: String = "page",
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
) : Parcelable
