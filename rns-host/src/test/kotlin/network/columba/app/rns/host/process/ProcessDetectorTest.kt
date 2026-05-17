package network.columba.app.rns.host.process

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [ProcessDetector]'s process-type classification.
 *
 * The Espresso classpath probe matches the existing
 * `ColumbaApplication.isRunningInTest()` heuristic — it fires for instrumented
 * tests where Espresso lives on the loader, but NOT for Robolectric unit
 * tests (whose loader contains Robolectric, not Espresso). The fall-through
 * branch reads the process name, which under Robolectric resolves to a name
 * without `:reticulum`, so the correct answer is [ProcessType.UI].
 *
 * That's the intended behaviour: in a Robolectric run the host module is
 * exercising "the UI side of the seam" — exactly the case where the
 * process-aware Hilt module should pick `BoundRnsBackend`.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessDetectorTest {
    @Test
    fun `detect returns UI under Robolectric — never RETICULUM`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric process never carries the `:reticulum` suffix.
        assertNotEquals(ProcessType.RETICULUM, ProcessDetector.detect(context))
        assertEquals(ProcessType.UI, ProcessDetector.detect(context))
    }

    @Test
    fun `currentProcessName returns a non-empty value under Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = ProcessDetector.currentProcessName(context)
        // Robolectric exposes a real process name via Application.getProcessName().
        // The exact value isn't useful to assert on; that it's resolvable is.
        assert(name != null && name.isNotEmpty()) {
            "Expected a non-empty process name, got: $name"
        }
    }
}
