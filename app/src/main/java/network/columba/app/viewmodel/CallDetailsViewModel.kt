package network.columba.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.BlockedPeerRepository
import network.columba.app.data.repository.CallHistoryRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState
import network.columba.app.ui.model.CodecProfile

enum class BlockLookupState { LOADING, BLOCKED, UNBLOCKED, ERROR }

const val CALL_AGAIN_FAILURE_RESULT = "callAgainFailure"
private const val LIVE_OWNERSHIP_RETRY_MILLIS = 500L

data class CallDetailsState(
    val record: CallHistoryRecord? = null,
    val isLoading: Boolean = true,
    val blockState: BlockLookupState = BlockLookupState.LOADING,
    val hasError: Boolean = false,
    val actionError: Boolean = false,
    val canStartCall: Boolean = false,
    val recoveryPending: Boolean = false,
    val isActiveAttempt: Boolean = false,
    val runtimeBlockRecoveryRequired: Boolean = false,
    val localIdentityMatches: Boolean = false,
)

private data class LiveCallLookup(
    val call: VoiceCallState?,
    val resolved: Boolean,
)

private fun CallState.isLiveCallState(): Boolean =
    when (this) {
        is CallState.Connecting, is CallState.Ringing, is CallState.Incoming, is CallState.Active -> true
        else -> false
    }

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailsViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val repository: CallHistoryRepository,
        private val announceRepository: AnnounceRepository,
        private val blockedPeerRepository: BlockedPeerRepository,
        private val rnsCore: RnsCore,
        private val rnsTelephony: RnsTelephony,
    ) : ViewModel() {
        private val callAttemptId: String = checkNotNull(savedStateHandle["callAttemptId"])
        val callAgainFailure = savedStateHandle.getStateFlow<String?>(CALL_AGAIN_FAILURE_RESULT, null)

        fun consumeCallAgainFailure() {
            savedStateHandle[CALL_AGAIN_FAILURE_RESULT] = null
        }

        private val actionFailure = MutableStateFlow(false)
        private val runtimeBlockRecoveryRequired = MutableStateFlow(false)
        private val liveCallState =
            rnsTelephony.callState
                .flatMapLatest { coarseState ->
                    flow {
                        if (!coarseState.isLiveCallState()) {
                            emit(LiveCallLookup(call = null, resolved = true))
                            return@flow
                        }
                        while (true) {
                            val snapshot = rnsTelephony.getCallState()
                            emit(LiveCallLookup(snapshot.getOrNull(), snapshot.isSuccess))
                            if (snapshot.isSuccess) break
                            delay(LIVE_OWNERSHIP_RETRY_MILLIS)
                        }
                    }
                }.catch { emit(LiveCallLookup(call = null, resolved = false)) }

        private val baseState =
            repository.observeRecord(callAttemptId).flatMapLatest { record ->
                if (record == null) {
                    flowOf(CallDetailsState(record = record, isLoading = false))
                } else {
                    combine(
                        blockedPeerRepository.isIdentityBlockedFlow(record.remoteIdentityHash),
                        liveCallState,
                        repository.observeActiveIdentityHash(),
                    ) { blocked, liveLookup, activeIdentityHash ->
                        val liveCall = liveLookup.call
                        val callIsIdle =
                            liveLookup.resolved &&
                                (liveCall == null || liveCall.status in setOf("idle", "ended", "busy", "rejected"))
                        val exactAttemptIsLive =
                            record.endedAt == null &&
                                liveCall?.callAttemptId == record.callAttemptId &&
                                liveCall.status in setOf("connecting", "ringing", "incoming", "active") &&
                                liveCall.remoteIdentity.equals(record.remoteIdentityHash, ignoreCase = true)
                        val localIdentityMatches = activeIdentityHash.equals(record.localIdentityHash, ignoreCase = true)
                        CallDetailsState(
                            record = record,
                            isLoading = false,
                            blockState = if (blocked) BlockLookupState.BLOCKED else BlockLookupState.UNBLOCKED,
                            canStartCall =
                                !blocked &&
                                    callIsIdle &&
                                    record.endedAt != null &&
                                    localIdentityMatches,
                            recoveryPending = record.endedAt == null && !exactAttemptIsLive,
                            isActiveAttempt = exactAttemptIsLive,
                            localIdentityMatches = localIdentityMatches,
                        )
                    }.onStart {
                        emit(CallDetailsState(record = record, isLoading = false))
                    }.catch {
                        emit(
                            CallDetailsState(
                                record = record,
                                isLoading = false,
                                blockState = BlockLookupState.ERROR,
                                actionError = true,
                            ),
                        )
                    }
                }
            }.catch {
                emit(CallDetailsState(isLoading = false, hasError = true))
            }

        val state =
            combine(baseState, actionFailure, runtimeBlockRecoveryRequired) { base, failed, recoveryRequired ->
                base.copy(
                    actionError = base.actionError || failed,
                    canStartCall = base.canStartCall && !recoveryRequired,
                    runtimeBlockRecoveryRequired = recoveryRequired,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = CallDetailsState(),
            )

        private val _deleted = MutableSharedFlow<Unit>()
        val deleted = _deleted.asSharedFlow()

        fun deleteRecord() {
            val record = state.value.record ?: return
            if (record.endedAt == null) return
            viewModelScope.launch {
                actionFailure.value = false
                repository.deleteFinalized(callAttemptId)
                    .onSuccess { _deleted.emit(Unit) }
                    .onFailure { actionFailure.value = true }
            }
        }

        suspend fun getRecommendedCodecProfile(remoteIdentityHash: String): CodecProfile {
            return runCatching {
                val destination =
                    announceRepository.findTelephonyByIdentityHash(remoteIdentityHash)?.destinationHash
                        ?: return@runCatching CodecProfile.DEFAULT
                val destinationBytes = destination.hexToBytesOrNull() ?: return@runCatching CodecProfile.DEFAULT
                val probe = rnsCore.probeLinkSpeed(destinationBytes, 5.0f, "direct")
                if (probe.isSuccess) CodecProfile.recommendFromProbe(probe) else CodecProfile.DEFAULT
            }.getOrDefault(CodecProfile.DEFAULT)
        }

        fun resolveCurrentPeerDestination(onResolved: (String) -> Unit) {
            val remoteIdentityHash = state.value.record?.remoteIdentityHash ?: return
            viewModelScope.launch {
                actionFailure.value = false
                val destination =
                    runCatching { announceRepository.findTelephonyByIdentityHash(remoteIdentityHash)?.destinationHash }
                        .getOrNull()
                if (destination == null) actionFailure.value = true else onResolved(destination)
            }
        }

        private fun String.hexToBytesOrNull(): ByteArray? {
            if (!matches(Regex("^[0-9a-fA-F]{32}$"))) return null
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun toggleBlocked(blackholeEnabled: Boolean = false) {
            val record = state.value.record ?: return
            val priorBlockState = state.value.blockState
            if (!state.value.localIdentityMatches) return
            if (priorBlockState !in setOf(BlockLookupState.BLOCKED, BlockLookupState.UNBLOCKED)) return
            viewModelScope.launch {
                actionFailure.value = false
                runCatching {
                    if (priorBlockState == BlockLookupState.BLOCKED) {
                        unblockIdentity(record)
                    } else {
                        blockIdentity(record, blackholeEnabled)
                    }
                }.onSuccess {
                    runtimeBlockRecoveryRequired.value = false
                }.onFailure { actionFailure.value = true }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun blockIdentity(
            record: CallHistoryRecord,
            blackholeEnabled: Boolean,
        ) {
            val destinations = blockedPeerRepository.getApprovedDestinationHashes(record.remoteIdentityHash)
            blockedPeerRepository.blockIdentity(
                remoteIdentityHash = record.remoteIdentityHash,
                displayName = record.displayName,
                blackholeEnabled = blackholeEnabled,
                localIdentityHash = record.localIdentityHash,
            )
            try {
                rnsCore.blockIdentity(record.remoteIdentityHash).getOrThrow()
                destinations.forEach { destination ->
                    rnsCore.blockDestination(destination).getOrThrow()
                }
                if (blackholeEnabled) rnsCore.blackholeIdentity(record.remoteIdentityHash).getOrThrow()
            } catch (error: Throwable) {
                // Room is the desired-state authority. Keep the durable block so the
                // service-owned multi-instance reconciler can retry partial/failed Binder work.
                runtimeBlockRecoveryRequired.value = true
                throw error
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun unblockIdentity(record: CallHistoryRecord) {
            val blockedPeer =
                blockedPeerRepository.getIdentityBlock(
                    record.remoteIdentityHash,
                    record.localIdentityHash,
                ) ?: return
            val destinationsBeforeRemoval =
                (blockedPeerRepository.getApprovedDestinationHashes(record.remoteIdentityHash) +
                    blockedPeer.peerHash.takeUnless { it == record.remoteIdentityHash })
                    .filterNotNull()
                    .distinct()
            // Durable authority is removed first. If the UI process dies before Binder cleanup,
            // the service remains conservatively over-blocked rather than allowing a durably
            // blocked peer. Service restart restores the now-unblocked durable state.
            blockedPeerRepository.unblockIdentity(record.remoteIdentityHash, record.localIdentityHash)
            val unenforced = mutableListOf<String>()
            try {
                val destinations =
                    (destinationsBeforeRemoval +
                        blockedPeerRepository.getApprovedDestinationHashes(record.remoteIdentityHash))
                        .distinct()
                destinations.forEach { destination ->
                    rnsCore.unblockDestination(destination).getOrThrow()
                    unenforced += destination
                }
                rnsCore.unblockIdentity(record.remoteIdentityHash).getOrThrow()
                if (blockedPeer.isBlackholeEnabled) {
                    rnsCore.unblackholeIdentity(record.remoteIdentityHash).getOrThrow()
                }
            } catch (error: Throwable) {
                runCatching {
                    withContext(NonCancellable) {
                        blockedPeerRepository.blockIdentity(
                            remoteIdentityHash = record.remoteIdentityHash,
                            displayName = blockedPeer.displayName,
                            blackholeEnabled = blockedPeer.isBlackholeEnabled,
                            localIdentityHash = record.localIdentityHash,
                        )
                    }
                }.onFailure {
                    runtimeBlockRecoveryRequired.value = true
                    error.addSuppressed(it)
                }
                val compensation =
                    buildList<suspend () -> Result<Unit>> {
                        unenforced.forEach { destination -> add { rnsCore.blockDestination(destination) } }
                        add { rnsCore.blockIdentity(record.remoteIdentityHash) }
                        if (blockedPeer.isBlackholeEnabled) add { rnsCore.blackholeIdentity(record.remoteIdentityHash) }
                    }
                compensateAndRethrow(error, compensation)
            }
        }

        private suspend fun compensateAndRethrow(
            original: Throwable,
            operations: List<suspend () -> Result<Unit>>,
        ): Nothing {
            var recoveryFailed = false
            withContext(NonCancellable) {
                operations.forEach { operation ->
                    runCatching { retryCompensation(operation) }
                        .exceptionOrNull()
                        ?.let {
                            recoveryFailed = true
                            original.addSuppressed(it)
                        }
                }
            }
            if (recoveryFailed) runtimeBlockRecoveryRequired.value = true
            throw original
        }

        private suspend fun retryCompensation(operation: suspend () -> Result<Unit>) {
            var lastFailure: Throwable? = null
            repeat(3) {
                val result = operation()
                if (result.isSuccess) return
                lastFailure = result.exceptionOrNull()
            }
            throw checkNotNull(lastFailure) { "Runtime compensation failed" }
        }
    }
