// The desktop client speaks the same v1 protocol as the Android app.
export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export const qualities = {
  hd: { width:2560, height:1440, fps:30, bitrate:12_000_000, priority:"maintain-resolution", resolutionLocked:true, fpsLocked:false },
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
  for (const key of ['resolutionLocked','fpsLocked']) if (q[key] !== undefined && typeof q[key] !== 'boolean') throw new Error('锁定选项无效。');
  return { width:q.width, height:q.height, fps:q.fps, bitrate:q.bitrate, priority:q.priority, resolutionLocked:q.resolutionLocked===true, fpsLocked:q.fpsLocked===true };
}
export function degradationPreference(q) {
  if (q.resolutionLocked && q.fpsLocked) return 'maintain-framerate-and-resolution';
  if (q.resolutionLocked) return 'maintain-resolution';
  if (q.fpsLocked) return 'maintain-framerate';
  return q.priority;
}
export function qualityControlLabel(q) {
  if (q.resolutionLocked && q.fpsLocked) return '自定义 · 分辨率与帧率已锁定';
  if (q.resolutionLocked) return '分辨率已锁定 · 允许自动降帧';
  if (q.fpsLocked) return '帧率已锁定 · 允许自动降分辨率';
  return '自动调整 · ' + ({balanced:'自动平衡','maintain-resolution':'清晰优先','maintain-framerate':'帧率优先'}[q.priority]);
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
// Only measured encoder/queue pressure triggers a reduction. A still screen is not a stall.
export function videoLoad(load, current, previous, fps) {
  const elapsed = previous && (current.timestamp-previous.timestamp)/1000;
  const frames = previous && current.framesEncoded-previous.framesEncoded;
  if (!(elapsed >= 1 && elapsed <= 5 && frames > 0)) return {...load, healthy:0};
  if (current.totalEncodeTime < previous.totalEncodeTime || current.packetsSent < previous.packetsSent || current.totalPacketSendDelay < previous.totalPacketSendDelay)
    return {...load,healthy:0};
  const encode = (current.totalEncodeTime-previous.totalEncodeTime)/frames;
  const packets = current.packetsSent-previous.packetsSent;
  const queue = packets > 0 ? (current.totalPacketSendDelay-previous.totalPacketSendDelay)/packets : NaN;
  if (current.qualityLimitationReason === 'cpu' || encode > .8/fps || queue > .1)
    return {level:Math.min(2,load.level+1),healthy:0};
  const healthy = Number.isFinite(encode) && encode >= 0 && encode < .5/fps &&
    current.qualityLimitationReason === 'none' && (!Number.isFinite(queue) || (queue >= 0 && queue < .03)) && frames/elapsed >= fps*.8;
  const count = healthy ? load.healthy+1 : 0;
  return count >= 6 ? {level:Math.max(0,load.level-1),healthy:0} : {...load,healthy:count};
}
export function effectiveQuality(q, level) {
  if (!level) return q;
  const spatial = !q.resolutionLocked && (q.fpsLocked || q.priority !== 'maintain-resolution');
  const temporal = !q.fpsLocked && (q.resolutionLocked || q.priority !== 'maintain-framerate');
  const scale = level === 1 ? .75 : .5;
  return {...q, width:spatial?Math.max(320,Math.floor(q.width*scale/2)*2):q.width,
    height:spatial?Math.max(180,Math.floor(q.height*scale/2)*2):q.height,
    fps:temporal?Math.min(q.fps,Math.max(10,Math.floor(q.fps/(level===1?1.5:3)))):q.fps};
}
const cameraFormats = [{width:1280,height:720,fps:30},{width:960,height:540,fps:24},{width:640,height:360,fps:15}];
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
    this.state = { status: '正在连接', connected: false, sharing: false, remoteSharing: false, muted: true, micAvailable: false, micBusy: false, micIssue: '', systemMuted: false, cameraOn: false, cameraBusy: false, remoteCameraOn: false, chatReady: false, messages: [], busy: false, quality: { ...qualities.hd }, qualityBusy: false };
    this.abort = new AbortController(); this.pendingIce = []; this.sendChain = Promise.resolve(); this.closed = false;
    this.videoSamples = new Map(); this.statsTimer = null; this.connectionTimer = null; this.reconnectTimer = null;
    this.screenLoad = {level:0,healthy:0}; this.cameraLoad = {level:0,healthy:0};
    this.networkAvailable = () => {
      if (!this.closed && !this.state.connected && this.retryConnection) {
        clearTimeout(this.reconnectTimer); this.reconnectTimer=setTimeout(this.retryConnection,0);
      }
    };
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
    // Some browsers leave resume pending until a later gesture. Room entry must
    // still work; the explicit playback/microphone action can activate audio.
    this.audio.resume().catch(() => {});
    try {
      if (this.closed) return;
      this.mix = this.audio.createMediaStreamDestination();
      // Permission prompts and missing microphones must not block joining or receiving.
      this.enableMicrophone();
      this.credentials = await this.request('POST', room ? `/v1/rooms/${room}/join` : '/v1/rooms', {});
      if (this.closed) return;
      this.pc = new RTCPeerConnection({ iceServers: this.credentials.iceServers, iceTransportPolicy: 'all' });
      this.pc.addTrack(this.mix.stream.getAudioTracks()[0], this.mix.stream);
      if (this.credentials.role === 'host') {
        this.video = this.pc.addTransceiver('video', { direction: 'sendrecv' });
        await this.configureScreenCodecs(this.video);
        if (this.closed) return;
        this.cameraVideo = this.pc.addTransceiver('video', { direction: 'sendrecv' });
        await this.configureScreenCodecs(this.cameraVideo, {...cameraFormats[(navigator.deviceMemory <= 4 || navigator.hardwareConcurrency <= 4)?1:0],bitrate:2_000_000});
        if (this.closed) return;
        this.bindChannel(this.pc.createDataChannel('companion-v1', { ordered:true }));
      }
      this.pc.ondatachannel = ({channel}) => this.bindChannel(channel);
      this.pc.onicecandidate = ({ candidate }) => { if (candidate) this.signal('ice', candidate.toJSON()); };
      this.pc.ontrack = ({ track, transceiver }) => {
        const videos = this.pc.getTransceivers().filter(t => t.receiver.track.kind === 'video');
        this.update(track.kind !== 'video' ? {remoteAudio:track} : videos.indexOf(transceiver) === 1 ? {remoteCamera:track} : {remoteVideo:track});
      };
      this.pc.onconnectionstatechange = () => this.connectionChanged();
      globalThis.addEventListener?.('online',this.networkAvailable);
      this.update({ roomId: this.credentials.roomId, role: this.credentials.role, hasTurn: this.credentials.hasTurn,
        status: this.credentials.role === 'host' ? '等待对方加入' : '正在连接语音' });
      if (this.credentials.role === 'guest') this.armDeadline();
      this.poll(); this.statsTimer = setInterval(() => this.stats(), 2000);
    } catch (error) { if (!this.closed) this.fail(friendly(error)); }
  }
  async configureScreenCodecs(video, quality = this.state.quality) {
    // Probe the negotiated quality, so lower-power devices need not support 4K60
    // to use efficient encoding at 720p/1080p. Live quality changes keep the codec.
    // Keep every offered codec: unsupported peers can still negotiate a fallback.
    if (!video.setCodecPreferences || !globalThis.RTCRtpSender?.getCapabilities || !navigator.mediaCapabilities?.encodingInfo || !navigator.mediaCapabilities?.decodingInfo) return;
    try {
      const {width,height,fps,bitrate}=quality;
      const key=`${width}/${height}/${fps}/${bitrate}`;
      this.videoCodecs ??= new Map();
      if (!this.videoCodecs.has(key)) {
        this.videoCodecs.set(key, (async () => {
          const codecs = RTCRtpSender.getCapabilities('video')?.codecs || [];
          const candidates=codecs.filter(c=>c.mimeType.toLowerCase()==='video/h264' && /(?:^|;)\s*packetization-mode=1(?:;|$)/i.test(c.sdpFmtpLine || ''));
          const usable=(await Promise.all(candidates.map(async codec=>{
            try {
              // A bare video/H264 probe can describe software Baseline while the
              // explicitly offered High profile is hardware accelerated.
              const config={type:'webrtc',video:{contentType:codec.mimeType+';'+codec.sdpFmtpLine,width,height,bitrate,framerate:fps}};
              const [encode,decode]=await Promise.all([navigator.mediaCapabilities.encodingInfo(config),navigator.mediaCapabilities.decodingInfo(config)]);
              return encode.supported && encode.smooth && decode.supported && decode.smooth ? {codec,efficient:!!encode.powerEfficient && !!decode.powerEfficient} : null;
            } catch { return null; }
          }))).filter(Boolean);
          if (!usable.length) return null;
          // Among equally capable hardware profiles, preserve the browser's own order.
          usable.sort((a,b)=>Number(b.efficient)-Number(a.efficient));
          const preferred=usable.map(x=>x.codec);
          return [...preferred,...codecs.filter(c=>!preferred.includes(c))];
        })());
      }
      const codecs=await this.videoCodecs.get(key);
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
        if (this.cameraVideo) {
          this.cameraVideo.direction = 'sendrecv';
          await this.configureScreenCodecs(this.cameraVideo, {...cameraFormats[(navigator.deviceMemory <= 4 || navigator.hardwareConcurrency <= 4)?1:0],bitrate:2_000_000});
          if (this.closed) return;
        }
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
      this.retryConnection = null;
      this.update({ connected: true, status: '语音已连接' });
    } else if (state === 'disconnected' || state === 'failed') {
      this.update({ connected: false, status: '正在恢复通话' });
      if (this.reconnectTimer === null) {
        this.armDeadline();
        let attempts = 0, running = false;
        const retry = async () => {
          if (this.closed || this.state.connected || running || this.retryConnection !== retry || attempts >= 3) return;
          running = true;
          attempts++;
          try { await (this.credentials.role === 'host' ? this.offer(true) : this.signal('restart', {})); }
          catch { /* A following bounded retry can recover a transient negotiation failure. */ }
          finally { running = false; }
          if (!this.closed && !this.state.connected && this.retryConnection === retry && attempts < 3) this.reconnectTimer = setTimeout(retry, 6000);
        };
        this.retryConnection = retry;
        this.reconnectTimer = setTimeout(retry, state === 'failed' ? 0 : 1500);
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
      this.cameraLoad = {level:(navigator.deviceMemory <= 4 || navigator.hardwareConcurrency <= 4)?1:0,healthy:0};
      const format = cameraFormats[this.cameraLoad.level];
      stream = await navigator.mediaDevices.getUserMedia({video:{width:{ideal:format.width,max:format.width},height:{ideal:format.height,max:format.height},frameRate:{ideal:format.fps,max:format.fps}},audio:false});
      if (this.closed) { stream.getTracks().forEach(t=>t.stop()); return; }
      this.camera = stream;
      const track = stream.getVideoTracks()[0];
      track.contentHint = 'motion';
      const p = this.cameraVideo.sender.getParameters();
      p.degradationPreference = 'maintain-framerate';
      // Relative allocation matters only when another local sender competes.
      // Leave screen locks intact and let camera resolution adapt first.
      for (const e of p.encodings || []) { e.maxBitrate=2000000; e.maxFramerate=format.fps; e.priority='very-low'; }
      if (p.encodings?.length) await this.cameraVideo.sender.setParameters(p);
      if (this.closed || this.camera !== stream) { stream.getTracks().forEach(t=>t.stop()); return; }
      if (track.readyState === 'ended') throw new Error('摄像头已断开');
      await this.cameraVideo.sender.replaceTrack(track);
      if (this.closed || this.camera !== stream) { stream.getTracks().forEach(t=>t.stop()); return; }
      if (track.readyState === 'ended') throw new Error('摄像头已断开');
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
    this.cameraLoad = {level:0,healthy:0}; this.videoSamples.clear();
    if (!this.closed) {
      await this.cameraVideo?.sender.replaceTrack(null).catch(()=>{});
      this.update({cameraOn:false,localCamera:null}); this.sendCameraState();
    }
  }
  async enableMicrophone() {
    if (this.closed || this.state.micBusy) return;
    this.update({micBusy:true, micIssue:''});
    let mic;
    try {
      mic = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
      if (this.closed) { mic.getTracks().forEach(t=>t.stop()); return; }
      const track = mic.getAudioTracks()[0];
      if (!track || track.readyState === 'ended') throw new Error('麦克风不可用');
      await this.audio.resume();
      if (this.closed) { mic.getTracks().forEach(t=>t.stop()); return; }
      this.micSource?.disconnect(); this.micGain?.disconnect();
      this.mic?.getTracks().forEach(t=>{t.onended=null;t.stop();});
      this.mic=mic; this.micSource=this.audio.createMediaStreamSource(mic); this.micGain=this.audio.createGain();
      this.micSource.connect(this.micGain).connect(this.mix);
      track.onended=()=>{
        if (this.closed || this.mic !== mic) return;
        this.micSource.disconnect(); this.micGain.disconnect();
        this.update({micAvailable:false,muted:true,micIssue:'麦克风已断开，画面和聊天继续。连接设备后，点击“重试麦克风”。'});
      };
      this.update({micAvailable:true,muted:false,micIssue:''});
    } catch (error) {
      mic?.getTracks().forEach(t=>t.stop());
      this.update({micAvailable:false,muted:true,micIssue:'暂未开启麦克风，你仍可观看、听声音和聊天。'+friendly(error)});
    } finally { this.update({micBusy:false}); }
  }
  mute() {
    if (this.closed || this.state.micBusy) return;
    if (!this.state.micAvailable) return this.enableMicrophone();
    this.update({ muted: !this.state.muted }); this.micGain.gain.value = this.state.muted ? 0 : 1;
  }
  muteSystemAudio() {
    if (!this.displayGain || !this.state.systemAudio) return;
    this.update({systemMuted:!this.state.systemMuted});
    this.displayGain.gain.value=this.state.systemMuted?0:1;
  }
  async quality(value) {
    const q = validateQuality(typeof value === 'string' ? qualities[value] : value);
    if (this.closed || this.state.busy || this.state.qualityBusy || this.adjustingMedia) throw new Error('共享状态正在变化，请稍后调整。');
    const previous = this.state.quality;
    this.update({ qualityBusy:true });
    try {
      if (this.display) await this.encoding(q);
      if (this.closed) return;
      this.screenLoad = {level:0,healthy:0};
      this.videoSamples.clear();
      this.update({ quality:q, qualityNote:this.display ? this.captureNote(q) : '', error:'' });
    } catch (error) {
      if (!this.closed && this.display) {
        try { await this.encoding(effectiveQuality(previous,this.screenLoad.level)); }
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
    const capture = this.display;
    const track = this.display?.getVideoTracks()[0];
    if (track) {
      const bounds = captureBounds(q, track.getSettings());
      // A detail hint makes libwebrtc treat BALANCED as MAINTAIN_RESOLUTION.
      // Motion content keeps the explicitly selected adaptation policy effective.
      track.contentHint = (q.resolutionLocked || (!q.fpsLocked && q.priority === 'maintain-resolution')) && q.fps <= 30 ? 'detail' : 'motion';
      const next = { width:{ideal:bounds.width,max:bounds.width}, height:{ideal:bounds.height,max:bounds.height}, frameRate:{ideal:q.fps,max:q.fps} };
      const current = track.getConstraints();
      if (Object.keys(next).some(key => current[key]?.ideal !== next[key].ideal || current[key]?.max !== next[key].max)) {
        await track.applyConstraints(next);
      }
      if (this.closed || this.display !== capture) return;
    }
    if (this.video?.sender) {
      const p = this.video.sender.getParameters();
      p.degradationPreference = degradationPreference(q);
      for (const encoding of p.encodings || []) { encoding.maxBitrate=q.bitrate; encoding.maxFramerate=q.fps;
        const settings=track?.getSettings() || {}; const bounds=captureBounds(q,settings);
        encoding.scaleResolutionDownBy=Math.max(1,(settings.width||bounds.width)/bounds.width,(settings.height||bounds.height)/bounds.height); }
      if (p.encodings?.length) {
        try {
          await this.video.sender.setParameters(p);
          if ((q.resolutionLocked || q.fpsLocked) && this.video.sender.getParameters().degradationPreference !== p.degradationPreference)
            throw new Error('浏览器未接受锁定策略');
        } catch (error) {
          if (q.resolutionLocked || q.fpsLocked) throw new Error('浏览器未能执行手动锁定，未自动改用其他策略。请升级浏览器，或明确取消对应锁定后重试。' + friendly(error));
          throw error;
        }
      }
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
      this.videoSamples.clear();
      this.screenLoad = {level:(navigator.deviceMemory <= 4 || navigator.hardwareConcurrency <= 4) && !(this.state.quality.resolutionLocked && this.state.quality.fpsLocked)?1:0,healthy:0};
      const track = capture.getVideoTracks()[0];
      if (track.readyState === 'ended') throw new Error('屏幕共享已取消，请重新选择。');
      // Configure capture and negotiated sender limits before the first frame is attached.
      await this.encoding(effectiveQuality(this.state.quality,this.screenLoad.level));
      if (this.closed || this.display !== capture) return;
      if (track.readyState === 'ended') throw new Error('屏幕共享已取消，请重新选择。');
      await this.video.sender.replaceTrack(track);
      if (this.closed || this.display !== capture) return;
      if (track.readyState === 'ended') throw new Error('屏幕共享已取消，请重新选择。');
      const sound = capture.getAudioTracks();
      if (sound.length) {
        this.displaySource = this.audio.createMediaStreamSource(new MediaStream(sound));
        this.displayGain = this.audio.createGain(); this.displaySource.connect(this.displayGain).connect(this.mix);
        sound[0].onended = () => this.update({systemAudio:false});
      }
      track.onended = () => this.stopSharing();
      this.update({ sharing: true, localVideo: track, systemAudio: sound.length > 0, systemMuted:false, busy: false, qualityNote:this.captureNote(effectiveQuality(this.state.quality,this.screenLoad.level)) });
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
    this.displayGain?.disconnect(); this.displayGain = null;
    this.display?.getTracks().forEach(t => { t.onended = null; t.stop(); }); this.display = null;
    this.screenLoad = {level:0,healthy:0}; this.videoSamples.clear();
    if (!this.closed && this.video) this.video.sender.replaceTrack(null).catch(() => {});
  }
  async stopSharing() {
    if (this.closed || !this.display) return;
    this.stopCapture(); this.update({ sharing: false, localVideo: null, busy: true, systemAudio: false, qualityNote:'', mediaStats:'' });
    try { await this.request('POST', this.path('/share'), { enabled: false }); this.update({ busy: false }); }
    catch { if (!this.closed) this.fail('画面已停止，但共享状态同步失败，请重新加入。'); }
  }
  async stats() {
    if (this.closed || !this.pc || this.statsBusy) return;
    this.statsBusy = true;
    try {
      const report = await this.pc.getStats();
      const transport = [...report.values()].find(s => s.type === 'transport' && s.selectedCandidatePairId);
      const pair = report.get(transport?.selectedCandidatePairId) || [...report.values()].find(s => s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated);
      const lines = [];
      for (const direction of ['outbound-rtp','inbound-rtp']) {
        const rows = [...report.values()].filter(r=>r.type===direction && r.kind==='video' && !r.isRemote && r.mid != null && (r.mid === this.video?.mid || r.mid === this.cameraVideo?.mid));
        for (const r of rows) {
          const camera = r.mid === this.cameraVideo?.mid;
          const active = direction === 'outbound-rtp' ? (camera ? this.state.cameraOn : this.state.sharing) : (camera ? this.state.remoteCameraOn : this.state.remoteSharing);
          const previous=this.videoSamples.get(r.id);
          const rate=videoRate(r,previous);this.videoSamples.set(r.id,{...r,...rate});
          if (active && direction === 'outbound-rtp') await this.adaptMedia(camera, r, previous);
          if (!active || !r.frameWidth) continue;
          const why={cpu:'设备编码性能受限',bandwidth:'网络带宽受限',other:'编码器调整中'}[r.qualityLimitationReason];
          lines.push(`${camera?'摄像头':'屏幕'}${direction==='outbound-rtp'?'发送':'接收'} ${r.frameWidth}×${r.frameHeight} · ${rate.fps===null?'测量中':rate.fps.toFixed(1)+' FPS'} · ${rate.mbps===null?'测量中':rate.mbps.toFixed(2)+' Mbps'}${why?' · '+why:''}`);
          const timing=videoTiming(r,previous);if(timing)lines.push(timing);
          if (direction === 'outbound-rtp' && !camera) {
            const q=this.state.quality, source=this.display?.getVideoTracks()[0]?.getSettings() || {};
            if (q.resolutionLocked && source.width && source.height && (Math.max(r.frameWidth,r.frameHeight)<Math.max(source.width,source.height) || Math.min(r.frameWidth,r.frameHeight)<Math.min(source.width,source.height)))
              lines.push('实际发送尺寸低于锁定采集尺寸；请检查设备能力与网络，设置未被改写');
            if (q.fpsLocked && rate.fps!==null && rate.fps<q.fps*.85)
              lines.push('实际帧率低于锁定目标；静止画面、采集、设备或网络可能限制出帧，设置未被改写');
          }
        }
      }
      if (this.screenLoad.level && this.state.sharing) lines.push('正在减轻共享采集负担，稳定后逐级恢复；手动锁定不变');
      if (this.cameraLoad.level && this.state.cameraOn) lines.push('摄像头已降低采集负担，稳定后逐级恢复清晰度');
      const patch={mediaStats:lines.join('；') || ((this.state.sharing||this.state.remoteSharing)?'正在测量视频…':'')};
      if (!pair) {this.update(patch);return;}
      const relay = [report.get(pair.localCandidateId), report.get(pair.remoteCandidateId)].some(c => c?.candidateType === 'relay');
      this.update({ ...patch, network: `${relay ? '中转连接' : '直接连接'}${pair.currentRoundTripTime == null ? '' : ` · 网络往返 ${Math.round(pair.currentRoundTripTime * 1000)} ms`}` });
    } catch { /* Statistics do not control call lifetime. */ }
    finally { this.statsBusy = false; }
  }
  async adaptMedia(camera, current, previous) {
    if (this.closed || !this.state.connected || this.state.busy || this.state.qualityBusy || this.state.cameraBusy) return;
    const key = camera ? 'cameraLoad' : 'screenLoad', old = this[key];
    if (performance.now() < (this[key+'RetryAt'] || 0)) return;
    const target = camera ? cameraFormats[old.level] : effectiveQuality(this.state.quality,old.level);
    const next = videoLoad(old,current,previous,target.fps);
    if (next.level === old.level) { this[key]=next; return; }
    if (!camera && this.state.quality.resolutionLocked && this.state.quality.fpsLocked) return;
    const stream = camera ? this.camera : this.display;
    if (!stream) return;
    this.adjustingMedia = true;
    const apply = async level => {
      if (!camera) return this.encoding(effectiveQuality(this.state.quality,level));
      const track=stream.getVideoTracks()[0], q=cameraFormats[level];
      await track.applyConstraints({width:{ideal:q.width,max:q.width},height:{ideal:q.height,max:q.height},frameRate:{ideal:q.fps,max:q.fps}});
      if (this.closed || this.camera !== stream) return;
      const p=this.cameraVideo.sender.getParameters();
      for (const e of p.encodings || []) e.maxFramerate=q.fps;
      if (p.encodings?.length) await this.cameraVideo.sender.setParameters(p);
    };
    try {
      await apply(next.level);
      if (!this.closed && (camera?this.camera:this.display)===stream) this[key]=next;
    } catch {
      if (!this.closed && (camera?this.camera:this.display)===stream) {
        try { await apply(old.level); } catch {
          if (!this.closed && (camera?this.camera:this.display)===stream) {
            await (camera ? this.stopCamera() : this.stopSharing());
            this.update({error:camera?'摄像头调整失败，已停止视频，请重新开启。':'共享画质调整失败，已停止共享，请重新开启。'});
          }
          return;
        }
        if (this.closed || (camera?this.camera:this.display)!==stream) return;
        this[key]={...old,healthy:0};
        this[key+'RetryAt']=performance.now()+15000;
      }
    } finally { this.adjustingMedia = false; }
  }
  fail(message) { this.end(message, true, true); }
  end(message = '通话已结束', notify = true, error = false) {
    if (this.closed) return;
    this.closed = true; this.abort.abort();
    globalThis.removeEventListener?.('online',this.networkAvailable); this.retryConnection = null;
    clearInterval(this.statsTimer); clearTimeout(this.connectionTimer); clearTimeout(this.reconnectTimer);
    this.stopCamera(); this.channel?.close(); this.stopCapture(); this.mic?.getTracks().forEach(t => { t.onended = null; t.stop(); });
    this.mix?.stream.getTracks().forEach(t => t.stop()); this.pc?.close(); this.audio?.close().catch(() => {});
    if (notify && this.credentials) this.request('DELETE', this.path(), undefined, 1500, true).catch(() => {});
    this.changed({ ...this.state, ended: true, connected: false, sharing: false, cameraOn:false, localCamera:null, remoteCamera:null, messages:[], chatReady:false, message, error: error ? message : '' });
  }
}
