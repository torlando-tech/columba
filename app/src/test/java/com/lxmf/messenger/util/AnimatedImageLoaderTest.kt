package com.lxmf.messenger.util

import android.app.Application
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for AnimatedImageLoader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnimatedImageLoaderTest {
    @After
    fun tearDown() {
        AnimatedImageLoader.clearInstance()
    }

    @Test
    fun `getInstance returns non-null ImageLoader`() {
        val context = RuntimeEnvironment.getApplication()
        val loader = AnimatedImageLoader.getInstance(context)
        assertNotNull(loader)
    }

    @Test
    fun `getInstance returns same instance on subsequent calls`() {
        val context = RuntimeEnvironment.getApplication()
        val loader1 = AnimatedImageLoader.getInstance(context)
        val loader2 = AnimatedImageLoader.getInstance(context)
        assertSame(loader1, loader2)
    }

    @Test
    fun `create returns new ImageLoader instance`() {
        val context = RuntimeEnvironment.getApplication()
        val loader1 = AnimatedImageLoader.create(context)
        val loader2 = AnimatedImageLoader.create(context)
        assertNotNull(loader1)
        assertNotNull(loader2)
        // create() should return new instances each time
        assert(loader1 !== loader2)
    }

    @Test
    fun `clearInstance allows new instance to be created`() {
        val context = RuntimeEnvironment.getApplication()
        val loader1 = AnimatedImageLoader.getInstance(context)
        AnimatedImageLoader.clearInstance()
        val loader2 = AnimatedImageLoader.getInstance(context)

        assertNotNull(loader1)
        assertNotNull(loader2)
        // After clearing, should get a new instance
        assert(loader1 !== loader2)
    }

    @Test
    @Config(sdk = [28])
    fun `create uses ImageDecoderDecoder on API 28`() {
        val context = RuntimeEnvironment.getApplication()
        val loader = AnimatedImageLoader.create(context)
        // Just verify it creates successfully on API 28+
        assertNotNull(loader)
    }

    @Test
    @Config(sdk = [26])
    fun `create uses GifDecoder on API 26`() {
        val context = RuntimeEnvironment.getApplication()
        val loader = AnimatedImageLoader.create(context)
        // Just verify it creates successfully on API < 28
        assertNotNull(loader)
    }

    @Test
    @Config(sdk = [24])
    fun `create works on minimum supported API 24`() {
        val context = RuntimeEnvironment.getApplication()
        val loader = AnimatedImageLoader.create(context)
        assertNotNull(loader)
    }

    @Test
    fun `getInstance is thread-safe`() {
        val context = RuntimeEnvironment.getApplication()
        val loaders = mutableListOf<Any?>()

        // Simulate concurrent access
        val threads =
            (1..10).map {
                Thread {
                    val loader = AnimatedImageLoader.getInstance(context)
                    synchronized(loaders) {
                        loaders.add(loader)
                    }
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // All threads should have received the same instance
        assertNotNull(loaders[0])
        loaders.forEach { assertSame(loaders[0], it) }
    }
}
