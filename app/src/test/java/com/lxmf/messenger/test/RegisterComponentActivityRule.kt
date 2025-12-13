package com.lxmf.messenger.test

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/**
 * JUnit Rule that registers ComponentActivity with Robolectric's ShadowPackageManager.
 *
 * This is required for Compose UI tests using `createComposeRule()` with Robolectric,
 * as the activity must be registered before the Compose test rule tries to launch it.
 *
 * Usage:
 * ```
 * private val registerActivityRule = RegisterComponentActivityRule()
 * private val composeRule = createComposeRule()
 *
 * @get:Rule
 * val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)
 *
 * // Access composeRule via a getter
 * val composeTestRule get() = composeRule
 * ```
 */
class RegisterComponentActivityRule : TestWatcher() {
    override fun starting(description: Description?) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val shadowPackageManager = shadowOf(application.packageManager)
        val activityInfo =
            ActivityInfo().apply {
                name = ComponentActivity::class.java.name
                packageName = application.packageName
            }
        shadowPackageManager.addOrUpdateActivity(activityInfo)
    }
}
