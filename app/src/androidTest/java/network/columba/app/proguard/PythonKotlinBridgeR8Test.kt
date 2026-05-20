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

    /** Fully-qualified names of every @Keep'd bridge Chaquopy resolves by name. */
    private val bridgeClasses = listOf(
        "network.columba.app.rns.host.rnode.KotlinRNodeBridge",
        "network.columba.app.rns.host.ble.bridge.KotlinBLEBridge",
        "network.columba.app.rns.host.usb.KotlinUSBBridge",
        "network.columba.app.rns.backend.py.PythonEventBridge",
    )

    /**
     * Method names Python calls on the bridges, harvested from the bridge call
     * sites in `rns-backend-py/src/main/python` (e.g. `self.kotlin_bridge.connect(...)`,
     * `self.usb_bridge.findDeviceByVidPid(...)`). Every one must remain a declared,
     * un-renamed method on at least one kept bridge class.
     */
    private val pythonCalledMethods = setOf(
        "configurePower", "connect", "connectAsync", "disconnect", "disconnectAsync",
        "disconnectCentralAsync", "disconnectPeripheralAsync", "ensureAdvertising",
        "findDeviceByVidPid", "getConnectedDeviceName", "getPeerRssi", "isConnected",
        "notifyBluetoothPin", "notifyOnlineStatusChanged", "read", "requestIdentityResync",
        "sendAsync", "setIdentity", "setOnAddressChanged", "setOnConnected",
        "setOnConnectionStateChanged", "setOnDataReceived", "setOnDeviceDiscovered",
        "setOnDisconnected", "setOnDuplicateIdentityDetected", "setOnIdentityReceived",
        "setOnMtuNegotiated", "shouldConnect", "startAdvertisingAsync", "startAsync",
        "startScanningAsync", "stopAdvertisingAsync", "stopAsync", "stopScanningAsync",
    )

    @Test
    fun bridgeClassesAndMethodsSurviveR8() {
        val loaded = bridgeClasses.map { fqcn ->
            try {
                Class.forName(fqcn, false, targetClassLoader)
            } catch (e: ClassNotFoundException) {
                throw AssertionError(
                    "R8 stripped/renamed bridge class '$fqcn'. Annotate it with " +
                        "@androidx.annotation.Keep — Chaquopy resolves it by this exact name.",
                    e,
                )
            }
        }

        // Every Python-called method name must still be declared (and un-renamed)
        // on at least one kept bridge class.
        val keptMethodNames = loaded.flatMap { cls -> cls.declaredMethods.map { it.name } }.toSet()
        val missing = pythonCalledMethods - keptMethodNames
        assertTrue(
            "R8 stripped/renamed Python-called bridge methods: $missing. The owning " +
                "bridge class must be @Keep-annotated so its members survive obfuscation.",
            missing.isEmpty(),
        )
    }
}
