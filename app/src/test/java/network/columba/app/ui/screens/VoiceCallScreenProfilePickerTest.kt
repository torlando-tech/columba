package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import network.columba.app.R
import network.columba.app.audio.CallMicrophoneAdmissionCoordinator
import network.columba.app.audio.MicrophoneAdmissionArbiter
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.CodecProfile
import network.columba.app.viewmodel.CallViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for the in-call audio profile picker on the real
 * [VoiceCallScreen]: the control opens the sheet on a deliberate tap, the
 * sheet lists the profiles, a row tap switches to that exact profile through
 * the ViewModel, and the control is inert before the call is established.
 *
 * The picker composable's own behaviour (labels, selection, callbacks) is
 * covered by `CallProfileSheetTest`; this file covers only the screen wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceCallScreenProfilePickerTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    private lateinit var telephony: RnsTelephony
    private lateinit var contactRepository: ContactRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var callStateFlow: MutableStateFlow<CallState>
    private lateinit var activeProfileCodeFlow: MutableStateFlow<Int?>
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: CallViewModel

    private fun resText(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Before
    fun setup() {
        telephony = mockk()
        callStateFlow = MutableStateFlow(CallState.Active(REMOTE_IDENTITY))
        activeProfileCodeFlow = MutableStateFlow(CodecProfile.QUALITY_MEDIUM.code)
        scope = CoroutineScope(Dispatchers.Unconfined)

        every { telephony.callState } returns callStateFlow
        every { telephony.isMuted } returns MutableStateFlow(false)
        every { telephony.isSpeakerOn } returns MutableStateFlow(false)
        every { telephony.isPttMode } returns MutableStateFlow(false)
        every { telephony.isPttActive } returns MutableStateFlow(false)
        every { telephony.remoteIdentity } returns MutableStateFlow<String?>(REMOTE_IDENTITY)
        every { telephony.activeProfileCode } returns activeProfileCodeFlow
        every { telephony.callMode } returns MutableStateFlow<String?>(null)
        every { telephony.hasActiveCall() } returns true
        coEvery { telephony.switchCallProfile(any()) } answers { }

        contactRepository = mockk()
        announceRepository = mockk()
        coEvery { contactRepository.getContact(any()) } returns null
        coEvery { announceRepository.getAnnounce(any()) } returns null
        coEvery { announceRepository.findByIdentityHash(any()) } returns null

        viewModel =
            CallViewModel(
                contactRepository = contactRepository,
                announceRepository = announceRepository,
                telephony = telephony,
                callMicrophoneCoordinator =
                    CallMicrophoneAdmissionCoordinator(MicrophoneAdmissionArbiter(), telephony, scope),
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun renderScreen() {
        composeTestRule.setContent {
            MaterialTheme {
                VoiceCallScreen(
                    destinationHash = REMOTE_IDENTITY,
                    onEndCall = {},
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun `control shows the active profile abbreviation`() {
        renderScreen()

        // assertExists rather than assertIsDisplayed: Robolectric's default
        // window is short enough to clip the control labels below the fold.
        composeTestRule.onNodeWithText(CodecProfile.QUALITY_MEDIUM.abbreviation).assertExists()
    }

    @Test
    fun `tapping the control opens the picker`() {
        renderScreen()

        composeTestRule.onNodeWithText(resText(R.string.call_profile_title)).assertDoesNotExist()

        composeTestRule.onNodeWithTag("profileButton").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(resText(R.string.call_profile_title)).assertIsDisplayed()
    }

    @Test
    fun `tapping a row switches to that profile through the view model`() {
        renderScreen()

        composeTestRule.onNodeWithTag("profileButton").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("profileOption_${CodecProfile.BANDWIDTH_ULTRA_LOW.abbreviation}")
            .performClick()
        composeTestRule.waitForIdle()

        coVerify(exactly = 1) { telephony.switchCallProfile(CodecProfile.BANDWIDTH_ULTRA_LOW.code) }
        // The sheet closes as soon as a profile is picked.
        composeTestRule.onNodeWithText(resText(R.string.call_profile_title)).assertDoesNotExist()
    }

    @Test
    fun `control is disabled before the call is established`() {
        callStateFlow.value = CallState.Connecting(REMOTE_IDENTITY)
        renderScreen()

        composeTestRule.onNodeWithTag("profileButton").assertIsNotEnabled()
    }

    private companion object {
        const val REMOTE_IDENTITY = "0123456789abcdef0123456789abcdef"
    }
}
