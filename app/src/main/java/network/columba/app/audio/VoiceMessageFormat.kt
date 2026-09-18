package network.columba.app.audio

import androidx.annotation.StringRes
import network.columba.app.R
import network.columba.app.rns.api.util.LxmfFields
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.recording.RecordingConfig

enum class VoiceMessageFormat(
    val wireMode: Int,
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val recordingConfig: RecordingConfig? = null,
    val codec2Mode: Int? = null,
) {
    CODEC2_1200(
        wireMode = LxmfFields.AM_CODEC2_1200,
        displayNameRes = R.string.voice_message_quality_codec2_1200,
        descriptionRes = R.string.voice_message_quality_codec2_1200_description,
        codec2Mode = Codec2.CODEC2_1200,
    ),
    CODEC2_2400(
        wireMode = LxmfFields.AM_CODEC2_2400,
        displayNameRes = R.string.voice_message_quality_codec2_2400,
        descriptionRes = R.string.voice_message_quality_codec2_2400_description,
        codec2Mode = Codec2.CODEC2_2400,
    ),
    CODEC2_3200(
        wireMode = LxmfFields.AM_CODEC2_3200,
        displayNameRes = R.string.voice_message_quality_codec2_3200,
        descriptionRes = R.string.voice_message_quality_codec2_3200_description,
        codec2Mode = Codec2.CODEC2_3200,
    ),
    OPUS_MEDIUM(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayNameRes = R.string.voice_message_quality_medium,
        descriptionRes = R.string.voice_message_quality_medium_description,
        recordingConfig = RecordingConfig(sampleRateHz = 24_000, channelCount = 1, bitRateBps = 8_000),
    ),
    OPUS_HIGH(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayNameRes = R.string.voice_message_quality_high,
        descriptionRes = R.string.voice_message_quality_high_description,
        recordingConfig = RecordingConfig(sampleRateHz = 48_000, channelCount = 1, bitRateBps = 16_000),
    ),
    OPUS_MAXIMUM(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayNameRes = R.string.voice_message_quality_maximum,
        descriptionRes = R.string.voice_message_quality_maximum_description,
        recordingConfig = RecordingConfig(sampleRateHz = 48_000, channelCount = 2, bitRateBps = 32_000),
    ),
    ;

    val isCodec2: Boolean get() = codec2Mode != null

    companion object {
        val DEFAULT = OPUS_MEDIUM
        val OUTBOUND_OPTIONS = entries.toList()
    }
}
