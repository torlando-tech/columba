package network.columba.app.proguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.columba.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies R8 did not strip or rename the Python<->Kotlin bridge surface in a
 * minified build.
 *
 * Chaquopy reaches these Kotlin classes/methods purely **by name** via
 * reflection from the Python interpreter. If R8 obfuscates or removes them the
 * app compiles and ships fine, then fails at runtime the moment Python tries to
 * call the bridge — exactly the regression that bit past releases. This test
 * performs the same by-name lookup against the installed (minified) target APK,
 * so a broken keep-rule fails CI instead of users' devices.
 *
 * Meaningful only on the `pythonBackend` flavor built with a minified build type
 * (run the dedicated CI job: `-PtestBuildType=releaseMinified`). On the kotlin
 * flavor it self-skips via [assumeTrue] — `PythonEventBridge` ships only in the
 * python flavor, and on a non-minified `debug` build the assertions are trivially
 * true.
 *
 * The defense itself is `@Keep` on each bridge class (see e.g.
 * [network.columba.app] bridge classes in `:rns-host` / `:rns-backend-py`).
 */
@RunWith(AndroidJUnit4::class)
class PythonKotlinBridgeR8Test {

    @Before
    fun onlyPythonFlavor() {
        assumeTrue(
            "Python<->Kotlin bridge only exists in the pythonBackend flavor",
            BuildConfig.RNS_IMPL == "python",
        )
    }

    private val targetClassLoader by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.classLoader
    }

    /**
     * Each @Keep'd bridge FQCN -> the method names Python resolves on *that class*
     * by name, harvested from the call sites in `rns-backend-py/src/main/python`
     * (e.g. `self.kotlin_bridge.connect(...)`, `self.usb_bridge.findDeviceByVidPid(...)`).
     *
     * The map is keyed per class on purpose: several names (`connect`, `disconnect`,
     * `read`, `isConnected`, ...) exist on more than one bridge, so checking against
     * the union of all classes' methods would let a name stripped from one bridge
     * pass as long as another bridge still has it — masking a real per-class break.
     *
     * The event path is reflected differently: Python (`event_bridge.py`) calls
     * `callback.onEvent(payload)` on the five `PyEventCallback` SAMs passed to
     * `register_callbacks`, so `PyEventCallback.onEvent` is the name that must
     * survive. `PythonEventBridge` itself is constructed Kotlin-side and its
     * `handle*` methods are called internally (by the SAMs), not by Python — so it
     * maps to an empty set (class survival only).
     */
    private val bridgeMethods: Map<String, Set<String>> = mapOf(
        "network.columba.app.rns.host.rnode.KotlinRNodeBridge" to setOf(
            "connect", "disconnect", "getConnectedDeviceName", "isConnected",
            "notifyOnlineStatusChanged", "read", "setOnConnectionStateChanged",
        ),
        "network.columba.app.rns.host.ble.bridge.KotlinBLEBridge" to setOf(
            "configurePower", "connect", "connectAsync", "disconnect", "disconnectAsync",
            "disconnectCentralAsync", "disconnectPeripheralAsync", "ensureAdvertising",
            "getPeerRssi", "requestIdentityResync", "sendAsync", "setIdentity",
            "setOnAddressChanged", "setOnConnected", "setOnDataReceived", "setOnDeviceDiscovered",
            "setOnDisconnected", "setOnDuplicateIdentityDetected", "setOnIdentityReceived",
            "setOnMtuNegotiated", "shouldConnect", "startAdvertisingAsync", "startAsync",
            "startScanningAsync", "stopAdvertisingAsync", "stopAsync", "stopScanningAsync",
        ),
        "network.columba.app.rns.host.usb.KotlinUSBBridge" to setOf(
            "connect", "disconnect", "findDeviceByVidPid", "isConnected", "notifyBluetoothPin", "read",
        ),
        "network.columba.app.rns.backend.py.PythonEventBridge" to emptySet(),
        // The SAM Python invokes for every announce/packet/link/LXMF event.
        "network.columba.app.rns.backend.py.PyEventCallback" to setOf("onEvent"),
    )

    @Test
    fun bridgeClassesAndMethodsSurviveR8() {
        bridgeMethods.forEach { (fqcn, expectedMethods) ->
            val cls = try {
                Class.forName(fqcn, false, targetClassLoader)
            } catch (e: ClassNotFoundException) {
                throw AssertionError(
                    "R8 stripped/renamed bridge class '$fqcn'. Annotate it with " +
                        "@androidx.annotation.Keep — Chaquopy resolves it by this exact name.",
                    e,
                )
            }

            // Assert per class (not across the union) so a name stripped from this
            // bridge isn't masked by the same name surviving on a different one.
            val declared = cls.declaredMethods.map { it.name }.toSet()
            val missing = expectedMethods - declared
            assertTrue(
                "R8 stripped/renamed Python-called methods on $fqcn: $missing. " +
                    "The class must be @Keep-annotated so its members survive obfuscation.",
                missing.isEmpty(),
            )
        }
    }
}
