package cn.screenshare.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

enum class VideoPriority(val label: String) {
    BALANCED("自动平衡"), RESOLUTION("清晰优先"), FRAMERATE("帧率优先")
}
data class Quality(
    val longEdge: Int = 1920, val shortEdge: Int = 1080, val fps: Int = 30,
    val bitrate: Int = 8_000_000, val priority: VideoPriority = VideoPriority.BALANCED,
    val resolutionLocked: Boolean = false, val fpsLocked: Boolean = false,
) {
    init {
        require(longEdge in 320..3840 && shortEdge in 180..2160 && longEdge >= shortEdge && longEdge % 2 == 0 && shortEdge % 2 == 0) { "长边 320–3840、短边 180–2160，均为偶数，且长边不小于短边" }
        require(fps in 1..60) { "帧率需为 1–60 的整数" }
        require(bitrate in 500_000..80_000_000) { "码率上限需为 0.5–80 Mbps" }
    }
    val degradationPreference: org.webrtc.RtpParameters.DegradationPreference get() = when {
        resolutionLocked && fpsLocked -> org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE_AND_RESOLUTION
        resolutionLocked -> org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        fpsLocked -> org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        else -> when (priority) {
            VideoPriority.BALANCED -> org.webrtc.RtpParameters.DegradationPreference.BALANCED
            VideoPriority.RESOLUTION -> org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            VideoPriority.FRAMERATE -> org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        }
    }
    val detailContent: Boolean get() = (resolutionLocked || (!fpsLocked && priority == VideoPriority.RESOLUTION)) && fps <= 30
    val controlLabel: String get() = when {
        resolutionLocked && fpsLocked -> "自定义 · 分辨率与帧率已锁定"
        resolutionLocked -> "分辨率已锁定 · 允许自动降帧"
        fpsLocked -> "帧率已锁定 · 允许自动降分辨率"
        else -> "自动调整 · ${priority.label}"
    }
    val label: String get() = "${longEdge}×${shortEdge} · ${fps} FPS"
    fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceAtLeast(2); val h = height.coerceAtLeast(2)
        val scale = minOf(1.0, longEdge.toDouble() / maxOf(w,h), shortEdge.toDouble() / minOf(w,h))
        return maxOf(2,(w*scale).toInt()/2*2) to maxOf(2,(h*scale).toInt()/2*2)
    }
    companion object {
        // Keep common 1080×2400 phone screens at native size and avoid automatic
        // spatial downscaling of text. Congestion control may still reduce FPS.
        val DEFAULT = Quality(2560,1440,30,12_000_000,VideoPriority.RESOLUTION, resolutionLocked = true)
        val AUTO = Quality()
        val CLEAR = Quality(2560,1440,24,12_000_000,VideoPriority.RESOLUTION)
        val SMOOTH = Quality(1920,1080,60,10_000_000,VideoPriority.FRAMERATE)
        val UHD = Quality(3840,2160,30,24_000_000,VideoPriority.RESOLUTION)
        val UHD60 = Quality(3840,2160,60,40_000_000,VideoPriority.RESOLUTION)
        val presets = listOf("高清默认" to DEFAULT, "均衡" to AUTO, "文字清晰" to CLEAR, "动态流畅" to SMOOTH, "4K 超清" to UHD, "4K · 60 帧" to UHD60)
    }
}
data class ChatMessage(val text: String, val mine: Boolean)
data class CallState(
    val cameraOn: Boolean = false,
    val localCamera: org.webrtc.VideoTrack? = null,
    val remoteCamera: org.webrtc.VideoTrack? = null,
    val remoteCameraOn: Boolean = false,
    val chatReady: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val chatRevision: Int = 0,
    val chatUnread: Int = 0,
    val active: Boolean = false,
    val roomId: String = "",
    val role: String = "",
    val status: String = "",
    val connected: Boolean = false,
    val peerPresent: Boolean = false,
    val muted: Boolean = false,
    val microphoneAvailable: Boolean = false,
    val sharing: Boolean = false,
    val remoteSharing: Boolean = false,
    val shareBusy: Boolean = false,
    val systemAudio: Boolean = false,
    val systemMuted: Boolean = false,
    val quality: Quality = Quality.DEFAULT,
    val error: String? = null,
    val notice: String? = null,
    val stats: String = "",
    val hasTurn: Boolean = false,
    val remoteTrack: org.webrtc.VideoTrack? = null,
    val routes: List<AudioRoute> = emptyList(),
    val selectedRoute: Int = -1,
)
data class AudioRoute(val id: Int, val label: String)

class CallService : Service() {
    companion object {
        val state = MutableStateFlow(CallState())
        @Volatile var current: CallService? = null
            private set
        const val START = "start"
        const val END = "end"
        const val SHARE = "share"
        const val STOP_SHARE = "stop-share"
        const val NOTIFICATION_ID = 41
    }
    lateinit var session: CallSession
        private set
    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("call", "通话与屏幕共享", NotificationManager.IMPORTANCE_LOW))
        session = CallSession(this, state)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                foreground(false)
                session.start(intent.getStringExtra("server").orEmpty(), intent.getStringExtra("room"))
            }
            SHARE -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("projection")
                if (data != null) session.startSharing(data)
            }
            STOP_SHARE -> session.stopSharing()
            END -> session.end()
        }
        return START_NOT_STICKY
    }
    fun foreground(sharing: Boolean, camera: Boolean = state.value.cameraOn) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 1, Intent(this, CallService::class.java).setAction(END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "call").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (sharing) "正在共享你的屏幕" else if (camera) "摄像头视频通话进行中" else "${getString(R.string.app_name)}通话进行中")
            .setContentText(if (sharing) "对方可以看到屏幕内容，点此返回控制" else if (camera) "摄像头正在使用，点此返回控制" else "点此返回画面、声音和聊天控制")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).addAction(0, "挂断", end)
        if (sharing) {
            val stop = PendingIntent.getService(this, 2, Intent(this, CallService::class.java).setAction(STOP_SHARE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notification.addAction(0, "停止共享", stop)
        }
        val hasMicrophone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val microphoneType = if (hasMicrophone && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val cameraType = if (camera && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or microphoneType or cameraType or (if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION_ID, notification.build(), type)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { session.end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { session.dispose(); if (current === this) current = null; super.onDestroy() }
}
