package network.columba.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.TransferPhase
import network.columba.app.rns.api.model.TransferProgressUpdate
import network.columba.app.service.SyncProgress
import kotlin.math.roundToInt

@Composable
fun MessageTransferProgress(
    update: TransferProgressUpdate,
    modifier: Modifier = Modifier,
) {
    val isTransferringResource = update.phase == TransferPhase.TRANSFERRING
    val currentAttempt = update.currentAttempt
    val maxAttempts = update.maxAttempts
    val label = when {
        isTransferringResource -> stringResource(R.string.transfer_transferring_resource)
        update.deliveryMethod == DeliveryMethod.PROPAGATED -> stringResource(R.string.transfer_sending_to_relay)
        update.deliveryMethod == DeliveryMethod.DIRECT &&
            currentAttempt != null &&
            currentAttempt > 1 &&
            maxAttempts != null -> stringResource(
                R.string.transfer_retrying_direct,
                currentAttempt,
                maxAttempts,
            )
        update.deliveryMethod == DeliveryMethod.DIRECT -> stringResource(R.string.transfer_sending_directly)
        else -> stringResource(R.string.transfer_sending)
    }
    if (!isTransferringResource) {
        Text(
            text = label,
            modifier = modifier.fillMaxWidth().testTag("message_transfer_status"),
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }
    val percent = (update.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val description = stringResource(R.string.transfer_progress_description, label, percent)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("message_transfer_progress")
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.transfer_percent, percent), style = MaterialTheme.typography.labelSmall)
        }
        LinearProgressIndicator(
            progress = { update.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().testTag("message_resource_progress_bar"),
        )
    }
}

@Composable
fun ConversationTransferTray(
    incomingTransfers: List<TransferProgressUpdate>,
    syncProgress: SyncProgress,
    modifier: Modifier = Modifier,
) {
    val relayProgress = (syncProgress as? SyncProgress.InProgress)
        ?.progress
        ?.takeIf { it < 1f }
    val directProgress = incomingTransfers.weightedProgress()
    val progress = relayProgress ?: directProgress ?: return
    val label = if (relayProgress != null) {
        stringResource(R.string.transfer_receiving_via_relay)
    } else {
        pluralStringResource(
            R.plurals.transfer_receiving_directly,
            incomingTransfers.size,
            incomingTransfers.size,
        )
    }
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    val description = stringResource(R.string.transfer_progress_description, label, percent)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("conversation_transfer_tray")
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.transfer_percent, percent), style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (relayProgress != null && incomingTransfers.isNotEmpty()) {
            Text(
                text = pluralStringResource(
                    R.plurals.transfer_direct_also_active,
                    incomingTransfers.size,
                    incomingTransfers.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun List<TransferProgressUpdate>.weightedProgress(): Float? {
    val weighted = filter { (it.totalBytes ?: 0L) > 0L }
    val total = weighted.sumOf { it.totalBytes ?: 0L }
    return when {
        isEmpty() -> null
        weighted.isEmpty() -> map { it.progress }.average().toFloat()
        total <= 0L -> null
        else -> weighted.sumOf { (it.progress * (it.totalBytes ?: 0L)).toDouble() }.div(total).toFloat()
    }
}
