package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.model.ThreadPillUi

/**
 * Header pill (threads-spec §5.1): the single thread control on the message
 * screen. Shows the open target's title — `Main` or the thread's title — with
 * an unread dot and a chevron; tapping it opens the threads sheet.
 *
 * The pill is the fixed, always-visible answer to "which thread am I in?",
 * replacing the scroll-position-dependent chip bar. Long-press is deliberately
 * unbound (reserved for a future action; see spec §5.1).
 *
 * Width is capped so a long thread title cannot push the message list off
 * screen; the label ellipsizes instead.
 */
@Composable
fun ThreadPill(
    pill: ThreadPillUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        if (pill.isMain) {
            stringResource(R.string.thread_chip_main)
        } else {
            pill.title ?: stringResource(R.string.thread_untitled)
        }
    val description =
        if (pill.unreadCount > 0) {
            stringResource(R.string.thread_pill_switch_unread, label, pill.unreadCount)
        } else {
            stringResource(R.string.thread_pill_switch, label)
        }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .widthIn(max = 240.dp)
                .semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (pill.unreadCount > 0) {
                Icon(
                    imageVector = Icons.Default.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(8.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
