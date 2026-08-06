package network.columba.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

internal fun interface AudioWaveformReader {
    suspend fun read(bytes: ByteArray, durationMs: Int): List<Float>?
}

internal class AndroidPcmWaveformReader(
    context: Context,
) : AudioWaveformReader {
    private val cacheDir = context.applicationContext.cacheDir

    override suspend fun read(bytes: ByteArray, durationMs: Int): List<Float>? {
        if (durationMs !in 1..MAX_DURATION_MS) return null
        val file = File.createTempFile("voice_waveform_", ".ogg", cacheDir)
        return try {
            file.writeBytes(bytes)
            decode(file, durationMs)
        } finally {
            file.delete()
        }
    }

    // MediaCodec's input/output state machine is intentionally fail-closed at each buffer boundary.
    @Suppress("NestedBlockDepth", "ReturnCount")
    private suspend fun decode(file: File, durationMs: Int): List<Float>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val accumulator = PcmWaveformAccumulator(durationMs = durationMs, barCount = WAVEFORM_BARS)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleIterations = 0
            var outputFormat = inputFormat

            while (!outputEnded && idleIterations < MAX_IDLE_ITERATIONS) {
                currentCoroutineContext().ensureActive()
                var madeProgress = false
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return null
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        madeProgress = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return null
                            val view = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
                            view.position(bufferInfo.offset)
                            view.limit(bufferInfo.offset + bufferInfo.size)
                            accumulator.add(
                                buffer = view.slice().order(ByteOrder.nativeOrder()),
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                sampleRate = outputFormat.intValue(MediaFormat.KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE),
                                channelCount = outputFormat.intValue(MediaFormat.KEY_CHANNEL_COUNT, 1),
                                encoding = outputFormat.intValue(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT),
                            )
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        madeProgress = true
                    }
                }
                idleIterations = if (madeProgress) 0 else idleIterations + 1
            }
            return if (outputEnded) accumulator.levels() else null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intValue(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private companion object {
        const val WAVEFORM_BARS = 32
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MAX_DURATION_MS = 30 * 60 * 1_000
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_IDLE_ITERATIONS = 500
    }
}

internal class PcmWaveformAccumulator(
    private val durationMs: Int,
    private val barCount: Int,
) {
    private val energy = DoubleArray(barCount)
    private val sampleCounts = LongArray(barCount)

    @Suppress("ReturnCount")
    fun add(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        if (sampleRate <= 0 || channelCount <= 0) return
        val bytesPerSample = encoding.bytesPerSample() ?: return
        val frameCount = buffer.remaining() / (bytesPerSample * channelCount)
        if (frameCount <= 0) return
        val sampleStride = (frameCount / MAX_SAMPLES_PER_BUFFER).coerceAtLeast(1)
        var frame = 0
        while (frame < frameCount) {
            var frameEnergy = 0.0
            for (channel in 0 until channelCount) {
                val sampleOffset = (frame * channelCount + channel) * bytesPerSample
                val sample = buffer.normalizedSample(sampleOffset, encoding) ?: return
                frameEnergy += sample * sample
            }
            val timeUs = presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate
            val bucket = ((timeUs * barCount) / (durationMs * 1_000L)).toInt().coerceIn(0, barCount - 1)
            energy[bucket] += frameEnergy / channelCount
            sampleCounts[bucket] += 1
            frame += sampleStride
        }
    }

    fun levels(): List<Float>? {
        val rms =
            energy.indices.map { index ->
                if (sampleCounts[index] == 0L) 0.0 else sqrt(energy[index] / sampleCounts[index])
            }
        val peak = rms.maxOrNull()?.takeIf { it > 0.0 } ?: return null
        return rms.map { value ->
            if (value <= 0.0) {
                MIN_LEVEL
            } else {
                (MIN_LEVEL + (1f - MIN_LEVEL) * (value / peak).pow(0.7).toFloat()).coerceIn(MIN_LEVEL, 1f)
            }
        }
    }

    private fun Int.bytesPerSample(): Int? =
        when (this) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> null
        }

    private fun ByteBuffer.normalizedSample(offset: Int, encoding: Int): Double? =
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((get(offset).toInt() and 0xff) - 128) / 128.0
            AudioFormat.ENCODING_PCM_16BIT -> getShort(offset) / 32_768.0
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val raw =
                    (get(offset).toInt() and 0xff) or
                        ((get(offset + 1).toInt() and 0xff) shl 8) or
                        (get(offset + 2).toInt() shl 16)
                raw / 8_388_608.0
            }
            AudioFormat.ENCODING_PCM_32BIT -> getInt(offset) / 2_147_483_648.0
            AudioFormat.ENCODING_PCM_FLOAT -> getFloat(offset).toDouble().coerceIn(-1.0, 1.0)
            else -> null
        }

    private companion object {
        const val MAX_SAMPLES_PER_BUFFER = 4_096
        const val MIN_LEVEL = 0.12f
    }
}
