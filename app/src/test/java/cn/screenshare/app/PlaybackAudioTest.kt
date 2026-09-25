package cn.screenshare.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioTest {
    private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }

    @Test fun mixesBothSourcesAndSaturatesWithoutWrapping() {
        val data = pcm(100, -100, 30000, -30000)
        mixPlaybackPcm(data, shortArrayOf(200, -200, 10000, -10000), 4)
        assertEquals(listOf(300, -300, 32767, -32768), (0..3).map { data.getShort(it * 2).toInt() })
        assertEquals(8, data.position())
    }
    @Test fun microphoneMutePreservesPlaybackAndPartialReadDoesNotReplayOldSamples() {
        val data = pcm(0, 0, 50, -50)
        mixPlaybackPcm(data, shortArrayOf(1200, -1200, 9000, 9000), 2)
        assertEquals(listOf(1200, -1200, 50, -50), (0..3).map { data.getShort(it * 2).toInt() })
    }
    @Test fun noPlaybackSamplesLeavesMicrophoneUnchanged() {
        val data = pcm(111, -222)
        mixPlaybackPcm(data, shortArrayOf(300, 400), 0)
        assertEquals(111, data.getShort(0).toInt())
        assertEquals(-222, data.getShort(2).toInt())
    }
}
