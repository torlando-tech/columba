package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.util.hexToBytes

/**
 * Chaquopy interop helpers shared by every `PythonRns*` sub-impl.
 *
 * The two load-bearing concerns:
 *  - **The ArrayList footgun** ([toPyList]). Passing a raw Kotlin/Java
 *    `List` into a `callAttr` list parameter makes Python see
 *    `'ArrayList' object is not iterable`. Always convert first.
 *  - **Off-thread + typed errors** ([pyResult]). Every PyObject call holds
 *    the GIL; it must run on `Dispatchers.IO`, never the caller's thread.
 *    Chaquopy's `PyException` can't cross the AIDL seam, so failures are
 *    translated to [RnsException] / [RnsError] here.
 */

/**
 * Chaquopy footgun guard: Kotlin/Java `List` -> real Python `list`.
 *
 * Also converts `ByteArray` *elements* to Python `bytes` ([toPyBytes]): left
 * raw, a `ByteArray` inside the list crosses JNI as a `jarray('B')`, which
 * upstream `umsgpack` can't serialise — LXMF's `LXMessage.pack()` then dies
 * with `UnsupportedTypeException: ... java.jarray('B')` when it packs a fields
 * dict containing the list (this is what broke image / file-attachment sends).
 * Already-built [PyObject]s and every other element type pass through
 * unchanged.
 *
 * See the module CLAUDE.md. Use at *every* `callAttr` site that takes a
 * list parameter.
 */
fun List<*>.toPyList(): PyObject {
    val converted = this.map { if (it is ByteArray) it.toPyBytes() else it }
    return Python.getInstance().builtins.callAttr("list", converted.toTypedArray())
}

/** Kotlin `ByteArray` -> Python `bytes`. Chaquopy hands a `ByteArray` to a
 *  `callAttr` arg as a `jarray('B')`; some upstream code paths want a real
 *  `bytes`. Cheap to be explicit. */
fun ByteArray.toPyBytes(): PyObject =
    Python.getInstance().builtins.callAttr("bytes", this)

// --- Python dict accessors -------------------------------------------------
// `event_bridge.py` payloads (and many upstream return values) are Python
// dicts. `dict.get(k)` returns Python None -> Kotlin null for a missing key,
// so all of these are null-safe.

fun PyObject.dictGet(key: String): PyObject? = callAttr("get", key)

fun PyObject.dictStr(key: String): String? = dictGet(key)?.toString()

fun PyObject.dictInt(key: String): Int? = dictGet(key)?.toJava(Int::class.javaObjectType)

fun PyObject.dictLong(key: String): Long? = dictGet(key)?.toJava(Long::class.javaObjectType)

fun PyObject.dictDouble(key: String): Double? = dictGet(key)?.toJava(Double::class.javaObjectType)

fun PyObject.dictBool(key: String): Boolean =
    dictGet(key)?.toJava(Boolean::class.javaObjectType) ?: false

/** Reads a hex-string dict entry (event_bridge.py encodes all bytes as hex). */
fun PyObject.dictBytes(key: String): ByteArray? =
    dictStr(key)?.takeIf { it.isNotEmpty() }?.hexToBytes()

// --- Off-thread call + typed-error translation -----------------------------

/**
 * Run a block of PyObject calls on [Dispatchers.IO] and fold the outcome into
 * a [Result]. A thrown [RnsException] is preserved verbatim; anything else
 * (including Chaquopy's `PyException`) is wrapped in [RnsError.Generic] with
 * the message + stack trace text so the failure survives the AIDL seam.
 *
 * Use this for every `suspend fun ...: Result<T>` on the sub-interfaces.
 */
internal suspend fun <T> pyResult(block: suspend () -> T): Result<T> =
    withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: RnsException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(
                RnsException(
                    RnsError.Generic(
                        message = e.message ?: e.toString(),
                        stackTraceText = e.stackTraceToString(),
                    ),
                ),
            )
        }
    }

/**
 * Run a block of PyObject calls on [Dispatchers.IO] for a non-`Result`
 * suspend method, translating Chaquopy `PyException` into [RnsException] so
 * it still crosses the AIDL seam as a typed error.
 */
internal suspend fun <T> pyCall(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: RnsException) {
            throw e
        } catch (e: Throwable) {
            throw RnsException(
                RnsError.Generic(
                    message = e.message ?: e.toString(),
                    stackTraceText = e.stackTraceToString(),
                ),
            )
        }
    }

/** Throw the typed "feature not on this backend" error. */
internal fun featureUnsupported(feature: String): Nothing =
    throw RnsException(RnsError.FeatureUnsupported(feature))
