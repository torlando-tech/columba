package com.lxmf.messenger.service

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.MessageReceipt
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SosManagerTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var contactRepository: ContactRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var audioRecorder: SosAudioRecorder
    private lateinit var sosManager: SosManager

    private val mockIdentity = mockk<Identity>()
    private val mockReceipt = mockk<MessageReceipt>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        contactRepository = mockk()
        settingsRepository = mockk()
        reticulumProtocol = mockk()
        notificationHelper = mockk()
        audioRecorder = mockk()
        every { audioRecorder.isRecording } returns false
        every { audioRecorder.hasPermission() } returns false
        every { audioRecorder.start() } returns false
        every { audioRecorder.stopRecorder() } returns Unit
        every { audioRecorder.readAndDeleteOutputFile() } returns null
        every { audioRecorder.cancel() } returns Unit

        // Settings defaults
        every { settingsRepository.sosEnabled } returns flowOf(true)
        every { settingsRepository.sosCountdownSeconds } returns flowOf(5)
        every { settingsRepository.sosMessageTemplate } returns flowOf("SOS! I need help.")
        every { settingsRepository.sosIncludeLocation } returns flowOf(false)
        every { settingsRepository.sosPeriodicUpdates } returns flowOf(false)
        every { settingsRepository.sosUpdateIntervalSeconds } returns flowOf(120)
        every { settingsRepository.sosDeactivationPin } returns flowOf(null)
        every { settingsRepository.sosSilentAutoAnswer } returns flowOf(false)
        every { settingsRepository.sosActive } returns flowOf(false)
        every { settingsRepository.sosActiveSentCount } returns flowOf(0)
        every { settingsRepository.sosActiveFailedCount } returns flowOf(0)
        every { settingsRepository.sosAudioEnabled } returns flowOf(false)
        every { settingsRepository.sosAudioDurationSeconds } returns flowOf(30)
        coEvery { settingsRepository.persistSosActiveState(any(), any()) } just Runs
        coEvery { settingsRepository.clearSosActiveState() } just Runs

        // Contact repository
        coEvery { contactRepository.getSosContacts() } returns emptyList()

        // NotificationHelper
        every { notificationHelper.showSosActiveNotification(any(), any()) } just Runs
        every { notificationHelper.cancelNotification(any()) } just Runs

        // Identity loading
        coEvery { reticulumProtocol.loadIdentity("default_identity") } returns Result.success(mockIdentity)

        // Default sendLxmfMessageWithMethod mock
        coEvery {
            reticulumProtocol.sendLxmfMessageWithMethod(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Result.success(mockReceipt)

        sosManager =
            SosManager(
                context = context,
                contactRepository = contactRepository,
                settingsRepository = settingsRepository,
                reticulumProtocol = reticulumProtocol,
                notificationHelper = notificationHelper,
                audioRecorder = audioRecorder,
            )
        sosManager.dispatcher = testDispatcher
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun makeContact(hexHash: String): EnrichedContact =
        EnrichedContact(
            destinationHash = hexHash,
            publicKey = null,
            displayName = "Contact-$hexHash",
            customNickname = null,
            announceName = null,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = false,
            unreadCount = 0,
            lastMessageTimestamp = null,
            notes = null,
            tags = """["sos"]""",
            addedTimestamp = System.currentTimeMillis(),
            addedVia = "MANUAL",
            isPinned = false,
        )

    // ========== State Tests ==========

    @Test
    fun `initial state is Idle`() =
        runTest {
            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `trigger when not enabled does nothing`() =
        runTest {
            every { settingsRepository.sosEnabled } returns flowOf(false)

            sosManager.trigger()
            advanceUntilIdle()

            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `trigger with countdown starts countdown`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(5)

            sosManager.trigger()
            // Advance enough for the launch to start but not complete the countdown
            advanceTimeBy(100)

            val state = sosManager.state.value
            assertTrue("Expected Countdown state but got $state", state is SosState.Countdown)
        }

    @Test
    fun `trigger with countdown 0 goes directly to Sending`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue(
                "Expected Active state (skipped countdown) but got $state",
                state is SosState.Active,
            )
        }

    @Test
    fun `countdown ticks correctly`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(3)

            sosManager.trigger()
            advanceTimeBy(100) // Let coroutine start

            val initialState = sosManager.state.value
            assertTrue("Expected Countdown state but got $initialState", initialState is SosState.Countdown)
            assertEquals(3, (initialState as SosState.Countdown).remainingSeconds)

            advanceTimeBy(1_000) // After 1 second tick

            val tickedState = sosManager.state.value
            if (tickedState is SosState.Countdown) {
                assertTrue(
                    "Expected remaining <= 2 but got ${tickedState.remainingSeconds}",
                    tickedState.remainingSeconds <= 2,
                )
            }
        }

    @Test
    fun `countdown completion transitions to Sending then Active`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(1)

            sosManager.trigger()
            // Advance past countdown (1s) + sending
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue(
                "Expected Active after countdown completes but got $state",
                state is SosState.Active,
            )
        }

    @Test
    fun `cancel during countdown returns to Idle`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(10)

            sosManager.trigger()
            advanceTimeBy(100) // Let countdown start

            val countdownState = sosManager.state.value
            assertTrue("Expected Countdown state but got $countdownState", countdownState is SosState.Countdown)

            sosManager.cancel()

            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `cancel when not in countdown does nothing`() =
        runTest {
            sosManager.cancel()
            assertEquals(SosState.Idle, sosManager.state.value)
        }

    // ========== Send Tests ==========

    @Test
    fun `sendSosMessages with no contacts transitions to Active(0, 0)`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            coEvery { contactRepository.getSosContacts() } returns emptyList()

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(0, (state as SosState.Active).sentCount)
            assertEquals(0, state.failedCount)
        }

    @Test
    fun `sendSosMessages sends to all contacts and counts successes`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)

            val contact1 = makeContact("0a0b0c0d0e0f1011")
            val contact2 = makeContact("1a1b1c1d1e1f2021")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact1, contact2)

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(2, (state as SosState.Active).sentCount)
            assertEquals(0, state.failedCount)
        }

    @Test
    fun `sendSosMessages counts failures`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)

            val contact1 = makeContact("0a0b0c0d0e0f1011")
            val contact2 = makeContact("1a1b1c1d1e1f2021")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact1, contact2)

            // Second contact fails
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    match {
                        it.contentEquals(
                            contact2.destinationHash.chunked(2)
                                .map { b -> b.toInt(16).toByte() }.toByteArray(),
                        )
                    },
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns Result.failure(RuntimeException("Network error"))

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            val activeState = state as SosState.Active
            assertTrue(
                "Expected at least one failure: sent=${activeState.sentCount}, failed=${activeState.failedCount}",
                activeState.failedCount >= 1,
            )
        }

    @Test
    fun `sendSosMessages shows notification`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            verify { notificationHelper.showSosActiveNotification(any(), any()) }
        }

    // ========== Deactivation Tests ==========

    private suspend fun triggerAndWaitForActive() {
        every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
        sosManager.trigger()
    }

    @Test
    fun `deactivate from Active without PIN succeeds`() =
        runTest {
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            val result = sosManager.deactivate()

            assertTrue(result)
            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `deactivate from Active with correct PIN succeeds`() =
        runTest {
            every { settingsRepository.sosDeactivationPin } returns flowOf("1234")
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            val result = sosManager.deactivate(pin = "1234")

            assertTrue(result)
            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `deactivate from Active with wrong PIN fails`() =
        runTest {
            every { settingsRepository.sosDeactivationPin } returns flowOf("1234")
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            val result = sosManager.deactivate(pin = "9999")

            assertFalse(result)
            assertTrue(sosManager.state.value is SosState.Active)
        }

    @Test
    fun `deactivate from non-Active state returns false`() =
        runTest {
            val result = sosManager.deactivate()
            assertFalse(result)
        }

    @Test
    fun `deactivate cancels periodic updates`() =
        runTest {
            every { settingsRepository.sosPeriodicUpdates } returns flowOf(true)
            every { settingsRepository.sosUpdateIntervalSeconds } returns flowOf(120)
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            val result = sosManager.deactivate()

            assertTrue(result)
            assertEquals(SosState.Idle, sosManager.state.value)
        }

    @Test
    fun `deactivate clears notification`() =
        runTest {
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            sosManager.deactivate()

            assertEquals(SosState.Idle, sosManager.state.value)
            verify { notificationHelper.cancelNotification(NotificationHelper.NOTIFICATION_ID_SOS) }
        }

    // ========== Auto-Answer Tests ==========

    @Test
    fun `shouldAutoAnswer when Active and setting enabled returns true`() =
        runTest {
            every { settingsRepository.sosSilentAutoAnswer } returns flowOf(true)
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            assertTrue(sosManager.shouldAutoAnswer())
        }

    @Test
    fun `shouldAutoAnswer when Active but setting disabled returns false`() =
        runTest {
            every { settingsRepository.sosSilentAutoAnswer } returns flowOf(false)
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            assertFalse(sosManager.shouldAutoAnswer())
        }

    @Test
    fun `shouldAutoAnswer when not Active returns false`() =
        runTest {
            assertFalse(sosManager.shouldAutoAnswer())
        }

    // ========== Re-trigger Tests ==========

    @Test
    fun `re-trigger after deactivation works`() =
        runTest {
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            sosManager.deactivate()
            assertEquals(SosState.Idle, sosManager.state.value)

            // Re-trigger
            sosManager.trigger()
            advanceUntilIdle()

            assertTrue(
                "Expected Active state after re-trigger but got ${sosManager.state.value}",
                sosManager.state.value is SosState.Active,
            )
        }

    // ========== Location Tests ==========

    @Test
    fun `message includes GPS when location available and setting enabled`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            every { settingsRepository.sosIncludeLocation } returns flowOf(true)

            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)

            val mockLocation = mockk<Location>()
            every { mockLocation.latitude } returns 48.8566
            every { mockLocation.longitude } returns 2.3522
            every { mockLocation.accuracy } returns 10f
            every { mockLocation.hasAltitude() } returns false
            every { mockLocation.hasSpeed() } returns false
            every { mockLocation.hasBearing() } returns false
            sosManager.locationProvider = { mockLocation }

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(1, (state as SosState.Active).sentCount)
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = match { it.contains("GPS: 48.856600, 2.352200") && it.contains("accuracy: 10m") },
                    sourceIdentity = any(),
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = any(),
                    imageFormat = any(),
                    fileAttachments = any(),
                    replyToMessageId = any(),
                    iconAppearance = any(),
                    telemetryJson = any(),
                    audioData = any(),
                    sosState = any(),
                )
            }
        }

    @Test
    fun `message excludes GPS when setting disabled`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            every { settingsRepository.sosIncludeLocation } returns flowOf(false)

            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(1, (state as SosState.Active).sentCount)
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = match { !it.contains("GPS:") },
                    sourceIdentity = any(),
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = any(),
                    imageFormat = any(),
                    fileAttachments = any(),
                    replyToMessageId = any(),
                    iconAppearance = any(),
                    telemetryJson = any(),
                    audioData = any(),
                    sosState = any(),
                )
            }
        }

    // ========== Restore State Tests ==========

    @Test
    fun `restoreIfActive when not previously active does nothing`() =
        runTest {
            every { settingsRepository.sosActive } returns flowOf(false)

            sosManager.restoreIfActive()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertEquals(SosState.Idle, state)
            verify(exactly = 0) { notificationHelper.showSosActiveNotification(any(), any()) }
        }

    @Test
    fun `restoreIfActive when previously active restores Active state`() =
        runTest {
            every { settingsRepository.sosActive } returns flowOf(true)
            every { settingsRepository.sosActiveSentCount } returns flowOf(3)
            every { settingsRepository.sosActiveFailedCount } returns flowOf(1)
            every { settingsRepository.sosPeriodicUpdates } returns flowOf(false)

            sosManager.restoreIfActive()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(3, (state as SosState.Active).sentCount)
            assertEquals(1, state.failedCount)
        }

    @Test
    fun `restoreIfActive shows notification with persisted counts`() =
        runTest {
            every { settingsRepository.sosActive } returns flowOf(true)
            every { settingsRepository.sosActiveSentCount } returns flowOf(2)
            every { settingsRepository.sosActiveFailedCount } returns flowOf(1)
            every { settingsRepository.sosPeriodicUpdates } returns flowOf(false)

            sosManager.restoreIfActive()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(2, (state as SosState.Active).sentCount)
            assertEquals(1, state.failedCount)
            verify { notificationHelper.showSosActiveNotification(2, 1) }
        }

    @Test
    fun `restoreIfActive starts periodic updates when enabled`() =
        runTest {
            every { settingsRepository.sosActive } returns flowOf(true)
            every { settingsRepository.sosActiveSentCount } returns flowOf(1)
            every { settingsRepository.sosActiveFailedCount } returns flowOf(0)
            every { settingsRepository.sosPeriodicUpdates } returns flowOf(true)
            every { settingsRepository.sosUpdateIntervalSeconds } returns flowOf(60)

            sosManager.restoreIfActive()
            // Only advance enough to let restoreIfActive complete and start periodic updates.
            // Don't use advanceUntilIdle() — startPeriodicUpdates() has an infinite loop.
            advanceTimeBy(500)

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)

            // Deactivate to cancel the periodic update coroutine
            sosManager.deactivate()
        }

    // ========== Persistence Tests ==========

    @Test
    fun `sendSosMessages persists active state`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(1, (state as SosState.Active).sentCount)
            coVerify { settingsRepository.persistSosActiveState(1, 0) }
        }

    @Test
    fun `deactivate clears persisted state`() =
        runTest {
            triggerAndWaitForActive()
            advanceUntilIdle()
            assertTrue(sosManager.state.value is SosState.Active)

            sosManager.deactivate()
            advanceUntilIdle()

            assertEquals(SosState.Idle, sosManager.state.value)
            coVerify { settingsRepository.clearSosActiveState() }
        }

    @Test
    fun `sendSosMessages persists correct failure count`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)

            val contact1 = makeContact("0a0b0c0d0e0f1011")
            val contact2 = makeContact("1a1b1c1d1e1f2021")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact1, contact2)

            // Second contact fails
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    match {
                        it.contentEquals(
                            contact2.destinationHash.chunked(2)
                                .map { b -> b.toInt(16).toByte() }.toByteArray(),
                        )
                    },
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns Result.failure(RuntimeException("Network error"))

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            coVerify { settingsRepository.persistSosActiveState(1, 1) }
        }

    @Test
    fun `sendSosMessages with failed identity does not send but persists state`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)
            coEvery { reticulumProtocol.loadIdentity("default_identity") } returns Result.failure(RuntimeException("No identity"))

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(0, (state as SosState.Active).sentCount)
            assertEquals(1, state.failedCount)
        }

    // ========== Battery Level Tests ==========

    @Test
    fun `message includes battery level when available`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            every { settingsRepository.sosIncludeLocation } returns flowOf(false)

            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)

            val mockBatteryManager = mockk<BatteryManager>()
            every { context.getSystemService(Context.BATTERY_SERVICE) } returns mockBatteryManager
            every { mockBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 73

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(1, (state as SosState.Active).sentCount)
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = match { it.contains("Battery: 73%") },
                    sourceIdentity = any(),
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = any(),
                    imageFormat = any(),
                    fileAttachments = any(),
                    replyToMessageId = any(),
                    iconAppearance = any(),
                    telemetryJson = any(),
                    audioData = any(),
                    sosState = any(),
                )
            }
        }

    @Test
    fun `message includes both GPS and battery when both available`() =
        runTest {
            every { settingsRepository.sosCountdownSeconds } returns flowOf(0)
            every { settingsRepository.sosIncludeLocation } returns flowOf(true)

            val contact = makeContact("0a0b0c0d0e0f1011")
            coEvery { contactRepository.getSosContacts() } returns listOf(contact)

            val mockLocation = mockk<Location>()
            every { mockLocation.latitude } returns 48.8566
            every { mockLocation.longitude } returns 2.3522
            every { mockLocation.accuracy } returns 10f
            every { mockLocation.hasAltitude() } returns false
            every { mockLocation.hasSpeed() } returns false
            every { mockLocation.hasBearing() } returns false
            sosManager.locationProvider = { mockLocation }

            val mockBatteryManager = mockk<BatteryManager>()
            every { context.getSystemService(Context.BATTERY_SERVICE) } returns mockBatteryManager
            every { mockBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 42
            every { mockBatteryManager.isCharging } returns false

            sosManager.trigger()
            advanceUntilIdle()

            val state = sosManager.state.value
            assertTrue("Expected Active state but got $state", state is SosState.Active)
            assertEquals(1, (state as SosState.Active).sentCount)
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content =
                        match {
                            it.contains("GPS: 48.856600, 2.352200") &&
                                it.contains("Battery: 42%")
                        },
                    sourceIdentity = any(),
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = any(),
                    imageFormat = any(),
                    fileAttachments = any(),
                    replyToMessageId = any(),
                    iconAppearance = any(),
                    telemetryJson = any(),
                    audioData = any(),
                    sosState = any(),
                )
            }
        }
}
