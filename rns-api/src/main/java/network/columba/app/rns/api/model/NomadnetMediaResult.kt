package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result of a NomadNet `/media/` request (page image fetch).
 *
 * Returned by `RnsNomadnet.requestNomadnetMedia`. The body is written to
 * [filePath] (a fresh file in the backend's cache dir, name derived from the
 * requested path, uniquified so concurrent fetches never clobber each other);
 * the caller (image cache layer) is expected to move or copy it into its own
 * URL-keyed cache and manage its lifecycle from there.
 *
 * A server-side denial (`.allowed` gating) is surfaced as a failure with
 * [network.columba.app.rns.api.RnsError.NomadnetRequestDenied], not as a
 * result — matching upstream NomadNet's `False` deny signal (Node.py).
 *
 * @param filePath Absolute path to the downloaded media file.
 * @param fileName Display name of the media file (server-advertised name or
 *   basename of the requested path).
 * @param fileSize Size in bytes of the downloaded file.
 * @param path The `/media/...` path that was requested.
 */
@Parcelize
data class NomadnetMediaResult(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val path: String,
) : Parcelable
