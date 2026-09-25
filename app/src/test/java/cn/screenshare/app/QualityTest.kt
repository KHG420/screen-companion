package cn.screenshare.app
import org.junit.Assert.*
import org.junit.Test

class QualityTest {
    @Test fun defaultKeepsTallPhonePixelsAndPrioritizesClarity() {
        val q = CallState().quality
        assertEquals(Quality.DEFAULT, q)
        assertEquals(1080 to 2400, q.captureSize(1080,2400))
        assertEquals(2400 to 1080, q.captureSize(2400,1080))
        assertEquals(720 to 1600, q.captureSize(720,1600))
        assertEquals(VideoPriority.RESOLUTION, q.priority)
        assertTrue(q.detailContent)
        assertEquals(30, q.fps)
        assertEquals(12_000_000, q.bitrate)
        assertEquals(VideoPriority.BALANCED, Quality.AUTO.priority)
    }
    @Test fun explicitLocksOverrideEveryAutomaticPolicy() {
        for (priority in VideoPriority.entries) {
            val q=Quality.UHD60.copy(priority=priority)
            assertEquals(org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION,q.copy(resolutionLocked=true).degradationPreference)
            assertEquals(org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,q.copy(fpsLocked=true).degradationPreference)
            val locked=q.copy(resolutionLocked=true,fpsLocked=true)
            assertEquals(org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE_AND_RESOLUTION,locked.degradationPreference)
            assertEquals(locked.degradationPreference,locked.copy(bitrate=500_000).degradationPreference)
            assertEquals(3840 to 2160,locked.captureSize(3840,2160))
            assertEquals(60,locked.fps)
        }
        assertTrue(Quality.DEFAULT.resolutionLocked)
        assertFalse(Quality.SMOOTH.resolutionLocked)
    }
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
    @Test fun allFrameRatesPreserveIndependentSizeBitrateAndPriority() {
        for ((width, height) in listOf(320 to 180,640 to 360,1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160)) {
            for (priority in VideoPriority.entries) for (fps in 1..60) {
                val q=Quality(width,height,fps,500_000+fps*100_000,priority)
                assertEquals(width to height,q.captureSize(3840,2160))
                assertEquals(height to width,q.captureSize(2160,3840))
                assertEquals(fps,q.fps)
                assertEquals(500_000+fps*100_000,q.bitrate)
                assertEquals(priority == VideoPriority.RESOLUTION && fps <= 30,q.detailContent)
                assertEquals(q.captureSize(3840,2160),q.copy(bitrate=80_000_000).captureSize(3840,2160))
            }
        }
    }
}
