package com.lxmf.messenger.util

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

/**
 * Factory for creating Coil ImageLoader instances with GIF animation support.
 *
 * Uses hardware-accelerated ImageDecoderDecoder on API 28+ and falls back to
 * software GifDecoder on API 24-27.
 */
object AnimatedImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    /**
     * Get or create a singleton ImageLoader with GIF decoder support.
     *
     * Thread-safe via double-checked locking.
     */
    fun getInstance(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    /**
     * Create a new ImageLoader with appropriate GIF decoder for the device's API level.
     *
     * @param context Application context (will use applicationContext internally)
     * @return ImageLoader configured for animated GIF support
     */
    fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+: Use hardware-accelerated ImageDecoder
                    add(ImageDecoderDecoder.Factory())
                } else {
                    // API 24-27: Use software GIF decoder
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    /**
     * Clear the singleton instance. Useful for testing.
     */
    fun clearInstance() {
        synchronized(this) {
            instance = null
        }
    }
}
