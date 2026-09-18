package network.columba.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import network.columba.app.R

internal sealed interface MessageStatusIndicatorModel {
    data class Glyph(val value: String) : MessageStatusIndicatorModel

    data object RelayUploading : MessageStatusIndicatorModel

    data object RelayStored : MessageStatusIndicatorModel

    data object None : MessageStatusIndicatorModel
}

internal fun getMessageStatusIndicator(status: String): MessageStatusIndicatorModel =
    when (status) {
        "pending" -> MessageStatusIndicatorModel.Glyph("○")
        "sent" -> MessageStatusIndicatorModel.Glyph("✓")
        "retrying_propagated" -> MessageStatusIndicatorModel.RelayUploading
        "propagated" -> MessageStatusIndicatorModel.RelayStored
        "delivered" -> MessageStatusIndicatorModel.Glyph("✓✓")
        "failed" -> MessageStatusIndicatorModel.Glyph("!")
        else -> MessageStatusIndicatorModel.None
    }

@Composable
internal fun MessageStatusIndicator(
    status: String,
    color: Color,
) {
    when (val indicator = getMessageStatusIndicator(status)) {
        is MessageStatusIndicatorModel.Glyph -> Text(
            text = indicator.value,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        MessageStatusIndicatorModel.RelayUploading -> Icon(
            imageVector = Icons.Default.CloudUpload,
            contentDescription = stringResource(R.string.message_status_sending_to_relay),
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        MessageStatusIndicatorModel.RelayStored -> Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = stringResource(R.string.message_status_stored_on_relay),
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        MessageStatusIndicatorModel.None -> Unit
    }
}
