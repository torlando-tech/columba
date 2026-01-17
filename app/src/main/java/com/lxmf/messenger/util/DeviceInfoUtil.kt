package com.lxmf.messenger.util

import android.content.Context
import android.os.Build
import com.lxmf.messenger.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SystemInfo(
    val appVersion: String,
    val appBuildCode: Int,
    val buildType: String,
    val gitCommitHash: String,
    val buildDate: String,
    val androidVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val manufacturer: String,
    val identityHash: String?,
    val reticulumVersion: String?,
    val lxmfVersion: String?,
    val bleReticulumVersion: String?,
)

object DeviceInfoUtil {
    @Suppress("UnusedParameter") // Context reserved for future use
    fun getSystemInfo(
        context: Context,
        identityHash: String?,
        reticulumVersion: String?,
        lxmfVersion: String?,
        bleReticulumVersion: String?,
    ): SystemInfo {
        val buildDate =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(BuildConfig.BUILD_TIMESTAMP))

        return SystemInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appBuildCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            gitCommitHash = BuildConfig.GIT_COMMIT_HASH,
            buildDate = buildDate,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            identityHash = identityHash,
            reticulumVersion = reticulumVersion,
            lxmfVersion = lxmfVersion,
            bleReticulumVersion = bleReticulumVersion,
        )
    }

    fun formatForClipboard(info: SystemInfo): String {
        return buildString {
            appendLine("Columba ${info.appVersion} (${info.appBuildCode})")
            appendLine("Build: ${info.gitCommitHash} (${info.buildDate})")
            appendLine("Android ${info.androidVersion} (API ${info.apiLevel})")
            appendLine("Device: ${info.deviceModel} by ${info.manufacturer}")
            if (info.identityHash != null) {
                appendLine("Identity: ${info.identityHash}")
            }
            if (info.reticulumVersion != null) {
                appendLine("Reticulum: ${info.reticulumVersion}")
            }
            if (info.lxmfVersion != null) {
                appendLine("LXMF: ${info.lxmfVersion}")
            }
            if (info.bleReticulumVersion != null) {
                appendLine("BLE-Reticulum: ${info.bleReticulumVersion}")
            }
        }.trim()
    }
}
