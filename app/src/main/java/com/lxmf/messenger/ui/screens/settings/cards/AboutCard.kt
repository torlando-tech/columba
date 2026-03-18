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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.service.AppUpdateResult
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
    updateCheckResult: AppUpdateResult = AppUpdateResult.Idle,
    includePrereleaseUpdates: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    onSetIncludePrereleaseUpdates: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    CollapsibleSettingsCard(
        title = stringResource(R.string.about_card_title),
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
                contentDescription = stringResource(R.string.about_logo_content_description),
                modifier = Modifier.size(108.dp),
            )

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider()

            // Version Information
            InfoSection(title = stringResource(R.string.about_app_information)) {
                InfoRow(stringResource(R.string.about_version), systemInfo.appVersion)
                InfoRow(stringResource(R.string.about_build_number), systemInfo.appBuildCode.toString())
                InfoRow(stringResource(R.string.about_build_type), systemInfo.buildType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                InfoRow(stringResource(R.string.about_git_commit), systemInfo.gitCommitHash)
                InfoRow(stringResource(R.string.about_build_date), systemInfo.buildDate)
            }

            HorizontalDivider()

            // Device Information
            InfoSection(title = stringResource(R.string.about_device_information)) {
                InfoRow(stringResource(R.string.about_android_version), systemInfo.androidVersion)
                InfoRow(stringResource(R.string.about_api_level), systemInfo.apiLevel.toString())
                InfoRow(stringResource(R.string.about_device_model), systemInfo.deviceModel)
                InfoRow(stringResource(R.string.about_manufacturer), systemInfo.manufacturer)
            }

            HorizontalDivider()

            // Protocol Versions
            InfoSection(title = stringResource(R.string.about_protocol_versions)) {
                if (systemInfo.reticulumVersion != null) {
                    InfoRow(stringResource(R.string.about_reticulum), systemInfo.reticulumVersion)
                }
                if (systemInfo.lxmfVersion != null) {
                    InfoRow(stringResource(R.string.about_lxmf), systemInfo.lxmfVersion)
                }
                if (systemInfo.bleReticulumVersion != null) {
                    InfoRow(stringResource(R.string.about_ble_reticulum), systemInfo.bleReticulumVersion)
                }
            }

            HorizontalDivider()

            // Identity
            if (systemInfo.identityHash != null) {
                InfoSection(title = stringResource(R.string.about_identity)) {
                    InfoRow(stringResource(R.string.about_identity_hash), systemInfo.identityHash)
                }
                HorizontalDivider()
            }

            // Links
            InfoSection(title = stringResource(R.string.about_links_resources)) {
                LinkButton(stringResource(R.string.about_github_repository), "https://github.com/torlando-tech/columba", context)
                LinkButton(stringResource(R.string.about_report_issue), "https://github.com/torlando-tech/columba/issues", context)
                LinkButton(stringResource(R.string.about_reticulum_info), "https://reticulum.network/", context)
            }

            HorizontalDivider()

            // Legal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_mit_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "© 2025–${com.lxmf.messenger.BuildConfig.COPYRIGHT_YEAR} Columba Contributors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/torlando-tech/columba/blob/main/LICENSE"))
                        context.startActivity(intent)
                    },
                ) {
                    Text(stringResource(R.string.about_view_license), style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            // Attribution
            Text(
                text = stringResource(R.string.about_built_with),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.about_reticulum_credit), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_lxmf_credit), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_material_design), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_jetpack_compose), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Updates
            InfoSection(title = stringResource(R.string.about_updates)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.about_include_prereleases),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = includePrereleaseUpdates,
                        onCheckedChange = onSetIncludePrereleaseUpdates,
                    )
                }

                val isChecking = updateCheckResult is AppUpdateResult.Checking
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.about_check_for_updates))
                }

                when (val result = updateCheckResult) {
                    is AppUpdateResult.UpToDate ->
                        Text(
                            text = stringResource(R.string.about_up_to_date, result.currentVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    is AppUpdateResult.UpdateAvailable ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.about_update_available, result.tagName),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.htmlUrl))
                                    context.startActivity(intent)
                                },
                            ) {
                                Text(stringResource(R.string.about_view_release))
                            }
                        }
                    is AppUpdateResult.Error ->
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {}
                }
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
                Text(stringResource(R.string.about_copy_system_info))
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
                Text(stringResource(R.string.crash_report_report_bug))
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
