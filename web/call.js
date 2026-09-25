// The desktop client speaks the same v1 protocol as the Android app.
export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export const qualities = {
  auto: { width: 1920, height: 1080, fps: 30, bitrate: 8_000_000, priority: 'balanced' },
  clear: { width: 2560, height: 1440, fps: 24, bitrate: 12_000_000, priority: 'maintain-resolution' },
  smooth: { width: 1920, height: 1080, fps: 60, bitrate: 10_000_000, priority: 'maintain-framerate' },
  uhd: { width: 3840, height: 2160, fps: 30, bitrate: 24_000_000, priority: 'maintain-resolution' },
  uhd60: { width: 3840, height: 2160, fps: 60, bitrate: 40_000_000, priority: 'maintain-resolution' },
};
export function validateQuality(q) {
  if (!q || !Number.isInteger(q.width) || !Number.isInteger(q.height) ||
      q.width < 320 || q.width > 3840 || q.height < 180 || q.height > 2160 ||
      q.width < q.height || q.width % 2 || q.height % 2) throw new Error('长边需为 320–3840、短边为 180–2160 的偶数，且长边不小于短边。');
  if (!Number.isInteger(q.fps) || q.fps < 1 || q.fps > 60) throw new Error('帧率请输入 1–60 的整数。');
  if (!Number.isInteger(q.bitrate) || q.bitrate < 500_000 || q.bitrate > 80_000_000) throw new Error('码率上限请输入 0.5–80 Mbps。');
  if (!['balanced', 'maintain-resolution', 'maintain-framerate'].includes(q.priority)) throw new Error('请选择有效的调整策略。');
  return { width:q.width, height:q.height, fps:q.fps, bitrate:q.bitrate, priority:q.priority };
}
export function captureBounds(q, settings = {}) {
  return settings.height > settings.width ? { width:q.height, height:q.width } : { width:q.width, height:q.height };
}
export function videoRate(current, previous) {
  const elapsed = previous && current.timestamp > previous.timestamp ? (current.timestamp-previous.timestamp)/1000 : 0;
  const bytes = current.bytesSent ?? current.bytesReceived ?? 0;
  const frames = current.framesEncoded ?? current.framesDecoded ?? 0;
  const valid = elapsed > 0 && bytes >= previous.bytes && frames >= previous.frames;
  return { bytes, frames, timestamp:current.timestamp,
    fps:valid ? (frames-previous.frames)/elapsed : null,
    mbps:valid ? (bytes-previous.bytes)*8/elapsed/1e6 : null };
}
// Cumulative RTC counters need interval deltas; lifetime averages hide a recent stall.
export function videoTiming(current, previous) {
  if (!previous || current.timestamp <= previous.timestamp) return '';
  const fields = current.type === 'outbound-rtp'
    ? [['totalEncodeTime','framesEncoded','编码/帧'],['totalPacketSendDelay','packetsSent','发送排队/包']]
    : [['totalDecodeTime','framesDecoded','解码/帧'],['jitterBufferDelay','jitterBufferEmittedCount','接收缓冲/帧']];
  const parts=[];
  for (const [total,count,label] of fields) {
    const duration=current[total]-previous[total], samples=current[count]-previous[count];
    if (Number.isFinite(duration) && duration>=0 && Number.isFinite(samples) && samples>0) parts.push(`${label} ${(duration*1000/samples).toFixed(0)} ms`);
  }
  const freezes=current.freezeCount-previous.freezeCount;
  if (Number.isFinite(freezes) && freezes>0) parts.push(`本周期冻结 ${freezes} 次`);
  return parts.join(' · ');
}
export function friendly(error) {
  if (error instanceof ApiError) return error.message;
  if (error?.name === 'AbortError') return '连接超时，请检查网络后重试。';
  if (error instanceof TypeError && /fetch/i.test(error.message)) return '无法连接服务，请检查网络后重试。';
  if (error?.name === 'NotAllowedError') return '授权被取消或拒绝。请在浏览器和系统设置中允许麦克风或屏幕共享后重试。';
  if (error?.name === 'NotFoundError') return '没有找到麦克风，请连接音频设备后重试。';
  if (error?.name === 'NotReadableError') return '设备正被其他应用占用，或系统未允许录屏。请检查系统权限后重试。';
  return error?.message || '连接失败，请检查网络后重试。';
}
export class Call {
  constructor(base, changed = () => {}) {
    this.base = base.replace(/\/$/, ''); this.changed = changed;
    this.state = { status: '正在连接', connected: false, sharing: false, remoteSharing: false, muted: false, cameraOn: false, cameraBusy: false, remoteCameraOn: false, chatReady: false, messages: [], busy: false, quality: { ...qualities.auto }, qualityBusy: false };
    this.abort = new AbortController(); this.pendingIce = []; this.sendChain = Promise.resolve(); this.closed = false;
    this.videoSamples = new Map(); this.statsTimer = null; this.connectionTimer = null; this.reconnectTimer = null;
  }
  update(patch) { if (!this.closed) { Object.assign(this.state, patch); this.changed(this.state); } }
  path(suffix = '') { return `/v1/rooms/${this.credentials.roomId}${suffix}`; }
  async request(method, path, body, timeout = 10000, keepalive = false) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (!keepalive) { if (this.abort.signal.aborted) controller.abort(); this.abort.signal.addEventListener('abort', abort, { once: true }); }
    const timer = setTimeout(abort, timeout);
    try {
      const headers = { Accept: 'application/json' };
      if (this.credentials) headers.Authorization = `Bearer ${this.credentials.token}`;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, cache: 'no-store', keepalive });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new ApiError(response.status, data.error || `服务暂时不可用（${response.status}），请稍后重试。`);
      return data;
    } finally { clearTimeout(timer); this.abort.signal.removeEventListener('abort', abort); }
  }
  async start(room) {
    // Construct/resume inside the click handler so Web Audio is user-activated.
    this.audio = new AudioContext();
    await this.audio.resume();
    try {
      const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false });
      if (this.closed) { mic.getTracks().forEach(t => t.stop()); return; }
      this.mic = mic;
      this.mix = this.audio.createMediaStreamDestination();
      this.micSource = this.audio.createMediaStreamSource(mic); this.micGain = this.audio.createGain();
      this.micSource.connect(this.micGain).connect(this.mix);
      mic.getAudioTracks()[0].onended = () => this.fail('麦克风已断开，请连接设备后重新加入。');
      this.credentials = await this.request('POST', room ? `/v1/rooms/${room}/join` : '/v1/rooms', {});
      if (this.closed) return;
      this.pc = new RTCPeerConnection({ iceServers: this.credentials.iceServers, iceTransportPolicy: 'all' });
      this.pc.addTrack(this.mix.stream.getAudioTracks()[0], this.mix.stream);
      if (this.credentials.role === 'host') {
        this.video = this.pc.addTransceiver('video', { direction: 'sendrecv' });
        await this.configureScreenCodecs(this.video);
        if (this.closed) return;
        this.cameraVideo = this.pc.addTransceiver('video', { direction: 'sendrecv' });
        this.bindChannel(this.pc.createDataChannel('companion-v1', { ordered:true }));
      }
      this.pc.ondatachannel = ({channel}) => this.bindChannel(channel);
      this.pc.onicecandidate = ({ candidate }) => { if (candidate) this.signal('ice', candidate.toJSON()); };
      this.pc.ontrack = ({ track, transceiver }) => {
        const videos = this.pc.getTransceivers().filter(t => t.receiver.track.kind === 'video');
        this.update(track.kind !== 'video' ? {remoteAudio:track} : videos.indexOf(transceiver) === 1 ? {remoteCamera:track} : {remoteVideo:track});
      };
      this.pc.onconnectionstatechange = () => this.connectionChanged();
      this.update({ roomId: this.credentials.roomId, role: this.credentials.role, hasTurn: this.credentials.hasTurn,
        status: this.credentials.role === 'host' ? '等待对方加入' : '正在连接语音' });
      if (this.credentials.role === 'guest') this.armDeadline();
      this.poll(); this.statsTimer = setInterval(() => this.stats(), 3000);
    } catch (error) { if (!this.closed) this.fail(friendly(error)); }
  }
  async configureScreenCodecs(video) {
    // Probe the actual 4K60 workload before changing the browser's default order.
    // Keep every offered codec: unsupported peers can still negotiate a fallback.
    if (!video.setCodecPreferences || !globalThis.RTCRtpSender?.getCapabilities || !navigator.mediaCapabilities?.encodingInfo || !navigator.mediaCapabilities?.decodingInfo) return;
    try {
      if (!this.screenCodecs) this.screenCodecs = (async () => {
        const codecs = RTCRtpSender.getCapabilities('video')?.codecs || [];
        const candidates=codecs.filter(c=>c.mimeType.toLowerCase()==='video/h264' && /(?:^|;)\s*packetization-mode=1(?:;|$)/i.test(c.sdpFmtpLine || ''));
        const usable=(await Promise.all(candidates.map(async codec=>{
          try {
            // A bare video/H264 probe can describe software Baseline while the
            // explicitly offered High profile is hardware accelerated.
            const config={type:'webrtc',video:{contentType:codec.mimeType+';'+codec.sdpFmtpLine,width:3840,height:2160,bitrate:40_000_000,framerate:60}};
            const [encode,decode]=await Promise.all([navigator.mediaCapabilities.encodingInfo(config),navigator.mediaCapabilities.decodingInfo(config)]);
            return encode.supported && encode.smooth && decode.supported && decode.smooth ? {codec,efficient:!!encode.powerEfficient && !!decode.powerEfficient} : null;
          } catch { return null; }
        }))).filter(Boolean);
        if (!usable.length) return null;
        // Among equally capable hardware profiles, preserve the browser's own order.
        usable.sort((a,b)=>Number(b.efficient)-Number(a.efficient));
        const preferred=usable.map(x=>x.codec);
        return [...preferred,...codecs.filter(c=>!preferred.includes(c))];
      })();
      const codecs=await this.screenCodecs;
      if (!this.closed && codecs) video.setCodecPreferences(codecs);
    } catch { /* Capability probing and optional codec preferences must not prevent a call. */ }
  }
  signal(type, data) {
    const id = crypto.randomUUID();
    this.sendChain = this.sendChain.then(async () => {
      for (let attempt = 0; attempt < 3 && !this.closed; attempt++) {
        try { await this.request('POST', this.path('/signal'), { id, type, data }); return; }
        catch (error) {
          if (this.closed) return;
          if (error instanceof ApiError && error.status < 500) throw error;
          if (attempt === 2) throw new Error('连接消息发送失败，请重新加入。');
          await new Promise(resolve => setTimeout(resolve, 700 * (attempt + 1)));
        }
      }
    }).catch(error => { if (!this.closed) this.fail(friendly(error)); });
    return this.sendChain;
  }
  async offer(restart = false) {
    if (this.closed || this.makingOffer || this.pc.signalingState !== 'stable') return;
    this.makingOffer = true;
    try {
      await this.pc.setLocalDescription(await this.pc.createOffer({ iceRestart: restart }));
      await this.signal('offer', { sdp: this.pc.localDescription.sdp });
    } finally { this.makingOffer = false; }
  }
  async flushIce() { for (const candidate of this.pendingIce.splice(0)) await this.pc.addIceCandidate(candidate); }
  async event(event) {
    const data = event.data || {};
    switch (event.type) {
      case 'peer-joined': this.update({ status: '正在连接语音' }); this.armDeadline(); await this.offer(); break;
      case 'offer':
        await this.pc.setRemoteDescription({ type: 'offer', sdp: data.sdp });
        // Reuse the offered video slot, as the Android answerer does.
        this.video = this.pc.getTransceivers().find(t => t.receiver.track.kind === 'video');
        if (!this.video) throw new Error('对方未提供屏幕通道，请更新客户端。');
        this.video.direction = 'sendrecv';
        await this.configureScreenCodecs(this.video);
        if (this.closed) return;
        this.cameraVideo = this.pc.getTransceivers().filter(t => t.receiver.track.kind === 'video')[1];
        if (this.cameraVideo) this.cameraVideo.direction = 'sendrecv';
        await this.flushIce();
        await this.pc.setLocalDescription(await this.pc.createAnswer());
        await this.signal('answer', { sdp: this.pc.localDescription.sdp }); break;
      case 'answer': await this.pc.setRemoteDescription({ type: 'answer', sdp: data.sdp }); await this.flushIce(); break;
      case 'ice': if (this.pc.remoteDescription) await this.pc.addIceCandidate(data); else this.pendingIce.push(data); break;
      case 'restart': if (this.credentials.role === 'host') await this.offer(true); break;
      case 'share-state': this.update({ remoteSharing: !!data.sharer && data.sharer !== this.credentials.role }); break;
      case 'ended': this.end('对方已结束通话', false); break;
    }
  }
  async poll() {
    let after = 0, failures = 0;
    while (!this.closed) {
      let response;
      try { response = await this.request('GET', this.path(`/events?after=${after}`), undefined, 27000); failures = 0; this.update({ notice: '' }); }
      catch (error) {
        if (this.closed) return;
        if ((error instanceof ApiError && error.status < 500) || ++failures >= 6) { this.fail(friendly(error)); return; }
        this.update({ notice: '网络暂时中断，正在恢复连接…' });
        await new Promise(resolve => setTimeout(resolve, Math.min(failures * 1000, 4000))); continue;
      }
      for (const event of response.events) {
        if (this.closed) return;
        try { await this.event(event); after = event.id; }
        catch (error) { this.fail(friendly(error)); return; }
      }
    }
  }
  armDeadline() {
    clearTimeout(this.connectionTimer);
    this.connectionTimer = setTimeout(() => { if (!this.state.connected) this.fail('未能建立通话，请检查网络后重新加入。'); }, 30000);
  }
  connectionChanged() {
    if (this.closed) return;
    const state = this.pc.connectionState;
    if (state === 'connected') {
      clearTimeout(this.connectionTimer); clearTimeout(this.reconnectTimer); this.reconnectTimer = null;
      this.update({ connected: true, status: '语音已连接' });
    } else if (state === 'disconnected' || state === 'failed') {
      this.update({ connected: false, status: '正在恢复通话' });
      if (this.reconnectTimer === null) {
        this.armDeadline();
        this.reconnectTimer = setTimeout(() => {
          if (this.closed) return;
          (this.credentials.role === 'host' ? this.offer(true) : this.signal('restart', {})).catch(e => this.fail(friendly(e)));
        }, 2000);
      }
    }
  }
  bindChannel(channel) {
    if (channel.label !== 'companion-v1' || this.channel) { channel.close(); return; }
    this.channel = channel;
    channel.onopen = () => { this.update({chatReady:true}); this.sendCameraState(); };
    channel.onclose = channel.onerror = () => this.update({chatReady:false, remoteCameraOn:false});
    channel.onmessage = ({data}) => {
      if (this.closed || typeof data !== 'string' || new TextEncoder().encode(data).length > 16384) return;
      try {
        const m = JSON.parse(data);
        if (m.type === 'camera' && typeof m.enabled === 'boolean') this.update({remoteCameraOn:m.enabled});
        else if (m.type === 'chat' && typeof m.text === 'string' && m.text.trim() && m.text.length <= 2000)
          this.update({messages:[...this.state.messages,{text:m.text, mine:false}].slice(-200)});
      } catch { /* Ignore malformed peer messages without interrupting media. */ }
    };
  }
  sendData(message) {
    if (this.closed || !this.state.connected || this.channel?.readyState !== 'open') throw new Error('文字通道尚未连接，请稍后重试。');
    if (this.channel.bufferedAmount > 65536) throw new Error('消息正在发送，请稍后重试。');
    this.channel.send(JSON.stringify(message));
  }
  sendChat(text) {
    text = text.trim();
    if (!text || text.length > 2000) throw new Error('消息需为 1–2000 个字符。');
    this.sendData({type:'chat',text});
    this.update({messages:[...this.state.messages,{text,mine:true}].slice(-200)});
  }
  sendCameraState() {
    if (this.channel?.readyState === 'open') {
      try { this.channel.send(JSON.stringify({type:'camera',enabled:this.state.cameraOn})); } catch {}
    }
  }
  async toggleCamera() {
    if (this.closed || this.state.cameraBusy) return;
    if (this.state.cameraOn) { await this.stopCamera(); return; }
    if (!this.state.connected || !this.cameraVideo || !this.state.chatReady) {
      this.update({error:'摄像头通道尚未就绪，请确认双方使用新版客户端。'}); return;
    }
    this.update({cameraBusy:true,error:''});
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({video:{width:{ideal:1280,max:1280},height:{ideal:720,max:720},frameRate:{ideal:30,max:30}},audio:false});
      if (this.closed) { stream.getTracks().forEach(t=>t.stop()); return; }
      this.camera = stream;
      const track = stream.getVideoTracks()[0];
      await this.cameraVideo.sender.replaceTrack(track);
      const p = this.cameraVideo.sender.getParameters();
      for (const e of p.encodings || []) { e.maxBitrate=2000000; e.maxFramerate=30; }
      if (p.encodings?.length) await this.cameraVideo.sender.setParameters(p);
      track.onended = () => this.stopCamera();
      this.update({cameraOn:true,localCamera:track});
      this.sendCameraState();
    } catch (error) {
      stream?.getTracks().forEach(t=>t.stop());
      await this.stopCamera();
      this.update({error:'无法开启摄像头，请检查摄像头权限及设备占用。'+friendly(error)});
    } finally { this.update({cameraBusy:false}); }
  }
  async stopCamera() {
    this.camera?.getTracks().forEach(t=>{t.onended=null;t.stop();}); this.camera=null;
    if (!this.closed) {
      await this.cameraVideo?.sender.replaceTrack(null).catch(()=>{});
      this.update({cameraOn:false,localCamera:null}); this.sendCameraState();
    }
  }
  mute() { this.update({ muted: !this.state.muted }); this.micGain.gain.value = this.state.muted ? 0 : 1; }
  async quality(value) {
    const q = validateQuality(typeof value === 'string' ? qualities[value] : value);
    if (this.closed || this.state.busy || this.state.qualityBusy) throw new Error('共享状态正在变化，请稍后调整。');
    const previous = this.state.quality;
    this.update({ qualityBusy:true });
    try {
      if (this.display) await this.encoding(q);
      if (this.closed) return;
      this.update({ quality:q, qualityNote:this.display ? this.captureNote(q) : '', error:'' });
    } catch (error) {
      if (!this.closed && this.display) {
        try { await this.encoding(previous); }
        catch { await this.stopSharing(); }
      }
      throw new Error('画质未应用，已保留原设置；设备可能不支持该组合。' + friendly(error));
    } finally { this.update({qualityBusy:false}); }
  }
  captureNote(q) {
    const settings = this.display?.getVideoTracks()[0]?.getSettings() || {};
    const bounds = captureBounds(q, settings);
    const smaller = settings.width && settings.height && Math.max(settings.width/bounds.width, settings.height/bounds.height) < .95;
    return smaller ? '采集源低于目标尺寸：不会将低分辨率画面放大冒充 4K。请检查共享源和浏览器采集能力。' : '';
  }
  async encoding(q = this.state.quality) {
    const track = this.display?.getVideoTracks()[0];
    if (track) {
      const bounds = captureBounds(q, track.getSettings());
      // A detail hint makes libwebrtc treat BALANCED as MAINTAIN_RESOLUTION.
      // Motion content keeps the explicitly selected adaptation policy effective.
      track.contentHint = q.priority === 'maintain-resolution' && q.fps <= 30 ? 'detail' : 'motion';
      const next = { width:{ideal:bounds.width,max:bounds.width}, height:{ideal:bounds.height,max:bounds.height}, frameRate:{ideal:q.fps,max:q.fps} };
      const current = track.getConstraints();
      if (Object.keys(next).some(key => current[key]?.ideal !== next[key].ideal || current[key]?.max !== next[key].max)) {
        await track.applyConstraints(next);
      }
    }
    if (this.video?.sender) {
      const p = this.video.sender.getParameters();
      p.degradationPreference = q.priority;
      for (const encoding of p.encodings || []) { encoding.maxBitrate=q.bitrate; encoding.maxFramerate=q.fps;
        const settings=track?.getSettings() || {}; const bounds=captureBounds(q,settings);
        encoding.scaleResolutionDownBy=Math.max(1,(settings.width||bounds.width)/bounds.width,(settings.height||bounds.height)/bounds.height); }
      if (p.encodings?.length) await this.video.sender.setParameters(p);
    }
  }
  async share() {
    if (this.closed || !this.state.connected || this.state.busy || this.state.qualityBusy || this.state.remoteSharing || this.state.sharing) return;
    this.update({ busy: true, error: '' });
    let capture, reserved = false;
    try {
      // Must happen before any network await: browsers require a direct user gesture.
      capture = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: { ideal: this.state.quality.fps, max: 60 } }, audio: true, selfBrowserSurface: 'exclude', systemAudio: 'include', surfaceSwitching: 'include' });
      if (this.closed) { capture.getTracks().forEach(t => t.stop()); return; }
      await this.request('POST', this.path('/share'), { enabled: true }); reserved = true;
      if (this.closed) { capture.getTracks().forEach(t => t.stop()); return; }
      this.display = capture;
      const track = capture.getVideoTracks()[0];
      if (track.readyState === 'ended') throw new Error('屏幕共享已取消，请重新选择。');
      // Configure capture and negotiated sender limits before the first frame is attached.
      await this.encoding();
      if (this.closed) return;
      if (track.readyState === 'ended') throw new Error('屏幕共享已取消，请重新选择。');
      await this.video.sender.replaceTrack(track);
      const sound = capture.getAudioTracks();
      if (sound.length) { this.displaySource = this.audio.createMediaStreamSource(new MediaStream(sound)); this.displaySource.connect(this.mix); }
      track.onended = () => this.stopSharing();
      this.update({ sharing: true, localVideo: track, systemAudio: sound.length > 0, busy: false, qualityNote:this.captureNote(this.state.quality) });
    } catch (error) {
      capture?.getTracks().forEach(t => t.stop()); this.stopCapture();
      if (!this.closed && reserved) {
        try { await this.request('POST', this.path('/share'), { enabled: false }); }
        catch { this.fail('共享状态同步失败，请重新加入。'); return; }
      }
      this.update({ busy: false, error: friendly(error) });
    }
  }
  stopCapture() {
    this.displaySource?.disconnect(); this.displaySource = null;
    this.display?.getTracks().forEach(t => { t.onended = null; t.stop(); }); this.display = null;
    if (!this.closed && this.video) this.video.sender.replaceTrack(null).catch(() => {});
  }
  async stopSharing() {
    if (this.closed || !this.display) return;
    this.stopCapture(); this.update({ sharing: false, localVideo: null, busy: true, systemAudio: false, qualityNote:'', mediaStats:'' });
    try { await this.request('POST', this.path('/share'), { enabled: false }); this.update({ busy: false }); }
    catch { if (!this.closed) this.fail('画面已停止，但共享状态同步失败，请重新加入。'); }
  }
  async stats() {
    if (this.closed || !this.pc) return;
    try {
      const report = await this.pc.getStats();
      const transport = [...report.values()].find(s => s.type === 'transport' && s.selectedCandidatePairId);
      const pair = report.get(transport?.selectedCandidatePairId) || [...report.values()].find(s => s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated);
      const lines = [];
      for (const direction of ['outbound-rtp','inbound-rtp']) {
        const active = direction === 'outbound-rtp' ? this.state.sharing : this.state.remoteSharing;
        const rows = [...report.values()].filter(r=>r.type===direction && r.kind==='video' && !r.isRemote && r.mid === this.video?.mid);
        for (const r of rows) {
          const previous=this.videoSamples.get(r.id);
          const rate=videoRate(r,previous);this.videoSamples.set(r.id,{...r,...rate});
          if (!active || !r.frameWidth) continue;
          const why={cpu:'设备编码性能受限',bandwidth:'网络带宽受限',other:'编码器调整中'}[r.qualityLimitationReason];
          lines.push(`${direction==='outbound-rtp'?'发送':'接收'} ${r.frameWidth}×${r.frameHeight} · ${rate.fps===null?'测量中':rate.fps.toFixed(1)+' FPS'} · ${rate.mbps===null?'测量中':rate.mbps.toFixed(2)+' Mbps'}${why?' · '+why:''}`);
          const timing=videoTiming(r,previous);if(timing)lines.push(timing);
        }
      }
      const patch={mediaStats:lines.join('；') || ((this.state.sharing||this.state.remoteSharing)?'正在测量视频…':'')};
      if (!pair) {this.update(patch);return;}
      const relay = [report.get(pair.localCandidateId), report.get(pair.remoteCandidateId)].some(c => c?.candidateType === 'relay');
      this.update({ ...patch, network: `${relay ? '中转连接' : '直接连接'}${pair.currentRoundTripTime == null ? '' : ` · 网络往返 ${Math.round(pair.currentRoundTripTime * 1000)} ms`}` });
    } catch { /* Statistics do not control call lifetime. */ }
  }
  fail(message) { this.end(message, true, true); }
  end(message = '通话已结束', notify = true, error = false) {
    if (this.closed) return;
    this.closed = true; this.abort.abort();
    clearInterval(this.statsTimer); clearTimeout(this.connectionTimer); clearTimeout(this.reconnectTimer);
    this.stopCamera(); this.channel?.close(); this.stopCapture(); this.mic?.getTracks().forEach(t => { t.onended = null; t.stop(); });
    this.mix?.stream.getTracks().forEach(t => t.stop()); this.pc?.close(); this.audio?.close().catch(() => {});
    if (notify && this.credentials) this.request('DELETE', this.path(), undefined, 1500, true).catch(() => {});
    this.changed({ ...this.state, ended: true, connected: false, sharing: false, cameraOn:false, localCamera:null, remoteCamera:null, messages:[], chatReady:false, message, error: error ? message : '' });
  }
}
