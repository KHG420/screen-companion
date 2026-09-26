package cn.screenshare.app

import org.junit.Assert.*
import org.junit.Test

class VideoLoadTest {
    @Test fun reducesQuicklyAndNeedsSixHealthyIntervalsToRecover() {
        val busy = VideoLoad().next(2.0,60.0,2.0,200.0,.1,"cpu",30)
        assertEquals(VideoLoad(1),busy)
        assertEquals(VideoLoad(2),busy.next(2.0,60.0,.1,200.0,40.0,"bandwidth",30))
        var load=VideoLoad(2)
        repeat(5) { load=load.next(2.0,60.0,.1,200.0,.1,"none",30) }
        assertEquals(2,load.level)
        assertEquals(VideoLoad(1),load.next(2.0,60.0,.1,200.0,.1,"none",30))
    }
    @Test fun stillScreensMissingSamplesAndCounterResetsDoNotReduceQuality() {
        val load=VideoLoad(1,5)
        assertEquals(VideoLoad(1),load.next(2.0,0.0,0.0,0.0,0.0,"cpu",30))
        assertEquals(VideoLoad(1),load.next(10.0,60.0,.1,200.0,.1,"cpu",30))
        assertEquals(VideoLoad(1),load.next(2.0,60.0,-1.0,200.0,.1,"cpu",30))
        assertEquals(VideoLoad(1),load.next(2.0,Double.NaN,.1,200.0,.1,"cpu",30))
        assertEquals(VideoLoad(1),load.next(2.0,60.0,Double.NaN,200.0,.1,"none",30))
    }
    @Test fun respectsEveryManualLockAndKeepsBitrateAndTargets() {
        for(priority in VideoPriority.entries) for(resolutionLocked in listOf(false,true)) for(fpsLocked in listOf(false,true)) {
            val q=Quality.UHD60.copy(priority=priority,resolutionLocked=resolutionLocked,fpsLocked=fpsLocked)
            for(level in 0..2) {
                val actual=q.withLoad(level)
                if(resolutionLocked) assertEquals(q.longEdge,actual.longEdge)
                if(fpsLocked) assertEquals(q.fps,actual.fps)
                if(resolutionLocked && fpsLocked) assertEquals(q,actual)
                assertEquals(q.bitrate,actual.bitrate)
                assertTrue(actual.fps<=q.fps)
                assertEquals(0,actual.longEdge%2)
            }
        }
        assertEquals(1,Quality(fps=1).withLoad(2).fps)
        assertEquals(30,Quality.DEFAULT.fps)
        assertEquals(20,Quality.DEFAULT.withLoad(1).fps)
        assertEquals(10,Quality.DEFAULT.withLoad(2).fps)
    }
    @Test fun cameraHasBoundedCaptureFormats() {
        assertEquals(Triple(1280,720,30),cameraFormat(0))
        assertEquals(Triple(960,540,24),cameraFormat(1))
        assertEquals(Triple(640,360,15),cameraFormat(2))
    }
}
