package network.columba.app.rns.backend.py

import androidx.annotation.Keep
import com.chaquo.python.PyObject

/** Two-arg sibling of [PyEventCallback] — currently for `Link.set_remote_identified_callback`. */
@Keep // event_bridge.py calls `callback.onEvent(link, identity)` by name via Chaquopy — R8 must not rename the SAM
fun interface PyTwoArgCallback {
    fun onEvent(first: PyObject, second: PyObject)
}
