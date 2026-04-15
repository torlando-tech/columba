package network.columba.app.reticulum.flasher

/**
 * Represents a firmware source for the RNode flasher.
 *
 * Each GitHub-backed source maps directly to a GitHub owner/repo pair.
 * Custom allows the user to supply a local ZIP file or a direct download URL.
 */
sealed class FirmwareSource(
    /** Used as the cache subdirectory name under `firmware/`. */
    val id: String,
    val displayName: String,
    /** GitHub owner, or null for Custom. */
    val owner: String?,
    /** GitHub repo name, or null for Custom. */
    val repo: String?,
) {
    object Official : FirmwareSource(
        id = "official",
        displayName = "RNode Official",
        owner = "markqvist",
        repo = "RNode_Firmware",
    )

    object MicroReticulum : FirmwareSource(
        id = "microreticulum",
        displayName = "microReticulum",
        owner = "attermann",
        repo = "microReticulum_Firmware",
    )

    object CommunityEdition : FirmwareSource(
        id = "ce",
        displayName = "RNode Community Edition",
        owner = "liberatedsystems",
        repo = "RNode_Firmware_CE",
    )

    object Custom : FirmwareSource(
        id = "custom",
        displayName = "Custom",
        owner = null,
        repo = null,
    )
}
