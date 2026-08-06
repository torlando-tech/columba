package network.columba.app.audio

import android.media.AudioFormat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveformAccumulatorTest {
    @Test
    fun `waveform follows decoded pcm energy`() {
        val samples =
            ShortArray(200) { index ->
                if (index < 100) 1_000 else 20_000
            }
        val buffer =
            ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
                samples.forEach(::putShort)
                flip()
            }
        val accumulator = PcmWaveformAccumulator(durationMs = 2_000, barCount = 4)

        accumulator.add(
            buffer = buffer,
            presentationTimeUs = 0,
            sampleRate = 100,
            channelCount = 1,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )

        val levels = accumulator.levels()
        assertNotNull(levels)
        assertTrue(levels!![2] > levels[0])
        assertTrue(levels[3] > levels[1])
        assertTrue(levels.all { it in 0f..1f })
    }

    @Test
    fun `silent pcm has no fabricated waveform`() {
        val buffer = ByteBuffer.allocate(200).order(ByteOrder.nativeOrder())
        val accumulator = PcmWaveformAccumulator(durationMs = 1_000, barCount = 4)

        accumulator.add(
            buffer = buffer,
            presentationTimeUs = 0,
            sampleRate = 100,
            channelCount = 1,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )

        assertNull(accumulator.levels())
    }
}
