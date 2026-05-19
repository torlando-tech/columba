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
    // Identity create / import result keys. Names must match what
    // IdentityManagerViewModel reads from the reconstructed Map (see
    // IdentityManagerViewModel.kt:96-103) — previously the bundle was
    // using "identity_hash_hex" while the VM was reading "identity_hash",
    // making every create/import path silently fail with the error only
    // visible in the in-dialog IdentityManagerUiState. Single source of
    // truth: kotlin backend's NativeRnsBackendImpl.buildIdentityResult.
    const val IDENTITY_HASH = "identity_hash"
    const val DESTINATION_HASH = "destination_hash"
    const val FILE_PATH = "file_path"
    const val PUBLIC_KEY = "public_key"
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
