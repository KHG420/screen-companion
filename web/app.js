import { Call, friendly, qualities } from './call.js';
const $ = id => document.getElementById(id);
let call, starting = false, videoTrack, audioTrack;
const initialStatus = '双人屏幕共享与语音';
function message(text = '') { $('message').textContent = text; $('message').hidden = !text; }
function render(s) {
  if (s.ended) {
    call = null; videoTrack = audioTrack = null; $('play-audio').hidden = true;
    $('screen').srcObject = $('remote-audio').srcObject = null;
    $('call').hidden = true; $('home').hidden = false; document.body.classList.remove('in-call');
    $('status').textContent = initialStatus; $('create').disabled = false; $('join').disabled = false;
    message(s.message); if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); return;
  }
  $('home').hidden = true; $('call').hidden = false; document.body.classList.add('in-call');
  $('status').textContent = s.status; $('room-code').textContent = s.roomId?.replace(/(.{4})/, '$1 ') || '正在连接';
  $('copy').disabled = !s.roomId; $('network').textContent = s.network || '等待连接';
  $('share').disabled = s.busy || s.qualityBusy || (!s.sharing && (!s.connected || s.remoteSharing || !navigator.mediaDevices?.getDisplayMedia));
  $('share').textContent = s.busy ? '正在更新共享…' : s.sharing ? '停止共享' : s.remoteSharing ? '对方正在共享' : '共享我的屏幕';
  $('mute').textContent = s.muted ? '开启麦克风' : '关闭麦克风'; $('mute').setAttribute('aria-pressed', String(s.muted));
  $('mute').disabled = !s.roomId;
  const visible = s.remoteSharing || s.sharing;
  $('screen').hidden = !visible; $('empty').hidden = visible; $('fullscreen').hidden = !visible;
  $('screen-label').hidden = !visible; $('screen-label').textContent = s.sharing ? '你正在共享 · 本地预览' : '对方正在共享';
  $('stage-title').textContent = s.connected ? '已经连上啦' : s.status;
  $('stage-description').textContent = s.connected ? '可以说话了。选一个屏幕，一起看。' : '把上方房间号告诉对方，等 TA 一起同屏。';
  const video = s.sharing ? s.localVideo : s.remoteVideo;
  if (video !== videoTrack) { videoTrack = video; $('screen').srcObject = video ? new MediaStream([video]) : null; $('screen').play().catch(() => {}); }
  if (s.remoteAudio && audioTrack !== s.remoteAudio) {
    audioTrack = s.remoteAudio; $('remote-audio').srcObject = new MediaStream([audioTrack]);
    $('remote-audio').play().then(() => $('play-audio').hidden = true).catch(() => $('play-audio').hidden = false);
  }
  $('share-hint').textContent = s.sharing ? (s.systemAudio ? '正在共享画面和所选音频。关闭麦克风不会停止共享声音。' : '正在共享画面，当前未采集共享音频。需要声音时，停止后重新选择标签页并勾选分享音频。') : '共享声音：优先使用 Chrome / Edge，选择标签页并勾选“同时分享音频”。其他共享方式取决于浏览器和系统。';
  const q=s.quality;
  $('quality-target').textContent=`自己共享的目标：长边 ${q.width} × 短边 ${q.height} · ${q.fps} FPS · 上限 ${q.bitrate/1e6} Mbps`;
  $('media-stats').textContent=s.mediaStats || '';
  $('quality-note').textContent=s.qualityNote || '';
  $('quality').disabled=$('apply-quality').disabled=!!(s.busy||s.qualityBusy);
  message(s.error || s.notice || '');
}
async function begin(room) {
  if (call || starting) return;
  if (!isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) { message('请使用新版电脑浏览器，通过 HTTPS 安全地址打开。'); return; }
  if (room && !/^\d{8}$/.test(room)) { message('请输入八位数字房间号。'); return; }
  starting = true; $('quality').value = 'auto'; fillQuality(qualities.auto); $('quality-result').textContent=''; message(); $('create').disabled = $('join').disabled = true;
  const session = new Call(new URL('api', location.href).href, render); call = session;
  try { render(session.state); await session.start(room); }
  catch (error) { session.fail(friendly(error)); }
  finally { starting = false; }
}
$('create').onclick = () => begin();
$('join-form').onsubmit = e => { e.preventDefault(); begin($('room-input').value.replace(/\s/g, '')); };
$('room-input').oninput = e => { e.target.value = e.target.value.replace(/[^0-9 ]/g, '').slice(0, 9); };
$('hangup').onclick = () => call?.end('你已结束通话');
$('mute').onclick = () => call?.mute();
$('share').onclick = () => call?.state.sharing ? call.stopSharing() : call?.share();
function fillQuality(q) {
  $('long-edge').value=q.width; $('short-edge').value=q.height; $('fps').value=q.fps; $('bitrate').value=q.bitrate/1e6; $('priority').value=q.priority;
  const res=`${q.width},${q.height}`; $('resolution').value=[...$('resolution').options].some(o=>o.value===res)?res:'custom';
}
async function applyQuality(q, preset='custom') {
  const session=call; if(!session)return;
  try { await session.quality(q); if(call!==session)return; fillQuality(call.state.quality); $('quality').value=preset; $('quality-result').textContent='已应用目标参数。实际发送数据见下方。'; }
  catch(error){if(call!==session)return; message(friendly(error)); $('quality-result').textContent=friendly(error); if(call)$('quality').value=Object.keys(qualities).find(k=>JSON.stringify(qualities[k])===JSON.stringify(call.state.quality)) || 'custom';}
}
$('quality').onchange=e=>applyQuality(e.target.value,e.target.value);
$('resolution').onchange=e=>{if(e.target.value!=='custom'){const [w,h]=e.target.value.split(',');$('long-edge').value=w;$('short-edge').value=h;}};
$('long-edge').oninput=$('short-edge').oninput=()=>{$('resolution').value='custom';};
$('quality-form').onsubmit=e=>{e.preventDefault();applyQuality({width:Number($('long-edge').value),height:Number($('short-edge').value),fps:Number($('fps').value),bitrate:Math.round(Number($('bitrate').value)*1e6),priority:$('priority').value});};
$('copy').onclick = async () => {
  try { await navigator.clipboard.writeText(call.state.roomId); $('copy').textContent = '已复制'; setTimeout(() => $('copy').textContent = '复制房间号', 1800); }
  catch { message('无法自动复制，请手动选中房间号复制。'); }
};
$('fullscreen').onclick = () => { (document.fullscreenElement ? document.exitFullscreen() : $('stage').requestFullscreen()).catch(() => message('浏览器暂不支持全屏，请放大窗口查看。')); };
$('play-audio').onclick = () => $('remote-audio').play().then(() => $('play-audio').hidden = true).catch(() => message('声音播放失败，请检查浏览器的声音权限。'));
window.addEventListener('pagehide', () => call?.end());
