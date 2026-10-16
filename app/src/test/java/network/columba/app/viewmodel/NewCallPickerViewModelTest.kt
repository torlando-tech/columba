@file:Suppress("IgnoredReturnValue")

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.data.repository.CallHistoryRepository
import network.columba.app.util.LxstDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewCallPickerViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callHistoryRepository: CallHistoryRepository
    private lateinit var viewModel: NewCallPickerViewModel

    private val telephonyHash = "8137b7aa68cce05c931a4ea1634ea1c7"
    private val identityHash = "ab".repeat(16)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        callHistoryRepository = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(
        remoteIdentityHash: String,
        direction: String,
        attemptedAt: Long,
        displayName: String? = null,
        peerDisplayNameSnapshot: String? = null,
        icon: Triple<String, String, String>? = null,
    ) = CallHistoryRecord(
        callAttemptId = "attempt-$remoteIdentityHash-$attemptedAt",
        localIdentityHash = "cd".repeat(16),
        remoteIdentityHash = remoteIdentityHash,
        direction = direction,
        peerDisplayNameSnapshot = peerDisplayNameSnapshot,
        codecProfileCode = null,
        attemptedAt = attemptedAt,
        ringingAt = null,
        connectedAt = null,
        endedAt = null,
        outcome = null,
        inferredEnding = false,
        failureReason = null,
        displayName = displayName,
        currentDestinationHash = null,
        iconName = icon?.first,
        iconForegroundColor = icon?.second,
        iconBackgroundColor = icon?.third,
    )

    @Test
    fun `recent destinations map direction to input mode and order newest first`() =
        runTest {
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(
                        record(telephonyHash, "INCOMING", attemptedAt = 100, displayName = "Old Caller"),
                        record(identityHash, "OUTGOING", attemptedAt = 300, displayName = "Alice"),
                        record("ee".repeat(16), "OUTGOING", attemptedAt = 200, peerDisplayNameSnapshot = "Bob"),
                    ),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(
                    listOf(
                        NewCallRecentDestination(identityHash, NewCallInputMode.LXST_DESTINATION_HASH, "Alice"),
                        NewCallRecentDestination("ee".repeat(16), NewCallInputMode.LXST_DESTINATION_HASH, "Bob"),
                        NewCallRecentDestination(telephonyHash, NewCallInputMode.IDENTITY_HASH, "Old Caller"),
                    ),
                    recent,
                )
            }
        }

    @Test
    fun `recent destinations deduplicate by hash keeping the newest label`() =
        runTest {
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(
                        record(telephonyHash, "OUTGOING", attemptedAt = 100, displayName = "Old Name"),
                        record(telephonyHash, "OUTGOING", attemptedAt = 200, displayName = "New Name"),
                    ),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(1, recent.size)
                assertEquals("New Name", recent.single().label)
            }
        }

    @Test
    fun `recent destinations merge incoming and outgoing rows of the same peer`() =
        runTest {
            val dialedDelivery = LxstDestination.deliveryDestinationHashFromNormalizedIdentity(identityHash)
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(
                        record(dialedDelivery, "OUTGOING", attemptedAt = 100, displayName = "Peer"),
                        record(identityHash, "INCOMING", attemptedAt = 200, displayName = "Peer"),
                    ),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(
                    listOf(NewCallRecentDestination(identityHash, NewCallInputMode.IDENTITY_HASH, "Peer")),
                    recent,
                )
            }
        }

    @Test
    fun `recent destinations merge outgoing telephony row with incoming identity row`() =
        runTest {
            val dialedTelephony = LxstDestination.telephonyDestinationHashFromNormalizedIdentity(identityHash)
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(
                        record(identityHash, "INCOMING", attemptedAt = 100, displayName = "Peer"),
                        record(dialedTelephony, "OUTGOING", attemptedAt = 200, displayName = "Peer"),
                    ),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(
                    listOf(
                        NewCallRecentDestination(
                            dialedTelephony,
                            NewCallInputMode.LXST_DESTINATION_HASH,
                            "Peer",
                        ),
                    ),
                    recent,
                )
            }
        }

    @Test
    fun `recent destinations cap at four entries`() =
        runTest {
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    (1..6).map { index ->
                        record(
                            "%02x".format(index).repeat(16),
                            "OUTGOING",
                            attemptedAt = index.toLong(),
                            displayName = "Peer $index",
                        )
                    },
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(4, recent.size)
                assertEquals("Peer 6", recent.first().label)
            }
        }

    @Test
    fun `recent destinations fall back to truncated hash without a name`() =
        runTest {
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(record(telephonyHash, "OUTGOING", attemptedAt = 100)),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals("${telephonyHash.take(12)}…", recent.single().label)
            }
        }

    @Test
    fun `recent destinations carry fused announce icon from either side of the peer`() =
        runTest {
            val dialedDelivery = LxstDestination.deliveryDestinationHashFromNormalizedIdentity(identityHash)
            every { callHistoryRepository.observeHistory() } returns
                flowOf(
                    listOf(
                        record(
                            dialedDelivery,
                            "OUTGOING",
                            attemptedAt = 200,
                            displayName = "Peer",
                        ),
                        record(
                            identityHash,
                            "INCOMING",
                            attemptedAt = 100,
                            displayName = "Peer",
                            icon = Triple("star", "FFFFFF", "1E88E5"),
                        ),
                    ),
                )

            viewModel = NewCallPickerViewModel(callHistoryRepository)

            viewModel.recentDestinations.test {
                skipItems(1) // initial empty state
                val recent = awaitItem()
                assertEquals(
                    listOf(
                        NewCallRecentDestination(
                            dialedDelivery,
                            NewCallInputMode.LXST_DESTINATION_HASH,
                            "Peer",
                            iconName = "star",
                            iconForegroundColor = "FFFFFF",
                            iconBackgroundColor = "1E88E5",
                        ),
                    ),
                    recent,
                )
            }
        }
}
