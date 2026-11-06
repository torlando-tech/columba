package network.columba.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.model.CodecProfile

/**
 * Modal bottom sheet for switching the LXST audio profile during an active
 * call (LXST Profile Negotiation).
 *
 * Material 3 puts a long list of choices that carry descriptions into a modal
 * bottom sheet rather than an anchored menu, and a single-select list item is
 * the M3 pattern for choosing exactly one of them. The sheet exists as the
 * two-step affordance: opening it takes a deliberate tap, so holding the phone
 * to the ear cannot switch codecs by accident, and one tap on a row applies
 * that profile instead of cycling through the rest.
 *
 * Selection applies on tap and the sheet is dismissed by the caller; there is
 * no confirm step (see `CodecProfile` for the ids and labels).
 *
 * @param activeProfileCode LXST id of the profile the call is currently using,
 *   or `null` when the backend has not reported one yet.
 * @param onProfileSelected Invoked with the tapped profile; the caller is
 *   responsible for switching and closing the sheet.
 * @param onDismiss Invoked on scrim tap, swipe-down, or back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun CallProfileSheet(
    activeProfileCode: Int?,
    onProfileSelected: (CodecProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        CallProfileSheetContent(
            activeProfileCode = activeProfileCode,
            onProfileSelected = onProfileSelected,
        )
    }
}

/**
 * Sheet body: title, subtitle, and one single-select list item per profile.
 *
 * Split out of [CallProfileSheet] so the row and selection behaviour can be
 * tested without the modal window.
 *
 * The rows scroll inside the sheet: eight profiles plus the header are taller
 * than a short-screen sheet, so the sheet opens expanded rather than
 * half-height and the list scrolls internally.
 */
@Composable
internal fun CallProfileSheetContent(
    activeProfileCode: Int?,
    onProfileSelected: (CodecProfile) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.call_profile_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        Text(
            text = stringResource(R.string.call_profile_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )

        HorizontalDivider()

        CodecProfile.entries.forEach { profile ->
            CallProfileRow(
                profile = profile,
                isSelected = profile.code == activeProfileCode,
                onClick = { onProfileSelected(profile) },
            )
        }
    }
}

/**
 * One single-select profile row: label, one-line codec description, and a
 * trailing radio button.
 *
 * The click target is the whole row (`selectable` with radio role), which
 * keeps the 48dp minimum height and makes the row a single accessibility
 * node; the radio itself has no own click handler so tapping it does not
 * double-fire.
 */
@Composable
private fun CallProfileRow(
    profile: CodecProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(profile.labelRes)) },
        supportingContent = { Text(stringResource(profile.descriptionRes)) },
        overlineContent =
            if (profile.isExperimental) {
                {
                    Text(
                        text = stringResource(R.string.call_profile_experimental),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            RadioButton(
                selected = isSelected,
                onClick = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor =
                if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
        ),
        modifier =
            Modifier
                .selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .testTag("profileOption_${profile.abbreviation}"),
    )
}
