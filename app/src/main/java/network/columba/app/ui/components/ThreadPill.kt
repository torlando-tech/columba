package network.columba.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.model.ThreadPillUi

/**
 * Header thread control (threads-spec §5.1): the single thread control on the
 * message screen. Shows the open target's title — `Main` or the thread's title
 * — with an unread dot and a chevron; tapping it opens the threads sheet.
 *
 * Rendered as an [AssistChip]: M3's compact auxiliary-action primitive. Its
 * outline (no container fill) reads as a control in the app bar instead of
 * borrowing the message-bubble container colour the surface pill used.
 *
 * The control is the fixed, always-visible answer to "which thread am I in?",
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

    // Chip content is 32dp tall; minimumInteractiveComponentSize pads the
    // layout out to M3's 48dp touch floor without inflating the chip itself
    // (issue 07).
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        },
        leadingIcon =
            if (pill.unreadCount > 0) {
                {
                    Icon(
                        imageVector = Icons.Default.Circle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(8.dp),
                    )
                }
            } else {
                null
            },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier =
            modifier
                .widthIn(max = 240.dp)
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = description },
    )
}
