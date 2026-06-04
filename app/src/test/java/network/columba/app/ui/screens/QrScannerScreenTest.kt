package network.columba.app.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for COLUMBA-BA: opening the QR scanner crashed the app when CameraX
 * failed to initialize. On a BlueStacks-style virtual camera, camera enumeration throws
 * `IllegalArgumentException: format 0x... was not defined in either ImageFormat or
 * PixelFormat`, which `ProcessCameraProvider.getInstance(...).get()` rethrows wrapped in an
 * `ExecutionException` on the main-thread listener — previously uncaught.
 *
 * These tests exercise [initializeCameraProvider], the production seam that owns that
 * future resolution + error handling.
 */
class QrScannerScreenTest {
    /** Mimics `ListenableFuture.get()` rethrowing a CameraX init failure. */
    private class ThrowingFuture<T>(private val toThrow: Throwable) : Future<T> {
        override fun cancel(mayInterruptIfRunning: Boolean) = false

        override fun isCancelled() = false

        override fun isDone() = true

        override fun get(): T = throw toThrow

        override fun get(timeout: Long, unit: TimeUnit): T = throw toThrow
    }

    /** An already-resolved future, mimicking a successful CameraX init. */
    private class ImmediateFuture<T>(private val value: T) : Future<T> {
        override fun cancel(mayInterruptIfRunning: Boolean) = false

        override fun isCancelled() = false

        override fun isDone() = true

        override fun get(): T = value

        override fun get(timeout: Long, unit: TimeUnit): T = value
    }

    // The exact failure CameraX surfaces from a BlueStacks-style virtual camera (COLUMBA-BA):
    // an ExecutionException wrapping the unrecognized stream-config format.
    private val cameraXInitFailure =
        ExecutionException(
            "androidx.camera.core.InitializationException",
            IllegalArgumentException(
                "format 0xf0f21da0 was not defined in either ImageFormat or PixelFormat",
            ),
        )

    @After
    fun clearInterruptFlag() {
        // Don't let a restored interrupt flag leak into sibling tests on this thread.
        Thread.interrupted()
    }

    @Test
    fun `fixture reproduces the COLUMBA-BA failure (guards against a vacuous test)`() {
        val future = ThrowingFuture<Any>(cameraXInitFailure)

        val thrown = runCatching { future.get() }.exceptionOrNull()

        assertTrue("expected the raw future to throw", thrown is ExecutionException)
        assertEquals(
            "format 0xf0f21da0 was not defined in either ImageFormat or PixelFormat",
            thrown!!.cause?.message,
        )
    }

    @Test
    fun `camera init failure is caught and reported instead of crashing`() {
        val future = ThrowingFuture<Any>(cameraXInitFailure)
        var reportedError: String? = null
        var bindCalled = false

        // The crux of the fix: this call must NOT propagate the ExecutionException.
        initializeCameraProvider(
            future = future,
            onCameraError = { reportedError = it },
            bindUseCases = { bindCalled = true },
        )

        assertEquals(CAMERA_UNAVAILABLE_MESSAGE, reportedError)
        assertFalse("use cases must not be bound when init failed", bindCalled)
    }

    @Test
    fun `successful init binds the provider and reports no error`() {
        val provider = Any()
        val future = ImmediateFuture(provider)
        var reportedError: String? = null
        var bound: Any? = null

        initializeCameraProvider(
            future = future,
            onCameraError = { reportedError = it },
            bindUseCases = { bound = it },
        )

        assertSame("the resolved provider must reach bindUseCases", provider, bound)
        assertNull("no error on the happy path", reportedError)
    }

    @Test
    fun `interrupted init restores the thread interrupt flag and reports error`() {
        val future = ThrowingFuture<Any>(InterruptedException("init interrupted"))
        var reportedError: String? = null

        initializeCameraProvider(
            future = future,
            onCameraError = { reportedError = it },
            bindUseCases = { },
        )

        assertEquals(CAMERA_UNAVAILABLE_MESSAGE, reportedError)
        // Per InterruptedException best practice, the handler re-asserts the interrupt.
        assertTrue("interrupt flag must be restored", Thread.interrupted())
    }
}
