package network.columba.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import network.columba.app.R
import network.columba.app.ui.model.CodecProfile

@Composable
internal fun localizedCodecProfileLabel(profileCode: Int?): String {
    val profile = profileCode?.let(CodecProfile::fromCode)
        ?: return stringResource(R.string.call_outcome_unknown)
    return stringResource(
        when (profile) {
            CodecProfile.BANDWIDTH_ULTRA_LOW -> R.string.codec_profile_bandwidth_ultra_low
            CodecProfile.BANDWIDTH_VERY_LOW -> R.string.codec_profile_bandwidth_very_low
            CodecProfile.BANDWIDTH_LOW -> R.string.codec_profile_bandwidth_low
            CodecProfile.QUALITY_MEDIUM -> R.string.codec_profile_quality_medium
            CodecProfile.QUALITY_HIGH -> R.string.codec_profile_quality_high
            CodecProfile.QUALITY_MAX -> R.string.codec_profile_quality_max
            CodecProfile.LATENCY_LOW -> R.string.codec_profile_latency_low
            CodecProfile.LATENCY_ULTRA_LOW -> R.string.codec_profile_latency_ultra_low
        },
    )
}

internal fun String.callHistoryIdenticonBytes(): ByteArray =
    if (matches(Regex("^[0-9a-fA-F]{32}$"))) {
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } else {
        encodeToByteArray()
    }
