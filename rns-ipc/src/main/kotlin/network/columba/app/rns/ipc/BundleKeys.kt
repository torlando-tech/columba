package network.columba.app.rns.ipc

/**
 * Bundle payload keys shared by `:rns-ipc`'s client and server adapters when
 * marshalling `IRnsResultCallback.onSuccess(Bundle)` payloads.
 *
 * The conventions are also documented in the per-sub-interface AIDL files
 * (see `IRnsCore.aidl`, `IRnsLxmf.aidl`, etc.) — this file is the single
 * source of truth callers use to read/write the keys.
 */
internal object BundleKeys {
    // RnsCore
    const val IDENTITY = "identity"
    const val KEY_DATA = "key_data"
    const val DISPLAY_NAME = "display_name"
    const val IDENTITY_HASH_HEX = "identity_hash_hex"
    const val DESTINATION = "destination"
    const val RECEIPT = "receipt"
    const val LINK = "link"
    const val PROBE = "probe"
    const val LINK_RESULT = "link_result"
    const val COUNT = "count"

    // RnsLxmf
    const val PROPAGATION_STATE = "state"

    // RnsTelephony
    const val CALL_STATE = "state"

    // RnsNomadnet
    const val PAGE = "page"

    // RnsTransportAdmin
    const val INTERFACES = "interfaces"
    const val HAS_STATS = "has_stats"
}
