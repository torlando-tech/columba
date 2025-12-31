package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A selectable row displaying a contact with checkbox and avatar.
 *
 * @param displayName The contact's display name
 * @param destinationHash The contact's destination hash (used for identicon fallback)
 * @param isSelected Whether this contact is currently selected
 * @param onSelectionChanged Called when the selection state changes
 * @param iconName Optional icon name for ProfileIcon
 * @param iconForegroundColor Optional foreground color (hex RGB) for ProfileIcon
 * @param iconBackgroundColor Optional background color (hex RGB) for ProfileIcon
 * @param modifier Optional modifier for the row
 */
@Composable
fun ContactSelectionRow(
    displayName: String,
    destinationHash: String,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    iconName: String? = null,
    iconForegroundColor: String? = null,
    iconBackgroundColor: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onSelectionChanged(!isSelected) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onSelectionChanged,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Use destinationHash bytes for identicon fallback
        val hashBytes =
            destinationHash.chunked(2)
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()

        ProfileIcon(
            iconName = iconName,
            foregroundColor = iconForegroundColor,
            backgroundColor = iconBackgroundColor,
            size = 40.dp,
            fallbackHash = hashBytes,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
