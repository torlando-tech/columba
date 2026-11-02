package network.columba.app.ui.screens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.notifications.NotificationHelper
import network.columba.app.repository.SettingsRepository
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.NotificationSettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TOR-31: POST_NOTIFICATIONS must never be silently missed.
 *
 * Covers the user-reported bug: persisted `notificationsEnabled = true` with the
 * runtime permission denied used to leave the screen showing the master toggle
 * ON with no prompt and no warning, so incoming messages produced no
 * notifications and the user had to toggle off/on to get the system dialog.
 *
 * Contract under test:
 * - Entry with enabled && !granted (API 33+) surfaces a "Grant permission"
 *   affordance; permanently-denied routes to system app settings instead.
 * - Enabling the master toggle while denied requests the permission and never
 *   persists `enabled = true`.
 * - Permission state is re-read on ON_RESUME (grants/revocations via the
 *   system Settings UI have no launcher callback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationSettingsScreenPermissionTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notificationHelper: NotificationHelper

    private val notificationsEnabledFlow = MutableStateFlow(true)
    private val notificationReceivedMessageFlow = MutableStateFlow(true)
    private val notificationReceivedMessageFavoriteFlow = MutableStateFlow(true)
    private val notificationHeardAnnounceFlow = MutableStateFlow(false)
    private val notificationPrivacyLevelFlow = MutableStateFlow(NotificationPrivacyLevel.DEFAULT)
    private val notificationAnnounceDirectOnlyFlow = MutableStateFlow(true)
    private val notificationAnnounceExcludeTcpFlow = MutableStateFlow(true)
    private val notificationBleConnectedFlow = MutableStateFlow(false)
    private val notificationBleDisconnectedFlow = MutableStateFlow(false)
    private val hasRequestedNotificationPermissionFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        settingsRepository = mockk()
        notificationHelper = mockk()
        every { settingsRepository.notificationsEnabledFlow } returns notificationsEnabledFlow
        every { settingsRepository.notificationReceivedMessageFlow } returns notificationReceivedMessageFlow
        every { settingsRepository.notificationReceivedMessageFavoriteFlow } returns notificationReceivedMessageFavoriteFlow
        every { settingsRepository.notificationHeardAnnounceFlow } returns notificationHeardAnnounceFlow
        every { settingsRepository.notificationPrivacyLevelFlow } returns notificationPrivacyLevelFlow
        every { settingsRepository.notificationAnnounceDirectOnlyFlow } returns notificationAnnounceDirectOnlyFlow
        every { settingsRepository.notificationAnnounceExcludeTcpFlow } returns notificationAnnounceExcludeTcpFlow
        every { settingsRepository.notificationBleConnectedFlow } returns notificationBleConnectedFlow
        every { settingsRepository.notificationBleDisconnectedFlow } returns notificationBleDisconnectedFlow
        every { settingsRepository.hasRequestedNotificationPermissionFlow } returns hasRequestedNotificationPermissionFlow

        // Behave like the real repository: persisting flips the backing flow so
        // the screen observes the new enabled state.
        coEvery { settingsRepository.saveNotificationsEnabled(any()) } answers {
            notificationsEnabledFlow.value = firstArg()
        }
        coEvery { settingsRepository.saveNotificationReceivedMessage(any()) } just Runs
        coEvery { settingsRepository.saveNotificationReceivedMessageFavorite(any()) } just Runs
        coEvery { settingsRepository.saveNotificationHeardAnnounce(any()) } just Runs
        coEvery { settingsRepository.saveNotificationAnnounceDirectOnly(any()) } just Runs
        coEvery { settingsRepository.saveNotificationAnnounceExcludeTcp(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleConnected(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleDisconnected(any()) } just Runs
        coEvery { settingsRepository.markNotificationPermissionRequested() } just Runs
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun setNotificationPermission(granted: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (granted) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showScreen() {
        composeRule.setContent {
            NotificationSettingsScreen(
                onNavigateBack = {},
                viewModel = NotificationSettingsViewModel(settingsRepository, notificationHelper),
            )
        }
        composeRule.waitForIdle()
    }

    private fun assertPermissionRequestIntent(
        intent: Intent,
        reason: String,
    ) {
        assertNotNull(reason, intent)
        // ActivityResultRegistry routes RequestPermission through
        // Activity.requestPermissions; the platform (and Robolectric's shadow)
        // record it as this action + permission-names extra.
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", intent.action)
        val requested = intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")
        assertTrue(
            "$reason: expected POST_NOTIFICATIONS, got ${requested?.contentToString()}",
            requested?.contentEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) == true,
        )
    }

    /**
     * Result data the platform permission dialog returns for a granted
     * RequestPermission (parseResult reads the grant-results extra).
     */
    private fun permissionGrantResult() =
        Intent().putExtra(
            ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

    // ========== Entry reconciliation (the TOR-31 repro) ==========

    @Test
    fun `enabled with denied permission surfaces grant permission prompt on entry`() {
        notificationsEnabledFlow.value = true
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onNodeWithText("Grant permission").assertIsDisplayed()
    }

    @Test
    fun `enabled with granted permission shows no permission warning`() {
        notificationsEnabledFlow.value = true
        setNotificationPermission(granted = true)

        showScreen()

        composeRule.onNodeWithText("Grant permission").assertDoesNotExist()
    }

    @Test
    fun `disabled with denied permission shows no permission warning`() {
        notificationsEnabledFlow.value = false
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onNodeWithText("Grant permission").assertDoesNotExist()
    }

    // ========== Master toggle gating ==========

    @Test
    fun `enabling master toggle with denied permission requests permission without persisting enabled`() {
        notificationsEnabledFlow.value = false
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertNotNull("Toggle-on while denied must launch the permission request", started)
        assertPermissionRequestIntent(started.intent, "Toggle-on while denied must launch the permission request")
        coVerify(exactly = 0) { settingsRepository.saveNotificationsEnabled(true) }
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test
    fun `granting permission from the toggle request persists enabled`() {
        notificationsEnabledFlow.value = false
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitForIdle()
        val started = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertNotNull(started)

        setNotificationPermission(granted = true)
        shadowOf(composeRule.activity)
            .callOnActivityResult(started.requestCode, Activity.RESULT_OK, permissionGrantResult())
        composeRule.waitForIdle()

        coVerify(exactly = 1) { settingsRepository.saveNotificationsEnabled(true) }
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
    }

    // ========== Warning card actions ==========

    @Test
    fun `grant permission action launches notification permission request`() {
        notificationsEnabledFlow.value = true
        hasRequestedNotificationPermissionFlow.value = false
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onNodeWithText("Grant permission").performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertNotNull("Grant permission must launch the system permission dialog", started)
        assertPermissionRequestIntent(started.intent, "Grant permission must launch the system permission dialog")
    }

    @Test
    fun `denying permission from the warning card disables notifications and records the refusal`() {
        notificationsEnabledFlow.value = true
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onNodeWithText("Grant permission").performClick()
        composeRule.waitForIdle()
        val started = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertNotNull(started)

        shadowOf(composeRule.activity).callOnActivityResult(started.requestCode, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()

        // Never leave enabled=true persisted without the permission...
        coVerify(exactly = 1) { settingsRepository.saveNotificationsEnabled(false) }
        // ...and remember the refusal so a later tap routes to app settings.
        coVerify(exactly = 1) { settingsRepository.markNotificationPermissionRequested() }
        composeRule.onNodeWithText("Grant permission").assertDoesNotExist()
    }

    @Test
    fun `permanently denied routes grant action to app settings instead of a dead-end dialog`() {
        notificationsEnabledFlow.value = true
        // Persisted refusal + no rationale (Robolectric default) == permanently denied.
        hasRequestedNotificationPermissionFlow.value = true
        setNotificationPermission(granted = false)

        showScreen()

        composeRule.onNodeWithText("Open app settings").assertIsDisplayed()
        composeRule.onNodeWithText("Open app settings").performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull("Permanently denied must open system app settings", started)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${composeRule.activity.packageName}", started.dataString)
    }

    // ========== Resume refresh ==========

    @Test
    fun `permission revoked while backgrounded resurfaces warning on resume`() {
        notificationsEnabledFlow.value = true
        setNotificationPermission(granted = true)

        showScreen()
        composeRule.onNodeWithText("Grant permission").assertDoesNotExist()

        // User revokes the permission via system Settings while the app is backgrounded.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        setNotificationPermission(granted = false)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Grant permission").assertIsDisplayed()
    }

    @Test
    fun `permission granted while backgrounded clears warning on resume`() {
        notificationsEnabledFlow.value = true
        setNotificationPermission(granted = false)

        showScreen()
        composeRule.onNodeWithText("Grant permission").assertIsDisplayed()

        // User grants via system Settings (no launcher callback fires).
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        setNotificationPermission(granted = true)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Grant permission").assertDoesNotExist()
    }
}
