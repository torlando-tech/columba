package network.columba.app.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import network.columba.app.R
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.viewmodel.ChatsSegment
import network.columba.app.viewmodel.VoiceHistoryState
import kotlinx.coroutines.awaitCancellation
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsSegmentSelector(
    selected: ChatsSegment,
    onSelected: (ChatsSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ChatsSegment.entries.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = selected == segment,
                onClick = { onSelected(segment) },
                shape = SegmentedButtonDefaults.itemShape(index, ChatsSegment.entries.size),
            ) {
                Text(
                    stringResource(
                        if (segment == ChatsSegment.TEXT) R.string.chats_segment_text else R.string.chats_segment_voice,
                    ),
                )
            }
        }
    }
}

@Composable
fun VoiceHistoryContent(
    state: VoiceHistoryState,
    onRecordClick: (CallHistoryRecord) -> Unit,
    onRetry: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading && state.records.isEmpty() -> VoiceHistoryMessage(
            title = stringResource(R.string.call_history_loading),
            showProgress = true,
            modifier = modifier,
        )
        state.hasError && state.records.isEmpty() -> VoiceHistoryMessage(
            title = stringResource(R.string.call_history_error_title),
            body = stringResource(R.string.call_history_error_body),
            onRetry = onRetry,
            modifier = modifier,
        )
        state.records.isEmpty() -> VoiceHistoryMessage(
            title = stringResource(R.string.call_history_empty_title),
            body = stringResource(R.string.call_history_empty_body),
            modifier = modifier,
        )
        else -> {
            val now = temporalRefreshSignal()
            val grouped = state.records.groupBy { dayKey(it.attemptedAt) }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.hasError) {
                    item(key = "history-error") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.call_history_error_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.call_history_retry))
                            }
                        }
                    }
                }
                grouped.forEach { (day, records) ->
                    item(key = "day-$day") {
                        Text(
                            text = dayLabel(records.first().attemptedAt, now),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(records, key = { it.callAttemptId }) { record ->
                        VoiceHistoryCard(
                            record = record,
                            isActiveCall = record.callAttemptId == state.activeCallAttemptId,
                            onClick = { onRecordClick(record) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceHistoryMessage(
    title: String,
    modifier: Modifier,
    body: String? = null,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        onRetry?.let {
            TextButton(onClick = it, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.call_history_retry))
            }
        }
    }
}

@Composable
private fun VoiceHistoryCard(
    record: CallHistoryRecord,
    isActiveCall: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by
        produceState(
            initialValue = System.currentTimeMillis(),
            key1 = record.connectedAt,
            key2 = record.endedAt,
            key3 = isActiveCall,
        ) {
            while (record.connectedAt != null && record.endedAt == null && isActiveCall) {
                kotlinx.coroutines.delay(1000L)
                value = System.currentTimeMillis()
            }
        }
    val direction = if (record.direction == "INCOMING") {
        stringResource(R.string.call_direction_incoming)
    } else {
        stringResource(R.string.call_direction_outgoing)
    }
    val outcome = outcomeLabel(record, isActiveCall)
    val peerName = record.displayName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.call_peer_fallback, record.remoteIdentityHash.take(8).uppercase(Locale.ROOT))
    val time = SimpleDateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(record.attemptedAt))
    val duration =
        if (record.endedAt == null && !isActiveCall) null else connectedDuration(record, now)
    val description =
        if (duration == null) {
            stringResource(R.string.call_history_card_description, peerName, direction, outcome, time)
        } else {
            stringResource(R.string.call_history_card_description_with_duration, peerName, direction, outcome, duration, time)
        }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .focusable()
            .clearAndSetSemantics { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileIcon(
                iconName = record.iconName,
                foregroundColor = record.iconForegroundColor,
                backgroundColor = record.iconBackgroundColor,
                size = 40.dp,
                fallbackHash = record.remoteIdentityHash.callHistoryIdenticonBytes(),
            )
            Icon(
                imageVector = if (record.direction == "INCOMING") {
                    Icons.AutoMirrored.Filled.CallReceived
                } else {
                    Icons.AutoMirrored.Filled.CallMade
                },
                contentDescription = null,
                tint = severityColor(callOutcomeSeverity(record.outcome, isActiveCall)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peerName,
                    modifier = Modifier.testTag("callHistoryPeerName"),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.call_history_direction_outcome, direction, outcome),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                duration?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                Text(
                    time,
                    modifier = Modifier.testTag("callHistoryTime"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun outcomeLabel(
    record: CallHistoryRecord,
    isActiveCall: Boolean,
): String =
    stringResource(
        when (record.outcome) {
            null -> if (isActiveCall) R.string.call_outcome_in_progress else R.string.call_outcome_recovering
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
private fun connectedDuration(
    record: CallHistoryRecord,
    now: Long,
): String? {
    val connectedAt = record.connectedAt ?: return null
    val endedAt = record.endedAt ?: now
    if (endedAt < connectedAt) return stringResource(R.string.call_duration_unavailable)
    val seconds = (endedAt - connectedAt) / 1000L
    return stringResource(R.string.call_duration_minutes_seconds, seconds / 60L, seconds % 60L)
}

internal enum class CallOutcomeSeverity { ACTIVE, NEUTRAL, WARNING, ERROR }

internal fun callOutcomeSeverity(
    outcome: String?,
    isActiveCall: Boolean,
): CallOutcomeSeverity =
    when (outcome) {
        null -> if (isActiveCall) CallOutcomeSeverity.ACTIVE else CallOutcomeSeverity.NEUTRAL
        "MISSED_INCOMING" -> CallOutcomeSeverity.ERROR
        "FAILED", "INTERRUPTED" -> CallOutcomeSeverity.WARNING
        else -> CallOutcomeSeverity.NEUTRAL
    }

@Composable
private fun severityColor(severity: CallOutcomeSeverity) =
    when (severity) {
        CallOutcomeSeverity.ACTIVE -> MaterialTheme.colorScheme.primary
        CallOutcomeSeverity.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        CallOutcomeSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        CallOutcomeSeverity.ERROR -> MaterialTheme.colorScheme.error
    }

private fun dayKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(timestamp))



@Composable
private fun dayLabel(
    timestamp: Long,
    now: Long,
): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    if (target.get(Calendar.ERA) == today.get(Calendar.ERA) &&
        target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        return stringResource(R.string.call_history_today)
    }
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (target.get(Calendar.ERA) == today.get(Calendar.ERA) &&
        target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        return stringResource(R.string.call_history_yesterday)
    }
    return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timestamp))
}

@Composable
internal fun temporalRefreshSignal(): Long {
    val context = androidx.compose.ui.platform.LocalContext.current
    val signal by
        produceState(initialValue = System.currentTimeMillis(), key1 = context) {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        value = System.currentTimeMillis()
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                awaitCancellation()
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
    return signal
}
