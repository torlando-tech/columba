package network.columba.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import network.columba.app.R
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.viewmodel.BlockLookupState

@Composable
internal fun CallDetailsRecord(
    record: CallHistoryRecord,
    blockState: BlockLookupState,
    actionError: Boolean,
    runtimeBlockRecoveryRequired: Boolean,
    localIdentityMatches: Boolean,
    canStartCall: Boolean,
    recoveryPending: Boolean,
    isActiveAttempt: Boolean,
    onCallAgain: (String, Int) -> Unit,
    onViewPeer: () -> Unit,
    onToggleBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val temporalSignal = temporalRefreshSignal()
    val dateTime = remember(temporalSignal) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    val destination = record.currentDestinationHash
    val connectedAt = record.connectedAt
    val endedAt = record.endedAt
    val failureReason = record.failureReason
    val now by
        produceState(
            initialValue = System.currentTimeMillis(),
            key1 = connectedAt,
            key2 = endedAt,
            key3 = isActiveAttempt,
        ) {
            while (connectedAt != null && endedAt == null && isActiveAttempt) {
                kotlinx.coroutines.delay(1000L)
                value = System.currentTimeMillis()
            }
        }
    val displayName =
        record.displayName?.takeIf(String::isNotBlank)
            ?: stringResource(
                R.string.call_peer_fallback,
                record.remoteIdentityHash.take(8).uppercase(Locale.ROOT),
            )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProfileIcon(
                    iconName = record.iconName,
                    foregroundColor = record.iconForegroundColor,
                    backgroundColor = record.iconBackgroundColor,
                    size = 48.dp,
                    fallbackHash = record.remoteIdentityHash.callHistoryIdenticonBytes(),
                )
                Column {
                    Text(displayName, style = MaterialTheme.typography.headlineSmall)
                    SelectionContainer {
                        Text(
                            record.remoteIdentityHash,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item { DetailRow(stringResource(R.string.call_details_direction), callDirectionLabel(record.direction)) }
        item {
            DetailRow(
                stringResource(R.string.call_details_outcome),
                if (recoveryPending) stringResource(R.string.call_details_recovery_pending) else callOutcomeLabel(record.outcome),
            )
        }
        item { DetailRow(stringResource(R.string.call_details_profile), localizedCodecProfileLabel(record.codecProfileCode)) }
        item { DetailRow(stringResource(R.string.call_details_attempted), dateTime.format(Date(record.attemptedAt))) }
        item {
            DetailRow(
                stringResource(R.string.call_details_ringing),
                record.ringingAt?.let { dateTime.format(Date(it)) } ?: stringResource(R.string.call_details_not_reached),
            )
        }
        item {
            DetailRow(
                stringResource(R.string.call_details_connected),
                connectedAt?.let { dateTime.format(Date(it)) } ?: stringResource(R.string.call_details_not_reached),
            )
        }
        item {
            DetailRow(
                stringResource(R.string.call_details_ended),
                endedAt?.let { dateTime.format(Date(it)) } ?: stringResource(R.string.call_details_not_reached),
            )
        }
        if (connectedAt != null && (endedAt != null || isActiveAttempt)) {
            item {
                DetailRow(
                    stringResource(R.string.call_details_duration),
                    formatDetailsDuration((endedAt ?: now) - connectedAt),
                )
            }
        }
        failureReason?.let { reason ->
            item { DetailRow(stringResource(R.string.call_details_failure_reason), safeFailureReason(reason)) }
        }
        if (record.inferredEnding) {
            item { Text(stringResource(R.string.call_details_inferred_ending), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { HorizontalDivider() }
        item {
            DetailRow(
                stringResource(R.string.call_details_local_identity),
                listOfNotNull(record.localIdentityName, record.localIdentityHash).joinToString("\n"),
                monospace = true,
            )
        }
        item { DetailRow(stringResource(R.string.call_details_remote_identity), record.remoteIdentityHash, monospace = true) }
        if (actionError) {
            item { Text(stringResource(R.string.call_details_action_error), color = MaterialTheme.colorScheme.error) }
        }
        if (runtimeBlockRecoveryRequired) {
            item { Text(stringResource(R.string.call_details_runtime_recovery_required), color = MaterialTheme.colorScheme.error) }
        }
        if (!localIdentityMatches) {
            item { Text(stringResource(R.string.call_details_identity_changed), color = MaterialTheme.colorScheme.error) }
        }
        item {
            val canCall =
                blockState == BlockLookupState.UNBLOCKED &&
                    canStartCall &&
                    record.remoteIdentityHash.matches(Regex("^[0-9a-fA-F]{32}$"))
            Button(
                onClick = { onCallAgain(record.remoteIdentityHash, record.codecProfileCode ?: -1) },
                enabled = canCall,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.call_details_call_again)) }
        }
        destination?.let {
            item {
                OutlinedButton(
                    onClick = onViewPeer,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.call_details_view_peer)) }
            }
        }
        item {
            OutlinedButton(
                onClick = onToggleBlocked,
                enabled = localIdentityMatches && blockState in setOf(BlockLookupState.BLOCKED, BlockLookupState.UNBLOCKED),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (blockState == BlockLookupState.BLOCKED) R.string.call_details_unblock else R.string.call_details_block,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    val useStackedLayout = LocalDensity.current.fontScale >= 1.5f
    if (useStackedLayout) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (monospace) {
                SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) }
            } else {
                Text(value)
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (monospace) {
                SelectionContainer(modifier = Modifier.weight(0.6f)) {
                    Text(value, fontFamily = FontFamily.Monospace)
                }
            } else {
                Text(value, modifier = Modifier.weight(0.6f))
            }
        }
    }
}

@Composable
private fun callDirectionLabel(direction: String): String =
    stringResource(if (direction == "INCOMING") R.string.call_direction_incoming else R.string.call_direction_outgoing)

@Composable
private fun callOutcomeLabel(outcome: String?): String =
    stringResource(
        when (outcome) {
            null -> R.string.call_outcome_in_progress
            "CONNECTED_ENDED" -> R.string.call_outcome_connected_ended
            "MISSED_INCOMING" -> R.string.call_outcome_missed
            "DECLINED_LOCAL" -> R.string.call_outcome_declined
            "REJECTED_REMOTE" -> R.string.call_outcome_rejected
            "BUSY_REMOTE" -> R.string.call_outcome_busy
            "CANCELLED_LOCAL" -> R.string.call_outcome_cancelled
            "NOT_CONNECTED" -> R.string.call_outcome_not_connected
            "FAILED" -> R.string.call_outcome_failed
            "INTERRUPTED" -> R.string.call_outcome_interrupted
            else -> R.string.call_outcome_unknown
        },
    )

@Composable
private fun safeFailureReason(reason: String): String =
    stringResource(
        when (reason) {
            "NETWORK_UNAVAILABLE" -> R.string.call_failure_network_unavailable
            "MICROPHONE_PERMISSION_DENIED" -> R.string.call_failure_microphone_permission
            "LOCAL_IDENTITY_UNAVAILABLE" -> R.string.call_failure_local_identity_unavailable
            "ANOTHER_CALL_ACTIVE" -> R.string.call_failure_another_call_active
            "INVALID_PEER_IDENTITY" -> R.string.call_failure_invalid_peer
            "SERVICE_STARTUP_FAILURE" -> R.string.call_failure_service_startup
            "UNKNOWN_PREREQUISITE_FAILURE" -> R.string.call_failure_unknown
            else -> R.string.call_failure_unknown
        },
    )

@Composable
private fun formatDetailsDuration(durationMs: Long): String {
    if (durationMs < 0L) return stringResource(R.string.call_duration_unavailable)
    val seconds = durationMs / 1000L
    return stringResource(R.string.call_duration_minutes_seconds, seconds / 60L, seconds % 60L)
}
