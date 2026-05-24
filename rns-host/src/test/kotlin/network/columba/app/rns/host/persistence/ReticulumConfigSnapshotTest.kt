package network.columba.app.rns.host.persistence

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the COLUMBA-AT crash.
 *
 * `ReticulumConfigSnapshot.read()` used to call `Parcel.readParcelable(ClassLoader,
 * Class)` — the type-safe overload added in **API 33** — while the module's minSdk is
 * 24. On API 24..32 that method does not exist, so the call threw `NoSuchMethodError`.
 * Because that's an `Error` (not an `Exception`), `read()`'s `catch (e: Exception)`
 * did NOT swallow it: it propagated out and crashed the `:reticulum` foreground
 * service on startup (the snapshot self-init path runs in `ReticulumService.onCreate`).
 *
 * The teeth are entirely in `@Config(sdk = ...)`: this runs on a **pre-Tiramisu**
 * runtime (API 32) where the 2-arg overload genuinely isn't on `Parcel`. The buggy
 * 2-arg call `NoSuchMethodError`s here; the 1-arg fix round-trips. Run at the default
 * (>= 33) SDK this test would pass either way and prove nothing — the low SDK is the point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2]) // 32 — last pre-Tiramisu level; readParcelable(ClassLoader, Class) is absent here
class ReticulumConfigSnapshotTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun sampleConfig() = ReticulumConfig(
        storagePath = "/data/data/test/files/reticulum",
        enabledInterfaces = emptyList(),
        displayName = "Test Node",
        enableTransport = true,
        shareInstanceHosting = true, // exercises the V2 field the snapshot version covers
        deliveryIdentityKey = ByteArray(64) { it.toByte() }, // must be stripped on write
    )

    @Test
    fun `write then read round-trips the config on a pre-Tiramisu runtime`() {
        ReticulumConfigSnapshot.clear(context)
        val config = sampleConfig()

        ReticulumConfigSnapshot.write(context, config, identityHashHex = "deadbeefcafe")
        val snap = ReticulumConfigSnapshot.read(context)

        // Pre-fix, read() never reached this line on API < 33 — readParcelable(ClassLoader,
        // Class) NoSuchMethodError'd and the test would error out here instead.
        assertNotNull("read() returned null — snapshot did not round-trip", snap)
        assertEquals("deadbeefcafe", snap!!.identityHashHex)
        assertNull(
            "delivery identity key must never be persisted to disk",
            snap.configWithoutKey.deliveryIdentityKey,
        )
        assertEquals(config.copy(deliveryIdentityKey = null), snap.configWithoutKey)
    }

    @Test
    fun `read returns null without crashing when no snapshot exists`() {
        ReticulumConfigSnapshot.clear(context)
        assertNull(ReticulumConfigSnapshot.read(context))
    }
}
