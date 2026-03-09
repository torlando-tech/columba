package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun GuardianCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    hasGuardian: Boolean,
    isLocked: Boolean,
    guardianName: String?,
    allowedContactCount: Int,
    onManageClick: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Parental Controls",
        icon = if (isLocked) Icons.Default.Lock else Icons.Default.Security,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        containerColor =
            if (isLocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        iconTint =
            if (isLocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    ) {
        // Status description
        if (hasGuardian) {
            Text(
                text =
                    if (isLocked) {
                        "Messaging restricted to $allowedContactCount allowed contact${if (allowedContactCount != 1) "s" else ""} plus your guardian."
                    } else {
                        "Paired with guardian${guardianName?.let { ": $it" } ?: ""}. Controls are currently inactive."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Set up parental controls to restrict messaging or manage a child's device remotely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Action button
        Button(
            onClick = onManageClick,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (isLocked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                ),
        ) {
            Icon(
                imageVector =
                    if (hasGuardian) {
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen
                    } else {
                        Icons.Default.Security
                    },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (hasGuardian) "Manage Parental Controls" else "Set Up Parental Controls",
            )
        }
    }
}
