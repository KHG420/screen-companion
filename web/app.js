import { Call, friendly, qualities, qualityControlLabel } from './call.js';
const $ = id => document.getElementById(id);
let call, starting = false, videoTrack, audioTrack, chatMessages = [], chatOpen = false, unread = 0;
let cameraHidden = false, frameReady = false, frameCallback, frameGeneration = 0, qualityWaiting = false;
let wakeLock, wakePending = false, controlsTimer;
const messageNodes = new Map();
const initialStatus = '双人屏幕共享与语音';
function message(text = '') { $('message').textContent = text; $('message').hidden = !text; }
function updateScreenStatus() {
  const s=call?.state;
  let text='';
  if (s?.remoteSharing || s?.sharing) {
    if (!s.connected) text='网络中断，正在恢复画面和声音…';
    else if (!frameReady) text='正在等待首帧画面…';
    else if (videoTrack?.muted) text='共享源暂时没有画面，正在等待恢复…';
    else if (s.qualityBusy || qualityWaiting) text='正在应用画质，等待画面更新…';
  }
  $('screen-status').textContent=text; $('screen-status').hidden=!text;
}
function waitForFrame(reset = false) {
  const el=$('screen'), generation=++frameGeneration;
  if (frameCallback !== undefined) el.cancelVideoFrameCallback?.(frameCallback);
  if(reset)frameReady=false;
  const ready=()=>{if(generation!==frameGeneration)return;frameReady=true;qualityWaiting=false;updateScreenStatus();};
  if(el.requestVideoFrameCallback) frameCallback=el.requestVideoFrameCallback(ready);
  else if(el.readyState>=2)ready();
  else el.onloadeddata=ready;
  updateScreenStatus();
}
function atChatBottom() { const log=$('chat-log');return log.scrollHeight-log.scrollTop-log.clientHeight<40; }
function readChat() { unread=0;$('chat-toggle').textContent='聊天';$('new-messages').hidden=true; }
function setChat(open) {
  chatOpen=open;$('chat-panel').hidden=!open;$('chat-toggle').setAttribute('aria-expanded',String(open));
  if(open){$('chat-log').scrollTop=$('chat-log').scrollHeight;readChat();$('chat-input').focus();}
  else $('chat-toggle').focus();
}
function renderMessages(messages) {
  if(messages===chatMessages)return;
  const log=$('chat-log'),bottom=atChatBottom(),previous=new Set(chatMessages),keep=new Set(messages);
  const added=messages.filter(m=>!previous.has(m));
  if(!messages.length){chatMessages=messages;messageNodes.clear();const hint=document.createElement('p');hint.className='hint';hint.textContent='发一句问候吧。';log.replaceChildren(hint);return;}
  if(!messageNodes.size)log.replaceChildren();
  const oldHeight=log.scrollHeight,oldTop=log.scrollTop;
  for(const [m,node] of messageNodes)if(!keep.has(m)){node.remove();messageNodes.delete(m);}
  if(!bottom)log.scrollTop=Math.max(0,oldTop-(oldHeight-log.scrollHeight));
  for(const m of added){const row=document.createElement('p');row.className=m.mine?'chat-message mine':'chat-message';const label=document.createElement('strong');label.textContent=m.mine?'你':'对方';const body=document.createElement('span');body.textContent=m.text;row.append(label,body);log.append(row);messageNodes.set(m,row);}
  chatMessages=messages;
  if(chatOpen && (bottom || added.at(-1)?.mine)){log.scrollTop=log.scrollHeight;readChat();}
  else {unread+=added.filter(m=>!m.mine).length;$('chat-toggle').textContent=unread?`聊天 · ${unread} 条未读`:'聊天';$('new-messages').hidden=!unread;}
}
async function keepAwake() {
  const needed=!!call?.state.remoteSharing && document.visibilityState==='visible';
  if(!needed){const lock=wakeLock;wakeLock=null;await lock?.release().catch(()=>{});return;}
  if(wakeLock || wakePending || !navigator.wakeLock)return;
  wakePending=true;
  try { const lock=await navigator.wakeLock.request('screen');
    if(!call?.state.remoteSharing || document.visibilityState!=='visible')await lock.release();
    else {wakeLock=lock;lock.addEventListener('release',()=>{if(wakeLock===lock)wakeLock=null;});}
  } catch { /* Viewing still works when the platform denies a wake lock. */ }
  finally {wakePending=false;}
}
function render(s) {
  if (s.ended) {
    call=null;chatMessages=[];messageNodes.clear();readChat();chatOpen=false;cameraHidden=false;
    $('chat-panel').hidden=true;$('chat-toggle').setAttribute('aria-expanded','false');$('quality-details').open=false;
    $('chat-log').replaceChildren();$('chat-input').value='';
    for(const id of ['local-camera','remote-camera'])$(id).srcObject=null;
    if(videoTrack){videoTrack.removeEventListener('mute',updateScreenStatus);videoTrack.removeEventListener('unmute',updateScreenStatus);}
    videoTrack=audioTrack=null;frameReady=false;qualityWaiting=false;frameGeneration++;
    if(frameCallback!==undefined)$('screen').cancelVideoFrameCallback?.(frameCallback);
    $('play-audio').hidden=true;$('screen').srcObject=$('remote-audio').srcObject=null;
    $('call').hidden=true;$('home').hidden=false;document.body.classList.remove('in-call');
    $('status').textContent=initialStatus;$('create').disabled=$('join').disabled=false;
    keepAwake();message(s.message);if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});
    if(document.pictureInPictureElement)document.exitPictureInPicture().catch(()=>{});
    $('create').focus();return;
  }
  $('home').hidden=true;$('call').hidden=false;document.body.classList.add('in-call');
  $('camera').disabled=!!s.cameraBusy || (!s.cameraOn && (!s.connected || !s.chatReady));
  $('camera').textContent=s.cameraBusy?'正在开启…':s.cameraOn?'关闭摄像头':'开启摄像头';
  $('camera').setAttribute('aria-pressed',String(s.cameraOn));
  for(const [id,track,enabled] of [['local-camera',s.localCamera,s.cameraOn],['remote-camera',s.remoteCamera,s.remoteCameraOn]]){
    const el=$(id);$(id+'-frame').hidden=!enabled;
    if(el.srcObject?.getVideoTracks()[0]!==track){el.srcObject=track?new MediaStream([track]):null;if(track)el.play().catch(()=>{});}
  }
  const cameraOn=s.cameraOn||s.remoteCameraOn,visible=s.remoteSharing||s.sharing;
  $('cameras').hidden=!cameraOn||cameraHidden;
  $('stage').classList.toggle('camera-only',cameraOn&&!cameraHidden&&!visible);
  $('camera-preview').hidden=!cameraOn;$('camera-preview').textContent=cameraHidden?'显示摄像头画面':'收起摄像头画面';
  $('camera-preview').setAttribute('aria-pressed',String(cameraHidden));
  $('chat-send').disabled=!s.connected||!s.chatReady;
  $('chat-status').textContent=s.connected&&s.chatReady?'消息仅在本次通话保留':'文字通道正在连接…';
  renderMessages(s.messages);
  $('status').textContent=s.status;$('room-code').textContent=s.roomId?.replace(/(.{4})/,'$1 ')||'正在连接';
  $('copy').disabled=$('invite').disabled=!s.roomId;$('network').textContent=s.network||'等待连接';
  $('share').disabled=s.busy||s.qualityBusy||(!s.sharing&&(!s.connected||s.remoteSharing||!navigator.mediaDevices?.getDisplayMedia));
  $('share').textContent=s.busy?'正在准备共享…':s.sharing?'停止共享':s.remoteSharing?'对方正在共享':'共享我的屏幕';
  $('mute').textContent=s.micBusy?'等待麦克风授权…':!s.micAvailable?'重试麦克风':s.muted?'开启麦克风':'关闭麦克风';
  $('mute').setAttribute('aria-pressed',String(s.muted));$('mute').disabled=s.micBusy||!s.roomId;
  $('mic-status').textContent=s.micIssue||'';$('mic-status').hidden=!s.micIssue;
  $('screen').hidden=!visible;$('empty').hidden=visible||(cameraOn&&!cameraHidden);$('fullscreen').hidden=!visible;
  $('pip').hidden=!visible||!document.pictureInPictureEnabled;
  $('screen-label').hidden=!visible;$('screen-label').textContent=s.sharing?'你正在共享 · 本地预览':'对方正在共享';
  $('stage-title').textContent=s.connected?'已经连上啦':s.status;
  $('stage-description').textContent=s.connected?(s.muted?'可以听对方说话，也可以开启麦克风。选一个屏幕，一起看。':'可以说话了。选一个屏幕，一起看。'):'把房间号或邀请链接发给对方，等 TA 一起同屏。';
  const video=visible?(s.sharing?s.localVideo:s.remoteVideo):null;
  if(video!==videoTrack){
    if(videoTrack){videoTrack.removeEventListener('mute',updateScreenStatus);videoTrack.removeEventListener('unmute',updateScreenStatus);}
    videoTrack=video;$('screen').srcObject=video?new MediaStream([video]):null;
    if(video){video.addEventListener('mute',updateScreenStatus);video.addEventListener('unmute',updateScreenStatus);waitForFrame(true);$('screen').play().catch(()=>{});}else frameReady=false;
  }
  updateScreenStatus();keepAwake();
  if(s.remoteAudio&&audioTrack!==s.remoteAudio){audioTrack=s.remoteAudio;$('remote-audio').srcObject=new MediaStream([audioTrack]);$('remote-audio').play().then(()=>$('play-audio').hidden=true).catch(()=>$('play-audio').hidden=false);}
  $('system-mute').hidden=!s.sharing||!s.systemAudio;$('system-mute').textContent=s.systemMuted?'开启共享声音':'关闭共享声音';$('system-mute').setAttribute('aria-pressed',String(s.systemMuted));
  $('share-hint').textContent=s.sharing?(s.systemAudio?(s.systemMuted?'共享声音已关闭，麦克风独立控制。':'正在共享画面和所选音频。关闭麦克风不会停止共享声音。'):'正在共享画面，当前未采集共享音频。需要声音时，停止后重新选择标签页并勾选分享音频。'):'共享声音：Chrome / Edge 选择标签页并勾选“同时分享音频”。其他共享方式取决于浏览器和系统。';
  const q=s.quality;$('quality-target').textContent=`自己共享的目标：长边 ${q.width} × 短边 ${q.height} · ${q.fps} FPS · 上限 ${q.bitrate/1e6} Mbps · ${qualityControlLabel(q)}`;
  $('media-stats').textContent=s.mediaStats||'';$('quality-note').textContent=s.qualityNote||'';
  $('quality').disabled=$('apply-quality').disabled=!!(s.busy||s.qualityBusy);
  message(s.error||s.notice||'');
}
async function begin(room) {
  if (call || starting) return;
  if (!isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) { message('请使用新版电脑浏览器，通过 HTTPS 安全地址打开。'); return; }
  if (room && !/^\d{8}$/.test(room)) { message('请输入八位数字房间号。'); return; }
  starting = true; $('quality').value = 'hd'; fillQuality(qualities.hd); $('quality-result').textContent=''; message(); $('create').disabled = $('join').disabled = true;
  const session = new Call(new URL('api', location.href).href, render); call = session;
  try { render(session.state); await session.start(room); }
  catch (error) { session.fail(friendly(error)); }
  finally { starting = false; }
}
$('create').onclick = () => begin();
$('join-form').onsubmit = e => { e.preventDefault(); begin($('room-input').value.replace(/\s/g, '')); };
$('room-input').oninput = e => { e.target.value = e.target.value.replace(/[^0-9 ]/g, '').slice(0, 9); };
$('camera').onclick = () => call?.toggleCamera();
$('chat-form').onsubmit = e => { e.preventDefault(); try { if(!call)return; call.sendChat($('chat-input').value); $('chat-input').value=''; message(); } catch(error) { message(friendly(error)); } };
$('hangup').onclick = () => call?.end('你已结束通话');
$('mute').onclick = () => call?.mute();
$('share').onclick = () => call?.state.sharing ? call.stopSharing() : call?.share();
function fillQuality(q) {
  $('long-edge').value=q.width; $('short-edge').value=q.height; $('fps').value=q.fps; $('bitrate').value=q.bitrate/1e6; $('priority').value=q.priority; $('lock-resolution').checked=!!q.resolutionLocked; $('lock-fps').checked=!!q.fpsLocked; updateLocks();
  const res=`${q.width},${q.height}`; $('resolution').value=[...$('resolution').options].some(o=>o.value===res)?res:'custom';
}
function updateLocks() {
  $('priority').disabled=$('lock-resolution').checked || $('lock-fps').checked;
  $('lock-note').textContent=$('priority').disabled?'手动锁定优先；取消锁定才允许自动调整该项。':'未锁定的参数按所选自动策略调整。';
}
function markCustom() { $('quality').value='custom'; updateLocks(); }
$('lock-resolution').onchange=$('lock-fps').onchange=markCustom;
$('fps').oninput=()=>{$('lock-fps').checked=true;markCustom();};
$('bitrate').oninput=$('priority').onchange=markCustom;
async function applyQuality(q, preset='custom') {
  const session=call; if(!session)return;
  try { await session.quality(q); if(call!==session)return; fillQuality(call.state.quality); $('quality').value=preset; $('quality-result').textContent='目标参数已应用；实际发送画质见下方统计。'; if(call.state.sharing){qualityWaiting=true;waitForFrame();} }
  catch(error){if(call!==session)return; message(friendly(error)); $('quality-result').textContent=friendly(error); if(call)$('quality').value=Object.keys(qualities).find(k=>JSON.stringify(qualities[k])===JSON.stringify(call.state.quality)) || 'custom';}
}
$('quality').onchange=e=>applyQuality(e.target.value,e.target.value);
$('resolution').onchange=e=>{if(e.target.value!=='custom'){const [w,h]=e.target.value.split(',');$('long-edge').value=w;$('short-edge').value=h;$('lock-resolution').checked=true;markCustom();}};
$('long-edge').oninput=$('short-edge').oninput=()=>{$('resolution').value='custom';$('lock-resolution').checked=true;markCustom();};
$('quality-form').onsubmit=e=>{e.preventDefault();applyQuality({width:Number($('long-edge').value),height:Number($('short-edge').value),fps:Number($('fps').value),bitrate:Math.round(Number($('bitrate').value)*1e6),priority:$('priority').value,resolutionLocked:$('lock-resolution').checked,fpsLocked:$('lock-fps').checked});};
$('copy').onclick = async () => {
  try { await navigator.clipboard.writeText(call.state.roomId); $('copy').textContent = '已复制'; setTimeout(() => $('copy').textContent = '复制房间号', 1800); }
  catch { message('无法自动复制，请手动选中房间号复制。'); }
};
$('fullscreen').onclick = e => { (document.fullscreenElement ? document.exitFullscreen() : $('stage').requestFullscreen()).then(()=>{if(e.detail>0)$('stage').focus();}).catch(() => message('浏览器暂不支持全屏，请放大窗口查看。')); };
$('play-audio').onclick = () => { call?.audio.resume().catch(()=>{}); $('remote-audio').play().then(() => $('play-audio').hidden = true).catch(() => message('声音播放失败，请检查浏览器的声音权限。')); };
window.addEventListener('pagehide', () => call?.end());

$('chat-toggle').onclick=()=>setChat(!chatOpen);
$('close-chat').onclick=()=>setChat(false);
$('chat-panel').addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();setChat(false);}});
$('chat-log').onscroll=()=>{if(chatOpen&&atChatBottom())readChat();};
$('new-messages').onclick=()=>{$('chat-log').scrollTop=$('chat-log').scrollHeight;readChat();};
$('settings-toggle').onclick=()=>{const el=$('quality-details');el.open=!el.open;if(el.open){el.scrollIntoView({block:'nearest'});el.querySelector('summary').focus();}};
$('quality-details').ontoggle=()=>$('settings-toggle').setAttribute('aria-expanded',String($('quality-details').open));
$('system-mute').onclick=()=>call?.muteSystemAudio();
$('camera-preview').onclick=()=>{cameraHidden=!cameraHidden;if(call)render(call.state);};
$('invite').onclick=async()=>{if(!call?.state.roomId)return;const url=new URL('.',location.href);url.hash=new URLSearchParams({room:call.state.roomId}).toString();try{await navigator.clipboard.writeText(url.href);message('邀请链接已复制。电脑打开即可填写房间，安卓可粘贴到“邀请链接”入口。');}catch{message('无法复制邀请，请改用房间号。');}};
const invitedRoom=new URLSearchParams(location.hash.slice(1)).get('room');
if(/^\d{8}$/.test(invitedRoom||'')){$('room-input').value=invitedRoom;$('join-hint').textContent='邀请已就绪，点击加入房间。暂不开麦也能观看和聊天。';}
$('pip').onclick=async()=>{try{if(document.pictureInPictureElement)await document.exitPictureInPicture();else await $('screen').requestPictureInPicture();}catch{message('画面尚未就绪或浏览器不支持小窗，请稍后重试。');}};
function showStageControls(){clearTimeout(controlsTimer);$('stage').classList.remove('controls-hidden');if(document.fullscreenElement)controlsTimer=setTimeout(()=>{if(!$('stage').contains(document.activeElement)||document.activeElement===$('stage'))$('stage').classList.add('controls-hidden');},3000);}
for(const type of ['pointermove','pointerdown','keydown','focusin'])$('stage').addEventListener(type,showStageControls);
document.addEventListener('fullscreenchange',()=>{$('fullscreen').textContent=document.fullscreenElement?'退出全屏':'全屏查看';showStageControls();});
document.addEventListener('visibilitychange',keepAwake);
