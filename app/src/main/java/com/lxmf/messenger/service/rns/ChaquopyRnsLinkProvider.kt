package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestination
import com.lxmf.messenger.reticulum.bindings.rns.RnsLink
import com.lxmf.messenger.reticulum.bindings.rns.RnsLinkProvider

/**
 * Chaquopy implementation of [RnsLinkProvider].
 * Calls `rns_api.RnsApi.create_link()` which returns a live Python Link.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyRnsLinkProvider(
    private val api: PyObject,
) : RnsLinkProvider {
    override fun create(
        destination: RnsDestination,
        establishedCallback: ((RnsLink) -> Unit)?,
        closedCallback: ((RnsLink) -> Unit)?,
    ): RnsLink {
        val pyDestination = (destination as ChaquopyRnsDestination).pyDestination
        // TODO: Bridge callbacks in Phase 4 — for now pass null
        val pyLink = api.callAttr("create_link", pyDestination)
        return ChaquopyRnsLink(pyLink, api)
    }
}
