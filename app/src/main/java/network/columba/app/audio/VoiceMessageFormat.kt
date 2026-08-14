package network.columba.app.audio

import network.columba.app.rns.api.util.LxmfFields
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.recording.RecordingConfig

enum class VoiceMessageFormat(
    val wireMode: Int,
    val displayName: String,
    val description: String,
    val recordingConfig: RecordingConfig? = null,
    val codec2Mode: Int? = null,
) {
    CODEC2_1200(
        wireMode = LxmfFields.AM_CODEC2_1200,
        displayName = "Codec2 1200",
        description = "Very low bandwidth voice",
        codec2Mode = Codec2.CODEC2_1200,
    ),
    CODEC2_2400(
        wireMode = LxmfFields.AM_CODEC2_2400,
        displayName = "Codec2 2400",
        description = "Low bandwidth voice",
        codec2Mode = Codec2.CODEC2_2400,
    ),
    CODEC2_3200(
        wireMode = LxmfFields.AM_CODEC2_3200,
        displayName = "Codec2 3200",
        description = "Clearer speech at low bandwidth",
        codec2Mode = Codec2.CODEC2_3200,
    ),
    OPUS_MEDIUM(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayName = "Medium Quality",
        description = "Opus 8 kbps mono - good balance of quality and bandwidth",
        recordingConfig = RecordingConfig(sampleRateHz = 24_000, channelCount = 1, bitRateBps = 8_000),
    ),
    OPUS_HIGH(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayName = "High Quality",
        description = "Opus 16 kbps mono - higher fidelity audio",
        recordingConfig = RecordingConfig(sampleRateHz = 48_000, channelCount = 1, bitRateBps = 16_000),
    ),
    OPUS_MAXIMUM(
        wireMode = LxmfFields.AM_OPUS_OGG,
        displayName = "Maximum Quality",
        description = "Opus 32 kbps stereo - best audio, requires more bandwidth",
        recordingConfig = RecordingConfig(sampleRateHz = 48_000, channelCount = 2, bitRateBps = 32_000),
    ),
    ;

    val isCodec2: Boolean get() = codec2Mode != null

    companion object {
        val DEFAULT = OPUS_MEDIUM
        val OUTBOUND_OPTIONS = entries.toList()
    }
}
