package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject

/** Two-arg sibling of [PyEventCallback] — currently for `Link.set_remote_identified_callback`. */
fun interface PyTwoArgCallback {
    fun onEvent(first: PyObject, second: PyObject)
}
