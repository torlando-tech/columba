package com.lxmf.messenger.ui.screens.settings.cards

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard
import com.lxmf.messenger.util.SystemInfo
import java.util.Locale

@Composable
fun AboutCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    systemInfo: SystemInfo,
    onCopySystemInfo: () -> Unit,
    onReportBug: () -> Unit,
) {
    val context = LocalContext.current

    CollapsibleSettingsCard(
        title = "About",
        icon = Icons.Default.Info,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo and Header
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "Columba Logo",
                modifier = Modifier.size(108.dp),
            )

            Text(
                text = "Columba",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Native Android messaging app using Bluetooth LE, TCP, or RNode (LoRa) over LXMF and Reticulum",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider()

            // Version Information
            InfoSection(title = "App Information") {
                InfoRow("Version", systemInfo.appVersion)
                InfoRow("Build Number", systemInfo.appBuildCode.toString())
                InfoRow("Build Type", systemInfo.buildType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                InfoRow("Git Commit", systemInfo.gitCommitHash)
                InfoRow("Build Date", systemInfo.buildDate)
            }

            HorizontalDivider()

            // Device Information
            InfoSection(title = "Device Information") {
                InfoRow("Android Version", systemInfo.androidVersion)
                InfoRow("API Level", systemInfo.apiLevel.toString())
                InfoRow("Device Model", systemInfo.deviceModel)
                InfoRow("Manufacturer", systemInfo.manufacturer)
            }

            HorizontalDivider()

            // Protocol Versions
            InfoSection(title = "Protocol Versions") {
                if (systemInfo.reticulumVersion != null) {
                    InfoRow("Reticulum", systemInfo.reticulumVersion)
                }
                if (systemInfo.lxmfVersion != null) {
                    InfoRow("LXMF", systemInfo.lxmfVersion)
                }
                if (systemInfo.bleReticulumVersion != null) {
                    InfoRow("BLE-Reticulum", systemInfo.bleReticulumVersion)
                }
            }

            HorizontalDivider()

            // Identity
            if (systemInfo.identityHash != null) {
                InfoSection(title = "Identity") {
                    InfoRow("Identity Hash", systemInfo.identityHash)
                }
                HorizontalDivider()
            }

            // Links
            InfoSection(title = "Links & Resources") {
                LinkButton("GitHub Repository", "https://github.com/torlando-tech/columba", context)
                LinkButton("Report an Issue", "https://github.com/torlando-tech/columba/issues", context)
                LinkButton("About Reticulum", "https://reticulum.network/", context)
            }

            HorizontalDivider()

            // Legal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "MIT License",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "© 2025–${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Columba Contributors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/torlando-tech/columba/blob/main/LICENSE"))
                        context.startActivity(intent)
                    },
                ) {
                    Text("View License", style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            // Attribution
            Text(
                text = "Built With",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Reticulum by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("LXMF by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("Material Design 3", style = MaterialTheme.typography.bodySmall)
                Text("Jetpack Compose", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Copy Button
            OutlinedButton(
                onClick = onCopySystemInfo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy System Info")
            }

            // Report Bug Button
            OutlinedButton(
                onClick = onReportBug,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Report Bug")
            }
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LinkButton(
    label: String,
    url: String,
    context: Context,
) {
    TextButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}
