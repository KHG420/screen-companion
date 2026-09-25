package cn.screenshare.app


import android.app.Service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.webrtc.PeerConnection

class CallSession(private val service: CallService, private val state: MutableStateFlow<CallState>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signaling: Signaling? = null
    var rtc: RtcSession? = null
        private set
    private var started = false
    private var ending = false
    private var finalState = CallState(notice = "通话已结束")
    private var sharingWork: Job? = null
    private var reconnectJob: Job? = null
    private val messages = Channel<Pair<String, JSONObject>>(128)
    private val audio = service.getSystemService(AudioManager::class.java)
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var audioConfigured = false
    private var hasAudioFocus = true
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            scope.launch {
                hasAudioFocus = change == AudioManager.AUDIOFOCUS_GAIN
                rtc?.microphone(hasAudioFocus && !state.value.muted)
                state.update { it.copy(notice = if (hasAudioFocus) null else "其他应用正在使用音频，语音已暂停") }
            }
        }, Handler(Looper.getMainLooper())).build()
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { refreshRoutes() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { refreshRoutes() }
    }
    private val communicationListener = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnCommunicationDeviceChangedListener { refreshRoutes() } else null

    fun start(server: String, room: String?) {
        if (started) return
        started = true
        state.value = CallState(active = true, status = if (room == null) "正在创建房间" else "正在加入房间")
        scope.launch {
            try {
                val api = Signaling(ServerAddress.normalize(server, BuildConfig.DEBUG)).also { signaling = it }
                val credentials = api.connect(room)
                setupAudio()
                rtc = RtcSession(service, credentials.iceServers, credentials.role == "host", ::queueSignal,
                    { track -> state.update { it.copy(remoteTrack = track) } }, ::connectionChanged,
                    { stopSharing() },
                    { state.update { it.copy(error = "系统声音采集中断，请停止共享后重新开始。语音和画面仍可使用。") } })
                rtc?.microphone(hasAudioFocus)
                state.update { it.copy(roomId = credentials.roomId, role = credentials.role, hasTurn = credentials.hasTurn,
                    peerPresent = credentials.role == "guest", status = if (credentials.role == "host") "等待对方加入" else "正在连接语音") }
                launch { sendLoop(api) }
                launch { pollLoop(api) }
                launch { while (isActive) { delay(3000); rtc?.stats { value -> state.update { it.copy(stats = value) } } } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { end(error = friendly(e)) }
        }
    }
    private fun queueSignal(type: String, data: JSONObject) { if (!ending && messages.trySend(type to data).isFailure) end(error = "连接消息过多，请重新加入") }
    private suspend fun sendLoop(api: Signaling) {
        for ((type, data) in messages) {
            val messageId = java.util.UUID.randomUUID().toString()
            var delivered = false
            for (attempt in 0..2) {
                try { api.send(type, data, messageId); delivered = true; break }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiException && e.status in 400..499) { end(error = friendly(e)); return }
                    delay((attempt + 1) * 700L)
                }
            }
            if (!delivered) { end(error = "连接消息发送失败，请检查网络后重新加入"); return }
        }
    }
    private suspend fun pollLoop(api: Signaling) {
        var after = 0L
        var failures = 0
        while (scope.isActive && !ending) {
            try {
                val events = api.poll(after).getJSONArray("events")
                if (failures > 0) state.update { it.copy(notice = null) }
                failures = 0
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val data = event.optJSONObject("data") ?: JSONObject()
                    when (event.getString("type")) {
                        "peer-joined" -> { state.update { it.copy(peerPresent = true, status = "正在连接语音") }; rtc?.offer() }
                        "offer" -> rtc?.acceptOffer(data.getString("sdp"))
                        "answer" -> rtc?.acceptAnswer(data.getString("sdp"))
                        "ice" -> rtc?.addIce(data)
                        "restart" -> if (state.value.role == "host") rtc?.offer(restart = true)
                        "share-state" -> {
                            val sharer = data.optString("sharer")
                            state.update { it.copy(remoteSharing = sharer.isNotEmpty() && sharer != it.role) }
                        }
                        "ended" -> { end(notice = "通话已结束", notifyPeer = false); return }
                    }
                    after = event.getLong("id")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status in 400..499) { end(error = friendly(e), notifyPeer = false); return }
                failures++
                if (failures >= 6) { end(error = "无法恢复服务器连接，请重新加入"); return }
                state.update { it.copy(notice = "网络暂时中断，正在恢复连接…") }
                delay(minOf(failures * 1000L, 4000L))
            }
        }
    }
    private fun connectionChanged(value: PeerConnection.PeerConnectionState) {
        when (value) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                reconnectJob?.cancel(); reconnectJob = null
                state.update { it.copy(connected = true, status = "语音已连接", notice = null) }
            }
            PeerConnection.PeerConnectionState.CONNECTING -> state.update { it.copy(status = "正在连接语音") }
            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                state.update { it.copy(connected = false, status = "正在恢复通话") }
                if (reconnectJob?.isActive != true) reconnectJob = scope.launch {
                    delay(2000)
                    try {
                        if (state.value.role == "host") rtc?.offer(restart = true) else queueSignal("restart", JSONObject())
                        delay(20_000)
                        if (!state.value.connected) end(error = if (state.value.hasTurn) "无法恢复通话，请检查网络后重新加入" else "两台手机未能直连，请配置中转服务或改用同一 Wi-Fi")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { end(error = friendly(e)) }
                }
            }
            else -> Unit
        }
    }
    fun startSharing(data: Intent) {
        if (ending || !state.value.connected || state.value.sharing || state.value.remoteSharing || state.value.shareBusy) return
        state.update { it.copy(shareBusy = true, error = null) }
        sharingWork = scope.launch {
            var reserved = false
            try {
                signaling?.share(true); reserved = true
                service.foreground(true)
                checkNotNull(rtc).startScreen(data, state.value.quality)
                state.update { it.copy(sharing = true, shareBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { rtc?.stopScreen() }; service.foreground(false)
                if (reserved) runCatching { signaling?.share(false) }
                state.update { it.copy(shareBusy = false, sharing = false, error = "无法开始共享：${friendly(e)}") }
            }
        }
    }
    fun stopSharing() {
        if (ending) return
        sharingWork?.cancel()
        runCatching { rtc?.stopScreen() }
        service.foreground(false)
        state.update { it.copy(sharing = false, shareBusy = true) }
        sharingWork = scope.launch {
            try { signaling?.share(false); state.update { it.copy(shareBusy = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { end(error = "画面已停止，但共享状态同步失败，请重新加入") }
        }
    }
    fun toggleMute() { state.update { it.copy(muted = !it.muted) }; rtc?.microphone(hasAudioFocus && !state.value.muted) }
    fun quality(value: Quality) {
        if (ending || state.value.shareBusy) return
        try { rtc?.setQuality(value); state.update { it.copy(quality = value, error = null) } }
        catch (e: Exception) { state.update { it.copy(error = e.message ?: "无法调整画质，请降低参数重试") } }
    }
    fun dismissError() { state.update { it.copy(error = null) } }

    @Suppress("DEPRECATION")
    private fun setupAudio() {
        savedMode = audio.mode; savedSpeaker = audio.isSpeakerphoneOn; audioConfigured = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        hasAudioFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        audio.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) communicationListener?.let { audio.addOnCommunicationDeviceChangedListener(service.mainExecutor, it) }
        refreshRoutes()
    }
    @Suppress("DEPRECATION")
    fun selectRoute(id: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val device = audio.availableCommunicationDevices.firstOrNull { it.id == id } ?: return
                if (!audio.setCommunicationDevice(device)) { state.update { it.copy(error = "暂时无法切换该音频设备") }; return }
            } else { audio.isSpeakerphoneOn = id == 1 }
            refreshRoutes()
        } catch (_: SecurityException) { state.update { it.copy(error = "未获得蓝牙权限，请在系统设置中开启附近设备权限") } }
    }
    @Suppress("DEPRECATION")
    private fun refreshRoutes() {
        if (ending) return
        val routes = if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.filter { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || service.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }
                .map { AudioRoute(it.id, when (it.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "耳机"
                    else -> it.productName.toString()
                }) }
        } else listOf(AudioRoute(0, "听筒 / 耳机"), AudioRoute(1, "扬声器"))
        val selected = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.id ?: -1 else if (audio.isSpeakerphoneOn) 1 else 0
        state.update { it.copy(routes = routes, selectedRoute = selected) }
    }
    fun end(error: String? = null, notice: String? = null, notifyPeer: Boolean = true) {
        if (ending) return
        ending = true
        sharingWork?.cancel(); reconnectJob?.cancel()
        // Stop local capture/microphone immediately, even while the server is unreachable.
        runCatching { rtc?.close() }; rtc = null
        finalState = CallState(error = error, notice = notice)
        state.update { it.copy(connected = false, sharing = false, remoteSharing = false, remoteTrack = null, shareBusy = true, status = "正在结束通话") }
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        scope.launch {
            if (notifyPeer) withTimeoutOrNull(1500) { runCatching { signaling?.leave() } }
            signaling?.close(); service.stopSelf()
        }
    }
    @Suppress("DEPRECATION")
    fun dispose() {
        ending = true; signaling?.close(); scope.cancel(); messages.close()
        runCatching { rtc?.close() }; rtc = null
        state.value = finalState
        if (!audioConfigured) return
        audio.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 31) { communicationListener?.let { audio.removeOnCommunicationDeviceChangedListener(it) }; audio.clearCommunicationDevice() }
        audio.abandonAudioFocusRequest(focus)
        if (started) { audio.mode = savedMode; if (Build.VERSION.SDK_INT < 31) audio.isSpeakerphoneOn = savedSpeaker }
        if (state.value.active) state.value = CallState(notice = "通话已结束")
    }
    private fun friendly(e: Exception): String = when (e) {
        is ApiException, is IllegalArgumentException -> e.message ?: "连接失败，请重试"
        is java.net.SocketTimeoutException -> "连接超时，请检查服务器地址和网络"
        is java.net.UnknownHostException -> "找不到服务器，请检查服务器地址"
        is javax.net.ssl.SSLException -> "服务器安全连接失败，请检查 HTTPS 证书"
        else -> "连接失败，请检查网络和服务器配置"
    }
}
