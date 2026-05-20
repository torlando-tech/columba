package network.columba.app.rns.backend.py

import androidx.annotation.Keep
import com.chaquo.python.PyObject

/**
 * Single-method event sink that `event_bridge.py` invokes for each flattened
 * RNS/LXMF event.
 *
 * `event_bridge.py`'s `register_callbacks(...)` takes five of these (announce /
 * packet / link / lxmf-delivery / lxmf-failure). Python calls `onEvent(payload)`
 * where `payload` is a Python `dict` of JSON-primitive values — bytes are
 * hex-encoded strings so the Kotlin side never reasons about jarray vs bytes.
 *
 * Implemented by `PythonEventBridge` (Kotlin side), which translates the dict
 * into the `:rns-api` model types and publishes onto the same
 * `MutableSharedFlow`s the kotlin backend exposes.
 */
@Keep // event_bridge.py calls `callback.onEvent(payload)` by name via Chaquopy — R8 must not rename the SAM
fun interface PyEventCallback {
    /**
     * @param payload a Python `dict` (str -> primitive) built by
     *   `event_bridge.py`. Read entries with the `dict*` helpers in
     *   `PythonExt.kt`.
     */
    fun onEvent(payload: PyObject)
}
