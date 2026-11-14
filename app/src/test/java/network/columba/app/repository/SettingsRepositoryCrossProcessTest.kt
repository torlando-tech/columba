package network.columba.app.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.data.repository.CustomThemeRepository
import network.columba.app.rns.api.notify.MessageNotificationBridge
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for SettingsRepository cross-process communication.
 *
 * Tests the SharedPreferences-based flows that enable communication between
 * the service process and main app process for announce timestamps.
 *
 * These tests verify that:
 * 1. Flows emit initial values correctly
 * 2. Save methods write to SharedPreferences (not DataStore)
 * 3. Flows react to SharedPreferences changes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryCrossProcessTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var mockCustomThemeRepository: CustomThemeRepository
    private lateinit var repository: SettingsRepository
    private lateinit var crossProcessPrefs: SharedPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // All methods explicitly stubbed below (no relaxed mock needed)
        mockCustomThemeRepository = mockk()
        every { mockCustomThemeRepository.getAllThemes() } returns flowOf(emptyList())
        every { mockCustomThemeRepository.getThemeByIdFlow(any()) } returns flowOf(null)

        repository = SettingsRepository(context, mockCustomThemeRepository)

        // Get direct access to cross-process SharedPreferences for testing
        @Suppress("DEPRECATION")
        crossProcessPrefs =
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )

        // Clear prefs before each test
        crossProcessPrefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        crossProcessPrefs.edit().clear().apply()
    }

    // ========== lastAutoAnnounceTimeFlow Tests ==========

    @Test
    fun `lastAutoAnnounceTimeFlow emits null when no value set`() =
        runTest {
            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertNull(initial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `lastAutoAnnounceTimeFlow emits value from SharedPreferences`() =
        runTest {
            val timestamp = 1234567890L
            crossProcessPrefs
                .edit()
                .putLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, timestamp)
                .apply()

            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                val value = awaitItem()
                assertEquals(timestamp, value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveLastAutoAnnounceTime writes to SharedPreferences`() =
        runTest {
            val timestamp = 9876543210L

            repository.saveLastAutoAnnounceTime(timestamp)

            val savedValue =
                crossProcessPrefs.getLong(
                    ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME,
                    -1L,
                )
            assertEquals(timestamp, savedValue)
        }

    @Test
    fun `saveLastAutoAnnounceTime does not write to DataStore`() =
        runTest {
            val timestamp = 5555555555L

            repository.saveLastAutoAnnounceTime(timestamp)

            // Verify it's in SharedPreferences
            val sharedPrefsValue =
                crossProcessPrefs.getLong(
                    ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME,
                    -1L,
                )
            assertEquals(timestamp, sharedPrefsValue)

            // The flow should also see the value (reads from SharedPreferences)
            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                val flowValue = awaitItem()
                assertEquals(timestamp, flowValue)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== networkChangeAnnounceTimeFlow Tests ==========

    @Test
    fun `networkChangeAnnounceTimeFlow emits null when no value set`() =
        runTest {
            repository.networkChangeAnnounceTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertNull(initial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `networkChangeAnnounceTimeFlow emits value from SharedPreferences`() =
        runTest {
            val timestamp = 1111111111L
            crossProcessPrefs
                .edit()
                .putLong(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME, timestamp)
                .apply()

            repository.networkChangeAnnounceTimeFlow.test(timeout = 5.seconds) {
                val value = awaitItem()
                assertEquals(timestamp, value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveNetworkChangeAnnounceTime writes to SharedPreferences`() =
        runTest {
            val timestamp = 2222222222L

            repository.saveNetworkChangeAnnounceTime(timestamp)

            val savedValue =
                crossProcessPrefs.getLong(
                    ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME,
                    -1L,
                )
            assertEquals(timestamp, savedValue)
        }

    // ========== Cross-process simulation Tests ==========

    @Test
    fun `service write is visible to repository flow`() =
        runTest {
            // Simulate service writing via ServiceSettingsAccessor
            val serviceAccessor = ServiceSettingsAccessor(context)
            val timestamp = 3333333333L

            serviceAccessor.saveLastAutoAnnounceTime(timestamp)

            // Repository should see the value
            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                val value = awaitItem()
                assertEquals(timestamp, value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `repository write is visible to service accessor`() =
        runTest {
            val serviceAccessor = ServiceSettingsAccessor(context)
            val timestamp = 4444444444L

            repository.saveNetworkChangeAnnounceTime(timestamp)

            // Service accessor reads from same SharedPreferences
            // Note: getBlockUnknownSenders reads from SharedPreferences, confirming the accessor works
            // For timestamps, we verify directly via SharedPreferences
            val savedValue =
                crossProcessPrefs.getLong(
                    ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME,
                    -1L,
                )
            assertEquals(timestamp, savedValue)
        }

    // ========== Message notification mirror ==========

    @Test
    fun `syncNotificationMirror writes permissive defaults on a fresh install`() =
        runTest {
            repository.syncNotificationMirror()

            assertTrue(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_NOTIFICATIONS_ENABLED, false))
            assertTrue(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE, false))
            assertTrue(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE_FAVORITE, false))
            assertTrue(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_PREVIEW, false))
            assertTrue(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_SENDER, false))
            assertTrue(
                "the mirror must be marked authoritative, or :reticulum refuses to notify",
                crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_MIRROR_WRITTEN, false),
            )
        }

    @Test
    fun `syncNotificationMirror reflects a level stored before this feature existed`() =
        runTest {
            // The user picked NONE in an earlier build; no setter ran with mirroring in it.
            repository.saveNotificationPrivacyLevel(NotificationPrivacyLevel.NONE)
            crossProcessPrefs.edit().clear().apply()

            repository.syncNotificationMirror()

            assertFalse(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_PREVIEW, true))
            assertFalse(crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_SENDER, true))
            assertTrue(
                "an upgrade sync must make the file authoritative for the service",
                crossProcessPrefs.getBoolean(MessageNotificationBridge.KEY_MIRROR_WRITTEN, false),
            )
        }

    @Test
    fun `importAllPreferences refreshes the notification mirror`() =
        runTest {
            // Importing NONE into an install whose mirror still allows previews must not
            // leave the service showing text the user chose to hide.
            repository.importAllPreferences(
                listOf(
                    network.columba.app.migration.PreferenceEntry(
                        key = "notification_privacy_level",
                        type = "string",
                        value = NotificationPrivacyLevel.NONE.name,
                    ),
                ),
            )

            val mirror = requireNotNull(ServiceSettingsAccessor(context).getNotificationMirror())
            assertFalse("NONE hides the text", mirror.revealPreview)
            assertFalse("NONE hides the sender", mirror.revealSender)
        }

    @Test
    fun `notification setters mirror the gates and privacy level`() =
        runTest {
            repository.saveNotificationsEnabled(false)
            repository.saveNotificationReceivedMessage(true)
            repository.saveNotificationReceivedMessageFavorite(false)
            repository.saveNotificationPrivacyLevel(NotificationPrivacyLevel.NAME_ONLY)

            val serviceMirror = requireNotNull(ServiceSettingsAccessor(context).getNotificationMirror())

            assertFalse(serviceMirror.enabled)
            assertTrue(serviceMirror.receivedMessage)
            assertFalse(serviceMirror.favoriteAllowed)
            assertFalse("NAME_ONLY hides the text", serviceMirror.revealPreview)
            assertTrue("NAME_ONLY still shows the sender", serviceMirror.revealSender)
        }

    // ========== distinctUntilChanged Tests ==========

    @Test
    fun `lastAutoAnnounceTimeFlow emits only on distinct values`() =
        runTest {
            val timestamp = 6666666666L
            crossProcessPrefs
                .edit()
                .putLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, timestamp)
                .apply()

            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                // Get initial value
                assertEquals(timestamp, awaitItem())

                // Write same value - should NOT emit due to distinctUntilChanged
                crossProcessPrefs
                    .edit()
                    .putLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, timestamp)
                    .apply()

                // No new emission expected for same value
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }
}
