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
import android.view.Display
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
    private val remoteCamera: (VideoTrack) -> Unit,
    private val peerMessage: (JSONObject) -> Unit,
    private val chatReady: (Boolean) -> Unit,
    private val cameraFailed: (String) -> Unit,
    private val connectionChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val projectionStopped: () -> Unit,
    private val playbackFailed: () -> Unit,
    private val microphoneFailed: () -> Unit,
) {
    val egl: EglBase = EglBase.create()
    private val handler = Handler(Looper.getMainLooper())
    private var disposed = false
    private val audioModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val peer: PeerConnection
    private var audioSender: RtpSender? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var recordingAllowed = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private var videoSender: RtpSender? = null
    private var cameraSender: RtpSender? = null
    private var screenMid: String? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var channel: DataChannel? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var captureDimensions: Pair<Int, Int>? = null
    private var adaptedFormat: Triple<Int, Int, Int>? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private val playbackLock = Any()
    private var playbackRecord: AudioRecord? = null
    private val playbackSamples = ShortArray(480) // 10 ms at the ADM input rate.
    @Volatile private var playbackMuted = false
    private var quality = Quality.DEFAULT
    private data class VideoSample(val time: Double, val bytes: Long, val frames: Long, val counters: Map<String, Double>)
    private val videoSamples = mutableMapOf<String, VideoSample>()
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY && capturer != null) updateCaptureSize()
        }
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        audioModule = JavaAudioDeviceModule.builder(context)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) = onMain(microphoneFailed)
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = onMain(microphoneFailed)
                override fun onWebRtcAudioRecordError(error: String) = onMain(microphoneFailed)
            })
            .setInputSampleRate(48_000)
            .setAudioBufferCallback { buffer, format, channels, rate, _, timestamp ->
                if (format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && rate == 48_000) {
                    synchronized(playbackLock) {
                        playbackRecord?.let { record ->
                            val count = record.read(playbackSamples, 0, min(playbackSamples.size, buffer.capacity() / 2), AudioRecord.READ_NON_BLOCKING)
                            if (count > 0 && !playbackMuted) mixPlaybackPcm(buffer, playbackSamples, count)
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
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
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
            override fun onDataChannel(channel: DataChannel) = onMain { bindChannel(channel) }
            override fun onRenegotiationNeeded() = Unit // Host controls offers; video direction is negotiated before capture.
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            override fun onTrack(transceiver: RtpTransceiver) = Unit // Bind both receiver tracks after remote SDP is applied.
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onMain { connectionChanged(state) }
        }))
        audioSource = factory.createAudioSource(MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        })
        audioTrack = factory.createAudioTrack("microphone", audioSource)
        // A sendrecv audio channel initializes AudioRecord even without a source.
        // Receive-only is required until Android grants recording permission.
        audioTransceiver = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(if (recordingAllowed) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY, listOf("call")))
        audioSender = audioTransceiver?.sender
        if (recordingAllowed) audioSender?.setTrack(audioTrack, false)
        if (isHost) {
            configureVideo(peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)))
            cameraSender = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)).sender
            bindChannel(peer.createDataChannel("companion-v1", DataChannel.Init()))
        }
        displays.registerDisplayListener(displayListener, handler)
    }
    private fun configureVideo(video: RtpTransceiver) {
        video.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        val hardwareH264 = HardwareVideoEncoderFactory(egl.eglBaseContext, true, true).supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }
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
        bindVideoSlots()
        flushIce()
        val answer = createDescription(false, MediaConstraints())
        setDescription(answer, local = true)
        applyEncoding()
        signal("answer", JSONObject().put("sdp", answer.description))
    }
    suspend fun acceptAnswer(sdp: String) { setDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false); bindVideoSlots(); flushIce(); applyEncoding() }
    private fun bindVideoSlots() {
        // libwebrtc disposes previous Java transceiver/sender/receiver wrappers on
        // getTransceivers(). Read once and refresh ALL retained references together.
        val transceivers = peer.transceivers
        audioTransceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        audioTransceiver?.direction = if (recordingAllowed) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        audioSender = audioTransceiver?.sender
        val videos = transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        val screen = videos.first()
        configureVideo(screen)
        screenMid = screen.mid
        (screen.receiver.track() as? VideoTrack)?.let(remoteVideo)
        videos.getOrNull(1)?.let {
            it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            cameraSender = it.sender
            (it.receiver.track() as? VideoTrack)?.let(remoteCamera)
        }
    }
    fun addIce(data: JSONObject) {
        val candidate = IceCandidate(data.optString("sdpMid"), data.getInt("sdpMLineIndex"), data.getString("candidate"))
        if (peer.remoteDescription == null) pendingIce.add(candidate) else peer.addIceCandidate(candidate)
    }
    private fun flushIce() { pendingIce.forEach { peer.addIceCandidate(it) }; pendingIce.clear() }
    // Mute the microphone before mixing, so shared media remains audible.
    fun microphone(enabled: Boolean) { if (!disposed) audioModule.setMicrophoneMute(!enabled) }
    fun recording(enabled: Boolean) {
        if (!disposed) {
            recordingAllowed = enabled
            audioTransceiver?.direction = if (enabled) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            audioSender?.setTrack(if (enabled) audioTrack else null, false)
            peer.setAudioRecording(enabled)
        }
    }
    suspend fun updateAudioDirection() {
        while (!disposed && peer.signalingState() != PeerConnection.SignalingState.STABLE) kotlinx.coroutines.delay(100)
        if (disposed) return
        if (isHost) offer() else signal("restart", JSONObject())
    }
    fun systemMuted(muted: Boolean) { playbackMuted = muted }
    fun hasSystemAudio(): Boolean = synchronized(playbackLock) { playbackRecord != null }

    private fun bindChannel(value: DataChannel) {
        if (value.label() != "companion-v1" || channel != null) { value.close(); value.dispose(); return }
        channel = value
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = onMain {
                val ready = value.state() == DataChannel.State.OPEN
                chatReady(ready)
                if (ready) sendCameraState()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 16384) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val message = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
                onMain {
                    when (message.optString("type")) {
                        "camera" -> if (message.opt("enabled") is Boolean) peerMessage(message)
                        "chat" -> {
                            val text = message.opt("text") as? String
                            if (text != null && text.isNotBlank() && text.length <= 2000) peerMessage(message)
                        }
                    }
                }
            }
        })
        if (value.state() == DataChannel.State.OPEN) { chatReady(true); sendCameraState() }
    }
    private fun sendData(message: JSONObject) {
        val c = checkNotNull(channel) { "文字通道尚未连接" }
        check(c.state() == DataChannel.State.OPEN && c.bufferedAmount() <= 65536) { "消息尚未发送，请稍后重试" }
        check(c.send(DataChannel.Buffer(ByteBuffer.wrap(message.toString().toByteArray(Charsets.UTF_8)), false))) { "消息发送失败，请重试" }
    }
    fun sendChat(text: String) {
        require(text.isNotBlank() && text.length <= 2000) { "消息需为 1–2000 个字符" }
        sendData(JSONObject().put("type", "chat").put("text", text))
    }
    private fun sendCameraState() {
        val c = channel ?: return
        if (c.state() != DataChannel.State.OPEN) return
        val data = JSONObject().put("type", "camera").put("enabled", cameraTrack != null).toString()
        c.send(DataChannel.Buffer(ByteBuffer.wrap(data.toByteArray(Charsets.UTF_8)), false))
    }
    fun startCamera(): VideoTrack {
        check(cameraSender != null && channel?.state() == DataChannel.State.OPEN) { "摄像头通道未就绪，请确认双方使用新版客户端" }
        check(cameraCapturer == null)
        val enumerator = Camera2Enumerator(context)
        val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("没有找到摄像头")
        val events = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String) = onMain { cameraFailed("摄像头启动失败或已断开") }
            override fun onCameraDisconnected() = onMain { cameraFailed("摄像头已断开") }
            override fun onCameraFreezed(error: String) = onMain { cameraFailed("摄像头无响应") }
            override fun onCameraOpening(name: String) = Unit
            override fun onFirstFrameAvailable() = Unit
            override fun onCameraClosed() = Unit
        }
        try {
            val capture = checkNotNull(enumerator.createCapturer(device, events)).also { cameraCapturer = it }
            val helper = SurfaceTextureHelper.create("CameraCapture", egl.eglBaseContext).also { cameraHelper = it }
            val source = factory.createVideoSource(false).also { cameraSource = it }
            capture.initialize(helper, context, source.capturerObserver)
            val track = factory.createVideoTrack("camera", source).also { cameraTrack = it }
            check(cameraSender?.setTrack(track, false) == true) { "无法连接摄像头轨道" }
            source.adaptOutputFormat(1280,720,30)
            capture.startCapture(1280,720,30)
            val parameters = cameraSender!!.parameters
            parameters.encodings.forEach { it.maxBitrateBps = 2_000_000; it.maxFramerate = 30 }
            if (parameters.encodings.isNotEmpty()) check(cameraSender!!.setParameters(parameters))
            sendCameraState()
            return track
        } catch (e: Exception) { stopCamera(); throw e }
    }
    fun switchCamera() {
        cameraCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = Unit
            override fun onCameraSwitchError(error: String) = onMain { cameraFailed("无法切换摄像头，请重新开启") }
        })
    }
    fun stopCamera() {
        val capture = cameraCapturer
        cameraCapturer = null
        cameraSender?.setTrack(null, false)
        try { capture?.stopCapture() } finally {
            capture?.dispose(); cameraTrack?.dispose(); cameraTrack = null
            cameraSource?.dispose(); cameraSource = null
            cameraHelper?.dispose(); cameraHelper = null
            if (!disposed) sendCameraState()
        }
    }

    fun startScreen(data: Intent, selectedQuality: Quality) {
        check(capturer == null)
        quality = selectedQuality
        val screen = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() = onMain { if (capturer != null) projectionStopped() }
        })
        capturer = screen
        val helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext).also { captureHelper = it }
        // WebRTC treats BALANCED + screencast as MAINTAIN_RESOLUTION. Use the
        // motion path for high FPS too: preserving resolution does not require a static-content encoder.
        val source = factory.createVideoSource(quality.detailContent).also { videoSource = it }
        screen.initialize(helper, context, source.capturerObserver)
        val track = factory.createVideoTrack("screen", source).also { localVideo = it }
        check(videoSender?.setTrack(track, false) == true) { "无法连接屏幕轨道" }
        val (width, height) = captureSize()
        source.adaptOutputFormat(width, height, quality.fps)
        adaptedFormat = Triple(width, height, quality.fps)
        // Apply the selected bitrate/FPS before capture can submit its first frame.
        applyEncoding()
        screen.startCapture(width, height, quality.fps)
        captureDimensions = width to height
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { startPlaybackAudio(checkNotNull(screen.mediaProjection)) }.onFailure { onMain(playbackFailed) }
    }
    @Suppress("MissingPermission") // The caller checks RECORD_AUDIO; denial keeps video-only sharing available.
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
        if (value == quality) return
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
        val screen = capturer ?: return
        val source = videoSource ?: return
        val dimensions = captureSize()
        val (w, h) = dimensions
        val format = Triple(w, h, quality.fps)
        // FPS is enforced by the source adapter; changing it must not reset the capture surface.
        if (adaptedFormat != format) {
            adaptedFormat = null // A failed native call must be retried when restoring the old quality.
            source.adaptOutputFormat(w, h, quality.fps)
            adaptedFormat = format
        }
        // WebRTC synchronously resizes/rebinds the virtual display even for identical dimensions.
        // Display state notifications and bitrate-only changes must leave that surface intact.
        if (captureDimensions != dimensions) {
            captureDimensions = null
            if (BuildConfig.DEBUG) android.util.Log.d("CaptureResizeProbe", "resize ${w}x${h} fps=${quality.fps}")
            screen.changeCaptureFormat(w, h, quality.fps)
            captureDimensions = dimensions
        }
    }
    private fun applyEncoding() {
        videoSource?.setIsScreencast(quality.detailContent)
        val sender = videoSender ?: return
        val p = sender.parameters
        p.degradationPreference = quality.degradationPreference
        p.encodings.forEach { it.maxBitrateBps = quality.bitrate; it.maxFramerate = quality.fps }
        if (p.encodings.isNotEmpty()) {
            check(sender.setParameters(p)) { "编码器不支持此参数组合" }
            if (quality.resolutionLocked || quality.fpsLocked)
                check(sender.parameters.degradationPreference == quality.degradationPreference) { "编码器未接受手动锁定策略" }
            if (BuildConfig.DEBUG) android.util.Log.d("QualityControl", "policy=${sender.parameters.degradationPreference} size=${quality.longEdge}x${quality.shortEdge} fps=${quality.fps} resolutionLocked=${quality.resolutionLocked} fpsLocked=${quality.fpsLocked}")
        }
    }
    fun stopScreen() {
        stopPlaybackAudio()
        val old = capturer ?: return
        capturer = null // Projection callback must not recursively stop this capture.
        captureDimensions = null
        adaptedFormat = null
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
                if (m["mid"]?.toString() != screenMid) return@forEach
                val sending = row.type == "outbound-rtp"
                val bytes = (m[if (sending) "bytesSent" else "bytesReceived"] as? Number)?.toLong() ?: 0L
                val frames = (m[if (sending) "framesEncoded" else "framesDecoded"] as? Number)?.toLong() ?: 0L
                val counters = m.mapNotNull { (key, value) -> (value as? Number)?.toDouble()?.let { key to it } }.toMap()
                val old = videoSamples.put(row.id, VideoSample(row.timestampUs, bytes, frames, counters))
                val elapsed = old?.let { (row.timestampUs-it.time)/1_000_000.0 } ?: 0.0
                val fresh = elapsed > 0 && old != null && bytes >= old.bytes && frames >= old.frames
                val width = (m["frameWidth"] as? Number)?.toInt()
                val height = (m["frameHeight"] as? Number)?.toInt()
                if (width != null && height != null && ((!sending && fresh && bytes > old!!.bytes) || (sending && capturer != null))) {
                    val rate = if (fresh) String.format(java.util.Locale.ROOT, "%.1f FPS · %.2f Mbps", (frames-old!!.frames)/elapsed, (bytes-old.bytes)*8/elapsed/1e6) else "测量中"
                    val reason = when(m["qualityLimitationReason"]) { "cpu" -> " · 设备编码性能受限"; "bandwidth" -> " · 网络带宽受限"; else -> "" }
                    val timing = mutableListOf<String>()
                    fun elapsedMs(total: String, count: String, label: String) {
                        val before = old?.counters ?: return
                        val duration = counters[total]?.minus(before[total] ?: return) ?: return
                        val samples = counters[count]?.minus(before[count] ?: return) ?: return
                        if (duration >= 0 && samples > 0) timing += "$label ${String.format(java.util.Locale.ROOT, "%.0f", duration * 1000 / samples)} ms"
                    }
                    if (sending) {
                        elapsedMs("totalEncodeTime", "framesEncoded", "编码/帧")
                        elapsedMs("totalPacketSendDelay", "packetsSent", "发送排队/包")
                    } else {
                        elapsedMs("totalDecodeTime", "framesDecoded", "解码/帧")
                        elapsedMs("jitterBufferDelay", "jitterBufferEmittedCount", "接收缓冲/帧")
                        val freezes = counters["freezeCount"]?.minus(old?.counters?.get("freezeCount") ?: 0.0)?.toInt() ?: 0
                        if (freezes > 0) timing += "本周期冻结 $freezes 次"
                    }
                    lines += "${if(sending) "发送" else "接收"} ${width}×${height} · ${rate}${reason}"
                    if (timing.isNotEmpty()) lines += timing.joinToString(" · ")
                    if (sending && quality.resolutionLocked) {
                        val (cw, ch) = captureSize()
                        if (maxOf(width,height) < maxOf(cw,ch) || minOf(width,height) < minOf(cw,ch))
                            lines += "实际发送尺寸低于锁定目标；请检查设备能力与网络，设置未被改写"
                    }
                    if (sending && quality.fpsLocked && fresh && (frames-old!!.frames)/elapsed < quality.fps * .85)
                        lines += "实际帧率低于锁定目标；静止画面、采集、设备或网络可能限制出帧，设置未被改写"
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
        stopCamera(); channel?.unregisterObserver(); channel?.close(); channel?.dispose(); channel = null
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
