package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject

/**
 * Two-arg sibling of [PyEventCallback] for RNS callbacks that take a
 * pair — currently only `Link.set_remote_identified_callback` (link,
 * identity). Lives here so Chaquopy's SAM-callable dispatcher invokes
 * `onEvent(PyObject, PyObject)` directly (typed-method path) rather
 * than collapsing args into a single `Object[]` (the trap that broke
 * the stamp-generator BiFunction; see PythonRnsRuntime kdoc).
 */
fun interface PyTwoArgCallback {
    fun onEvent(first: PyObject, second: PyObject)
}
