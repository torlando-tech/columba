package network.columba.app.rns.api

import kotlinx.coroutines.flow.StateFlow
import network.columba.app.rns.api.model.NomadnetPageResult

/**
 * NomadNet page browsing.
 *
 * Wraps the NomadNet `/page/` and `/file/` request flow so the UI can
 * show micron-rendered pages and download advertised files without
 * caring about the link/request choreography.
 *
 * Status & progress are exposed both as one-shot getters
 * ([getNomadnetRequestStatus], [getNomadnetDownloadProgress]) and as
 * observable [StateFlow]s for the busy-indicator.
 */
interface RnsNomadnet {
    /**
     * Request a NomadNet page or file from a destination.
     *
     * @param destinationHash 32-char hex destination hash of the NomadNet host.
     * @param path Path on the host (default `"/page/index.mu"`).
     * @param formDataJson Optional JSON-encoded form data for POST-style page requests.
     * @param timeoutSeconds Hard deadline for the round-trip.
     * @return Result containing [NomadnetPageResult] (page content or downloaded file metadata).
     */
    suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        formDataJson: String? = null,
        timeoutSeconds: Float = 45f,
    ): Result<NomadnetPageResult>

    /** Cancel an in-flight [requestNomadnetPage]. No-op if no request is active. */
    suspend fun cancelNomadnetPageRequest()

    /**
     * One-shot snapshot of the current NomadNet request status.
     * Mirrors [nomadnetRequestStatusFlow]'s most recent value.
     */
    suspend fun getNomadnetRequestStatus(): String

    /**
     * One-shot snapshot of the current NomadNet download progress
     * (0.0–1.0). Mirrors [nomadnetDownloadProgressFlow]'s most recent value.
     */
    suspend fun getNomadnetDownloadProgress(): Float

    /**
     * Establish an identified link to a NomadNet host (some hosts gate
     * pages on caller identification). Returns true if the host accepted
     * the identify proof.
     */
    suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean>

    /**
     * Observable status of the current NomadNet request — `"idle"`,
     * `"requesting"`, `"receiving"`, `"complete"`, etc. Drives the UI's
     * busy indicator state.
     */
    val nomadnetRequestStatusFlow: StateFlow<String>

    /**
     * Observable download progress of the current NomadNet file transfer
     * (0.0–1.0). Stays at 0 for page requests that don't carry attachments.
     */
    val nomadnetDownloadProgressFlow: StateFlow<Float>
}
