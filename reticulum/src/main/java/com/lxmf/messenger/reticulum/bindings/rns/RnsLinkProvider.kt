package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Factory for creating [RnsLink] instances.
 *
 * Mirrors reticulum-kt: `Link.create(destination, callbacks)`.
 */
interface RnsLinkProvider {
    fun create(
        destination: RnsDestination,
        establishedCallback: ((RnsLink) -> Unit)? = null,
        closedCallback: ((RnsLink) -> Unit)? = null,
    ): RnsLink
}
