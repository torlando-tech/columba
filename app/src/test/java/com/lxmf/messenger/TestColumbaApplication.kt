package com.lxmf.messenger

import android.app.Application

/**
 * Test application that skips Python/Chaquopy initialization.
 * Used by Robolectric tests via robolectric.properties to avoid
 * native library loading issues in unit tests.
 */
class TestColumbaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Skip all Chaquopy/Python and Reticulum initialization
        // Tests will mock dependencies as needed
    }
}
