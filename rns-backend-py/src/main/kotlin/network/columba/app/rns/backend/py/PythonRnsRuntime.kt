package network.columba.app.rns.backend.py

import android.content.Context
import android.util.Log
import network.columba.app.rns.api.util.StampGenerator
import network.columba.app.rns.api.util.toHex
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.model.ReticulumConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime state holder for the Python flavor.
 *
 * Owns the Chaquopy `Python` interpreter handle, the cached upstream module
 * objects (`RNS`, `LXMF`, `event_bridge`), the live `Reticulum` / `LXMRouter`
 * instances after [start], and the registries that map Columba's stable
 * hash/handle keys back to the live upstream PyObject references (an
 * `RNS.Identity` / `RNS.Destination` / `RNS.Link` can't cross the AIDL seam,
 * so the seam carries the hash/handle and this runtime resolves it).
 *
 * The six `PythonRns*` sub-impls hold a shared instance of this and call
 * upstream RNS/LXMF directly through these PyObjects — there is no Python
 * facade. See the module CLAUDE.md.
 *
 * Threading: every method here is expected to be called from
 * `Dispatchers.IO` (the sub-impls wrap their calls in [pyResult] / [pyCall]).
 * The registries are [ConcurrentHashMap] because event-bridge callbacks fire
 * on RNS internal threads.
 */
class PythonRnsRuntime(
    private val appContext: Context,
    private val stampGenerator: StampGenerator = StampGenerator(),
) {
    private companion object {
        const val TAG = "PythonRnsRuntime"
    }

    /** Chaquopy interpreter. Starting it is idempotent + guarded by [ensureStarted]. */
    val python: Python by lazy {
        ensureStarted()
        Python.getInstance()
    }

    /** Cached upstream `RNS` package object. */
    val rnsModule: PyObject by lazy { python.getModule("RNS") }

    /** Cached upstream `LXMF` package object. */
    val lxmfModule: PyObject by lazy { python.getModule("LXMF") }

    /** Cached Columba-authored `event_bridge` module — the one Python file with logic. */
    val eventBridge: PyObject by lazy { python.getModule("event_bridge") }

    /** Live `RNS.Reticulum()` instance after [start]; null before/after. */
    @Volatile
    var reticulumInstance: PyObject? = null
        private set

    /** Live `LXMF.LXMRouter(...)` instance after [start]; null before/after. */
    @Volatile
    var lxmRouter: PyObject? = null
        private set

    /** The local delivery `RNS.Identity` (from [ReticulumConfig.deliveryIdentityKey]). */
    @Volatile
    var localIdentity: PyObject? = null
        private set

    /** The local LXMF delivery `RNS.Destination` registered on [lxmRouter]. */
    @Volatile
    var localDestination: PyObject? = null
        private set

    /** Absolute path of the RNS config dir written by [start]; null before/after. */
    @Volatile
    var storagePath: String? = null
        private set

    /** hex identity hash -> live `RNS.Identity`. Seeded by restore + announce events. */
    val identities = ConcurrentHashMap<String, PyObject>()

    /** hex destination hash -> live `RNS.Destination`. */
    val destinations = ConcurrentHashMap<String, PyObject>()

    /** opaque handle id -> live `RNS.Link`. Keyed to mirror `:rns-ipc`'s HandleRegistry. */
    val links = ConcurrentHashMap<Long, PyObject>()

    private val running = AtomicBoolean(false)

    /** Guards [applyAndroidEnvPatches] so it runs exactly once per process. */
    private val envPatched = AtomicBoolean(false)

    /** True once [start] has completed and the stack is live. */
    val isRunning: Boolean get() = running.get()

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            Log.i(TAG, "Starting Chaquopy Python interpreter")
            Python.start(AndroidPlatform(appContext))
        }
        applyAndroidEnvPatches()
    }

    /**
     * Neutralise upstream-RNS behaviour that breaks under Chaquopy *before* the
     * first `RNS.Reticulum()` is constructed. `Reticulum.__init__` ends by
     * registering SIGINT/SIGTERM handlers via `signal.signal()`, which raises
     * `ValueError` off Python's main thread — and every PyObject call here runs
     * on `Dispatchers.IO`, so without this `__init__` aborts after Transport +
     * interfaces are up but before it returns. Idempotent; the Python side also
     * guards against a double-apply.
     */
    private fun applyAndroidEnvPatches() {
        if (envPatched.compareAndSet(false, true)) {
            Python.getInstance().getModule("event_bridge")
                .callAttr("apply_android_env_patches")
            Log.i(TAG, "Applied Android/Chaquopy env patches")
        }
    }

    /**
     * Bring the Python RNS/LXMF stack up: write the RNS `config` file from
     * [config], construct `Reticulum(configdir)`, load the delivery identity,
     * construct the `LXMRouter`, and register the delivery destination.
     *
     * [wireEventBridge] must be called afterwards to attach the Kotlin event
     * sinks.
     */
    @Synchronized
    fun start(config: ReticulumConfig) {
        if (running.get()) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        ensureStarted()

        val configDir = File(config.storagePath, "reticulum").apply { mkdirs() }
        File(configDir, "config").writeText(RnsConfigFile.build(config))
        storagePath = configDir.absolutePath
        Log.i(TAG, "Wrote RNS config to ${configDir.absolutePath}/config")

        // Construct the upstream Reticulum instance. RNS.Reticulum is a process
        // singleton — stop() must fully tear it down before a restart.
        reticulumInstance = rnsModule.callAttr("Reticulum", configDir.absolutePath)

        // Delivery identity. The 64-byte private key is held in memory only;
        // RNS.Identity.from_bytes() reconstructs the keypair.
        val identityClass = rnsModule["Identity"]
            ?: error("RNS.Identity not resolvable")
        val identity =
            config.deliveryIdentityKey?.let { key ->
                identityClass.callAttr("from_bytes", key.toPyBytes())
            } ?: config.identityFilePath?.let { path ->
                identityClass.callAttr("from_file", path)
            } ?: identityClass.call() // fresh keys — Python `RNS.Identity()`; caller persists
        localIdentity = identity
        // RNS.Identity.hash is an attribute. Cache the live identity under its
        // hex hash so recallIdentity / sends can resolve it without a re-derive.
        identity["hash"]?.let { identities[it.toJava(ByteArray::class.java).toHex()] = identity }

        // LXMF router + delivery destination.
        val lxmfStorage = File(config.storagePath, "lxmf").apply { mkdirs() }
        val router = lxmfModule.callAttr("LXMRouter", identity, lxmfStorage.absolutePath)
        lxmRouter = router
        localDestination = router.callAttr(
            "register_delivery_identity",
            identity,
            config.displayName ?: "",
        )

        // Bypass upstream LXMF's multiprocessing-based stamp generation,
        // which hangs on Android (Chaquopy lacks `sem_open` and the
        // platform aggressively kills idle helper processes). The
        // torlando-tech LXMF fork exposes `LXStamper.set_external_generator`
        // for exactly this case — we hand it a Kotlin coroutine-based
        // generator wrapped in a BiFunction (Chaquopy's `get_sam` rejects
        // bound method references because they implement multiple
        // functional interfaces; BiFunction is a single, unambiguous SAM).
        // Ported from release/v0.10.x's `PythonWrapperManager.setStampGeneratorCallback`.
        registerExternalStampGenerator()

        running.set(true)
        Log.i(TAG, "Python RNS/LXMF stack started")
    }

    /**
     * Register the native Kotlin [StampGenerator] as upstream LXMF's
     * external stamp generator.
     *
     * Called from [start] once the `LXMF` module is loaded. The Python
     * side stores the callback and invokes it from `LXStamper.generate_stamp`
     * (torlando-tech fork; see `LXMF/LXStamper.py:109`). Best-effort —
     * registration failure logs a warning and falls back to upstream's
     * default generator, which works on desktop platforms but hangs on
     * Android.
     */
    private fun registerExternalStampGenerator() {
        try {
            val lxStamper = lxmfModule["LXStamper"]
                ?: run {
                    Log.w(
                        TAG,
                        "LXMF.LXStamper not resolvable — external stamp " +
                            "generator NOT registered. Stamps will use " +
                            "upstream Python multiprocessing, which hangs " +
                            "on Android.",
                    )
                    return
                }

            // BiFunction<PyObject, PyObject, PyObject> — single SAM so
            // Chaquopy's `get_sam` doesn't trip on "multiple functional
            // interfaces" (Kotlin's `(PyObject, PyObject) -> PyObject`
            // also implements Function2 + KCallable + KFunction).
            //
            // Both params are PyObject (not Kotlin types) on purpose:
            // Chaquopy's auto-conversion at SAM invocation time erases
            // the generic type parameters at runtime, so a BiFunction
            // declared as `<ByteArray, Int, PyObject>` actually receives
            // `Object[]` (boxed bytes) instead of `byte[]` when Python
            // hands in a `bytes` arg, causing
            // `ClassCastException: Object[] cannot be cast to byte[]`
            // inside the synthetic `apply` wrapper. Receiving PyObject
            // and converting via `.toJava(...)` inside the lambda
            // avoids the trap.
            val callback =
                java.util.function.BiFunction<PyObject, PyObject, PyObject> { workblockPy, stampCostPy ->
                    val workblock = workblockPy.toJava(ByteArray::class.java)
                    val stampCost = stampCostPy.toJava(Int::class.java)
                    generateStampForPython(workblock, stampCost)
                }
            lxStamper.callAttr("set_external_generator", callback)
            Log.i(TAG, "Native Kotlin stamp generator registered with LXMF.LXStamper")
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to register external stamp generator with LXMF.LXStamper: ${e.message}",
                e,
            )
        }
    }

    /**
     * Synchronous adapter Python calls when it needs a stamp.
     *
     * Python's `LXStamper.generate_stamp` invokes
     * `external_generator(workblock, stamp_cost)` and expects a 2-tuple
     * `(stamp_bytes, rounds)` back. We bridge that to the coroutine-based
     * [StampGenerator.generateStamp] via `runBlocking` — the Python call
     * site runs on an LXMF-internal thread and is already blocking on
     * the result, so this doesn't deadlock the UI.
     *
     * Returns a Python `list` rather than a Kotlin `Pair` because
     * Chaquopy serializes the Python tuple back through the `list`
     * builtin most cleanly. The bytes are converted via Python's
     * `bytes()` builtin so they expose the buffer protocol that
     * `LXStamper` then concatenates with the workblock.
     */
    internal fun generateStampForPython(
        workblock: ByteArray,
        stampCost: Int,
    ): PyObject {
        Log.d(
            TAG,
            "External stamp generator invoked: cost=$stampCost, " +
                "workblock=${workblock.size} bytes",
        )

        val result =
            runBlocking(Dispatchers.Default) {
                stampGenerator.generateStamp(workblock, stampCost)
            }

        Log.d(TAG, "Stamp generated: value=${result.value}, rounds=${result.rounds}")

        val py = Python.getInstance()
        val builtins = py.builtins
        val stamp = result.stamp ?: ByteArray(0)
        val pyBytes = builtins.callAttr("bytes", stamp)
        val pyList = builtins.callAttr("list")
        pyList.callAttr("append", pyBytes)
        pyList.callAttr("append", result.rounds)
        return pyList
    }

    /**
     * Attach the Kotlin event sinks to upstream RNS/LXMF via `event_bridge.py`.
     * Call once after [start]. The five callbacks are supplied by
     * `PythonEventBridge`.
     */
    fun wireEventBridge(
        onAnnounce: PyEventCallback,
        onPacket: PyEventCallback,
        onLinkEvent: PyEventCallback,
        onLxmfDelivery: PyEventCallback,
        onLxmfFailure: PyEventCallback,
    ) {
        eventBridge.callAttr(
            "register_callbacks",
            rnsModule["Transport"],
            lxmRouter,
            onAnnounce,
            onPacket,
            onLinkEvent,
            onLxmfDelivery,
            onLxmfFailure,
        )
        Log.i(TAG, "Event bridge wired")
    }

    /**
     * Tear the stack down. Best-effort — RNS 1.1.x has no clean per-instance
     * shutdown, so this deregisters the event bridge, asks RNS to run its exit
     * handler, and drops every cached reference. The "Apply & Restart" flow
     * (Phase B verification) exercises start->stop->start; restart correctness
     * is on-device integration work.
     */
    @Synchronized
    fun stop() {
        if (!running.get()) return
        runCatching { eventBridge.callAttr("deregister_callbacks") }
            .onFailure { Log.w(TAG, "event_bridge deregister failed", it) }
        runCatching {
            // RNS.Reticulum.exit_handler() flushes path tables + closes
            // interfaces without the os._exit() that RNS.exit() would do.
            rnsModule["Reticulum"]?.callAttr("exit_handler")
        }.onFailure { Log.w(TAG, "Reticulum exit_handler failed", it) }
        runCatching {
            // exit_handler() detaches interfaces + persists path data but does
            // NOT clear RNS's process-global singleton / Transport-registry
            // state — a fresh start() would then hit OSError("Attempt to
            // reinitialise Reticulum") and KeyError("...already registered
            // destination"). This clears it (ported from release/v0.10.x's
            // reticulum_wrapper.shutdown()). Separate runCatching so it runs
            // even if exit_handler() above threw.
            eventBridge.callAttr("reset_reticulum_for_restart")
        }.onFailure { Log.w(TAG, "Reticulum state reset for restart failed", it) }

        identities.clear()
        destinations.clear()
        links.clear()
        localDestination = null
        localIdentity = null
        storagePath = null
        lxmRouter = null
        reticulumInstance = null
        running.set(false)
        Log.i(TAG, "Python RNS/LXMF stack stopped")
    }

    /** Resolve a destination hex hash to its live `RNS.Destination`, or throw [identityNotFound]. */
    fun requireDestination(hexHash: String): PyObject =
        destinations[hexHash] ?: featureUnsupportedDestination(hexHash)

    private fun featureUnsupportedDestination(hexHash: String): Nothing =
        throw network.columba.app.rns.api.RnsException(
            network.columba.app.rns.api.RnsError.IdentityNotFound(hexHash),
        )

    /** Throw [network.columba.app.rns.api.RnsError.BackendNotReady] if [start] hasn't run. */
    fun requireRunning() {
        if (!running.get()) {
            throw network.columba.app.rns.api.RnsException(
                network.columba.app.rns.api.RnsError.BackendNotReady,
            )
        }
    }
}
