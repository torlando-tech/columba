package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.ui.theme.ColumbaTheme
import network.columba.app.ui.theme.OnWarningContainer
import network.columba.app.ui.theme.WarningContainer

/** Shows unresolved verification as a warning, and a failed signature as an error. */
@Composable
fun SignatureStatusChip(
    status: SignatureStatus?,
    modifier: Modifier = Modifier,
) {
    val label =
        when (status) {
            SignatureStatus.SOURCE_UNKNOWN -> "Sender identity unknown"
            SignatureStatus.INVALID -> "Invalid signature"
            SignatureStatus.UNVERIFIED -> "Signature not verified"
            SignatureStatus.VERIFIED, null -> return
        }
    val invalid = status == SignatureStatus.INVALID
    val contentColor = if (invalid) MaterialTheme.colorScheme.onErrorContainer else OnWarningContainer
    Surface(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                if (invalid) error("Signature verification failed")
            },
        color = if (invalid) MaterialTheme.colorScheme.errorContainer else WarningContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                // Decorative; adjacent text already conveys the meaning.
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(name = "Signature warnings, light", showBackground = true)
@Preview(name = "Signature warnings, dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SignatureStatusChipPreview() {
    ColumbaTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignatureStatusChip(SignatureStatus.SOURCE_UNKNOWN)
            SignatureStatusChip(SignatureStatus.UNVERIFIED)
            SignatureStatusChip(SignatureStatus.INVALID)
        }
    }
}
