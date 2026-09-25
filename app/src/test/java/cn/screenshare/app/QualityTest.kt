package cn.screenshare.app
import org.junit.Assert.*
import org.junit.Test

class QualityTest {
    @Test fun sourceAspectAndNoUpscaling() {
        assertEquals(1080 to 1920, Quality.UHD.captureSize(1080,1920))
        assertEquals(2160 to 3840, Quality.UHD.captureSize(2160,3840))
        assertEquals(3840 to 1600, Quality.UHD.captureSize(7680,3200))
        assertEquals(720 to 1280, Quality(1280,720).captureSize(1080,1920))
    }
    @Test fun targetsAreIndependent() {
        val q=Quality.UHD.copy(fps=25,bitrate=17_500_000)
        assertEquals(3840,q.longEdge);assertEquals(25,q.fps);assertEquals(17_500_000,q.bitrate)
        assertEquals(1,Quality(fps=1).fps);assertEquals(60,Quality(fps=60).fps)
    }
    @Test fun uhd60KeepsResolutionAndUsesMotionEncoding() {
        val q = Quality.UHD60
        assertEquals(3840 to 2160, q.captureSize(3840,2160))
        assertEquals(1080 to 1920, q.captureSize(1080,1920))
        assertEquals(60, q.fps); assertEquals(40_000_000, q.bitrate)
        assertEquals(VideoPriority.RESOLUTION, q.priority)
        assertFalse(q.detailContent)
        assertTrue(Quality.UHD.detailContent)
        assertFalse(Quality.UHD.copy(fps=31).detailContent)
        assertFalse(Quality.SMOOTH.detailContent)
    }
    @Test fun rejectsUnsupportedInputs() {
        listOf<()->Quality>({Quality(fps=0)},{Quality(fps=61)},{Quality(longEdge=3841)},{Quality(shortEdge=0)},{Quality(bitrate=80_000_001)}).forEach {
            try { it();fail("invalid accepted") } catch(_:IllegalArgumentException) {}
        }
    }
}
