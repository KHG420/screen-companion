package cn.screenshare.app

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import java.nio.ByteBuffer
import java.nio.ByteOrder

// All public calls and callbacks are serialized onto the main thread by CallSession.
class RtcSession(
    private val context: Context,
    iceServers: List<PeerConnection.IceServer>,
    private val isHost: Boolean,
    private val signal: (String, JSONObject) -> Unit,
    private val remoteVideo: (VideoTrack) -> Unit,
    private val connectionChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val projectionStopped: () -> Unit,
    private val playbackFailed: () -> Unit,
) {
    val egl: EglBase = EglBase.create()
    private val handler = Handler(Looper.getMainLooper())
    private var disposed = false
    private val audioModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val peer: PeerConnection
    private var videoSender: RtpSender? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private val playbackLock = Any()
    private var playbackRecord: AudioRecord? = null
    private val playbackSamples = ShortArray(480) // 10 ms at the ADM input rate.
    private var quality = Quality.AUTO
    private val videoSamples = mutableMapOf<String, Triple<Double, Long, Long>>()
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { if (capturer != null) updateCaptureSize() }
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        audioModule = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(48_000)
            .setAudioBufferCallback { buffer, format, channels, rate, _, timestamp ->
                if (format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && rate == 48_000) {
                    synchronized(playbackLock) {
                        playbackRecord?.let { record ->
                            val count = record.read(playbackSamples, 0, min(playbackSamples.size, buffer.capacity() / 2), AudioRecord.READ_NON_BLOCKING)
                            if (count > 0) mixPlaybackPcm(buffer, playbackSamples, count)
                            else if (count < 0) {
                                stopPlaybackAudio()
                                onMain(playbackFailed)
                            }
                        }
                    }
                }
                timestamp
            }.createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = checkNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = onMain {
                signal("ice", JSONObject().put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit // Host controls offers; video direction is negotiated before capture.
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = onMain { (receiver.track() as? VideoTrack)?.let(remoteVideo) }
            override fun onTrack(transceiver: RtpTransceiver) = onMain { (transceiver.receiver.track() as? VideoTrack)?.let(remoteVideo) }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onMain { connectionChanged(state) }
        }))
        audioSource = factory.createAudioSource(MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        })
        audioTrack = factory.createAudioTrack("microphone", audioSource)
        peer.addTrack(audioTrack, listOf("call"))
        if (isHost) configureVideo(peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)))
        displays.registerDisplayListener(displayListener, handler)
    }
    private fun configureVideo(video: RtpTransceiver) {
        video.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        val hardwareH264 = HardwareVideoEncoderFactory(egl.eglBaseContext, true, false).supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }
        if (hardwareH264) {
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            video.setCodecPreferences(codecs.sortedBy { if (it.name.equals("H264", ignoreCase = true)) 0 else 1 })
        }
        videoSender = video.sender
    }
    private fun onMain(block: () -> Unit) { handler.post { if (!disposed) block() } }

    suspend fun offer(restart: Boolean = false) {
        if (disposed || peer.signalingState() != PeerConnection.SignalingState.STABLE) return
        val constraints = MediaConstraints().apply { if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        val description = createDescription(true, constraints)
        setDescription(description, local = true)
        signal("offer", JSONObject().put("sdp", description.description))
    }
    suspend fun acceptOffer(sdp: String) {
        setDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), local = false)
        // The answerer must use the transceiver created by the remote offer.
        // A separately pre-created slot is not automatically associated by libwebrtc.
        configureVideo(peer.transceivers.first { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO })
        flushIce()
        val answer = createDescription(false, MediaConstraints())
        setDescription(answer, local = true)
        applyEncoding()
        signal("answer", JSONObject().put("sdp", answer.description))
    }
    suspend fun acceptAnswer(sdp: String) { setDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false); flushIce(); applyEncoding() }
    fun addIce(data: JSONObject) {
        val candidate = IceCandidate(data.optString("sdpMid"), data.getInt("sdpMLineIndex"), data.getString("candidate"))
        if (peer.remoteDescription == null) pendingIce.add(candidate) else peer.addIceCandidate(candidate)
    }
    private fun flushIce() { pendingIce.forEach { peer.addIceCandidate(it) }; pendingIce.clear() }
    // Mute the microphone before mixing, so shared media remains audible.
    fun microphone(enabled: Boolean) { if (!disposed) audioModule.setMicrophoneMute(!enabled) }

    fun startScreen(data: Intent, selectedQuality: Quality) {
        check(capturer == null)
        quality = selectedQuality
        val screen = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() = onMain { if (capturer != null) projectionStopped() }
        })
        capturer = screen
        val helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext).also { captureHelper = it }
        val source = factory.createVideoSource(true).also { videoSource = it }
        screen.initialize(helper, context, source.capturerObserver)
        val track = factory.createVideoTrack("screen", source).also { localVideo = it }
        check(videoSender?.setTrack(track, false) == true) { "无法连接屏幕轨道" }
        val (width, height) = captureSize()
        source.adaptOutputFormat(width, height, quality.fps)
        screen.startCapture(width, height, quality.fps)
        startPlaybackAudio(checkNotNull(screen.mediaProjection))
        applyEncoding()
    }
    @Suppress("MissingPermission") // RECORD_AUDIO is granted before starting the call.
    private fun startPlaybackAudio(projection: MediaProjection) {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val record = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(3_840, AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setAudioPlaybackCaptureConfig(capture).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "无法初始化系统声音采集" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动系统声音采集" }
            synchronized(playbackLock) { playbackRecord = record }
        } catch (e: Exception) { record.release(); throw e }
    }
    private fun stopPlaybackAudio() = synchronized(playbackLock) {
        val record = playbackRecord ?: return@synchronized
        playbackRecord = null
        try { record.stop() } finally { record.release() }
    }
    fun setQuality(value: Quality) {
        val old = quality
        try {
            quality = value; updateCaptureSize(); applyEncoding()
        } catch (e: Exception) {
            quality = old
            val restored = runCatching { updateCaptureSize(); applyEncoding() }.isSuccess
            if (!restored) { stopScreen(); projectionStopped() }
            throw IllegalStateException(if (restored) "设备未接受该画质组合，已恢复原设置" else "画质恢复失败，已停止共享；语音仍可继续", e)
        }
    }
    @Suppress("DEPRECATION")
    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return quality.captureSize(metrics.widthPixels, metrics.heightPixels)
    }
    private fun updateCaptureSize() {
        if (capturer == null) return
        val (w,h) = captureSize()
        // ScreenCapturerAndroid ignores FPS; the source adapter enforces the capture frame limit.
        videoSource?.adaptOutputFormat(w,h,quality.fps)
        capturer?.changeCaptureFormat(w,h,quality.fps)
    }
    private fun applyEncoding() {
        val sender = videoSender ?: return
        val p = sender.parameters
        p.degradationPreference = when (quality.priority) {
            VideoPriority.BALANCED -> RtpParameters.DegradationPreference.BALANCED
            VideoPriority.RESOLUTION -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            VideoPriority.FRAMERATE -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        }
        p.encodings.forEach { it.maxBitrateBps = quality.bitrate; it.maxFramerate = quality.fps }
        if (p.encodings.isNotEmpty()) check(sender.setParameters(p)) { "编码器不支持此参数组合" }
    }
    fun stopScreen() {
        stopPlaybackAudio()
        val old = capturer ?: return
        capturer = null // Projection callback must not recursively stop this capture.
        videoSender?.setTrack(null, false)
        try { old.stopCapture() } finally {
            old.dispose(); localVideo?.dispose(); localVideo = null
            videoSource?.dispose(); videoSource = null; captureHelper?.dispose(); captureHelper = null
        }
    }
    fun stats(onStats: (String) -> Unit) {
        if (disposed) return
        peer.getStats { report ->
            val values = report.statsMap.values
            if (BuildConfig.DEBUG) {
                fun total(type: String, kind: String, field: String) = values.filter { it.type == type && (it.members["kind"] == kind || it.members["mediaType"] == kind) }.sumOf { (it.members[field] as? Number)?.toLong() ?: 0L }
                android.util.Log.d("ScreenShareStats", "audioIn=${total("inbound-rtp", "audio", "packetsReceived")} audioOut=${total("outbound-rtp", "audio", "packetsSent")} videoFrames=${total("inbound-rtp", "video", "framesDecoded")} videoOut=${total("outbound-rtp", "video", "framesEncoded")}")
            }
            val transport = values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
            val pair = transport?.members?.get("selectedCandidatePairId")?.let { report.statsMap[it.toString()] }
                ?: values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
            val local = pair?.members?.get("localCandidateId")?.let { report.statsMap[it.toString()] }
            val remote = pair?.members?.get("remoteCandidateId")?.let { report.statsMap[it.toString()] }
            val relay = local?.members?.get("candidateType") == "relay" || remote?.members?.get("candidateType") == "relay"
            val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)?.toInt()
            val lines = mutableListOf<String>()
            values.filter { it.type in listOf("outbound-rtp","inbound-rtp") && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }.forEach { row ->
                val m = row.members
                val sending = row.type == "outbound-rtp"
                val bytes = (m[if (sending) "bytesSent" else "bytesReceived"] as? Number)?.toLong() ?: 0L
                val frames = (m[if (sending) "framesEncoded" else "framesDecoded"] as? Number)?.toLong() ?: 0L
                val old = videoSamples.put(row.id, Triple(row.timestampUs, bytes, frames))
                val elapsed = old?.let { (row.timestampUs-it.first)/1_000_000.0 } ?: 0.0
                val fresh = elapsed > 0 && old != null && bytes >= old.second && frames >= old.third
                val width = (m["frameWidth"] as? Number)?.toInt()
                val height = (m["frameHeight"] as? Number)?.toInt()
                if (width != null && height != null && ((!sending && fresh && bytes > old!!.second) || (sending && capturer != null))) {
                    val rate = if (fresh) String.format(java.util.Locale.ROOT, "%.1f FPS · %.2f Mbps", (frames-old!!.third)/elapsed, (bytes-old.second)*8/elapsed/1e6) else "测量中"
                    val reason = when(m["qualityLimitationReason"]) { "cpu" -> " · 设备编码性能受限"; "bandwidth" -> " · 网络带宽受限"; else -> "" }
                    lines += "${if(sending) "发送" else "接收"} ${width}×${height} · ${rate}${reason}"
                }
            }
            if (capturer != null) {
                val (w,h) = captureSize()
                if (maxOf(w,h) < quality.longEdge && minOf(w,h) < quality.shortEdge) lines += "采集源 ${w}×${h}：低于目标，不放大冒充 4K"
            }
            onMain { onStats((if (pair == null) "正在检测连接" else (if (relay) "中转连接" else "直接连接") + (rtt?.let { " · 网络往返 ${it} ms" } ?: "")) +
                (if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n"))) }
        }
    }
    fun close() {
        if (disposed) return
        disposed = true
        displays.unregisterDisplayListener(displayListener)
        stopScreen(); peer.close(); peer.dispose()
        audioTrack.dispose(); audioSource.dispose(); factory.dispose(); audioModule.release()
        // Renderers detach on the next Compose frame, then release the shared EGL context.
        handler.postDelayed({ egl.release() }, 500)
    }
    private suspend fun createDescription(offer: Boolean, constraints: MediaConstraints): SessionDescription = suspendCancellableCoroutine { c ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (c.isActive) c.resume(sdp) }
            override fun onCreateFailure(error: String) { if (c.isActive) c.resumeWithException(IllegalStateException(error)) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        if (offer) peer.createOffer(observer, constraints) else peer.createAnswer(observer, constraints)
    }
    private suspend fun setDescription(sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { c ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() { if (c.isActive) c.resume(Unit) }
            override fun onSetFailure(error: String) { if (c.isActive) c.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }
        if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
    }
}

// Absolute accesses preserve the ADM buffer position; clamp rather than wrapping on overflow.
internal fun mixPlaybackPcm(microphone: ByteBuffer, playback: ShortArray, count: Int) {
    microphone.order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) {
        val sum = microphone.getShort(i * 2).toInt() + playback[i].toInt()
        microphone.putShort(i * 2, sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
}
