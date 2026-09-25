import test from 'node:test';
import assert from 'node:assert/strict';
import { Call } from '../call.js';

test('overlapping reconnect requests produce one offer until answer settles', async () => {
  const call = new Call('/api');
  let release, offers = 0, signals = 0;
  call.pc = {
    signalingState: 'stable',
    createOffer: async () => { offers++; return new Promise(resolve => release = () => resolve({type:'offer',sdp:'test'})); },
    setLocalDescription: async description => { call.pc.localDescription = description; call.pc.signalingState = 'have-local-offer'; }
  };
  call.signal = async () => { signals++; };
  const first = call.offer(true);
  await call.offer(true);
  assert.equal(offers, 1);
  release(); await first;
  await call.offer(true);
  assert.equal(offers, 1);
  assert.equal(signals, 1);
  call.pc.signalingState = 'stable';
  const next = call.offer(true); release(); await next;
  assert.equal(offers, 2);
});

test('failed offer releases negotiation guard for retry', async () => {
  const call = new Call('/api');
  call.pc = {signalingState:'stable',createOffer:async()=>{throw new Error('interrupted');}};
  await assert.rejects(call.offer(), /interrupted/);
  assert.equal(call.makingOffer,false);
});

test('ending while microphone permission is pending stops the late stream', async () => {
  const oldAudio=globalThis.AudioContext, oldNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  let resolveMic,stopped=false,changed;
  globalThis.AudioContext=class {async resume(){} async close(){}};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:()=>new Promise(r=>resolveMic=r)}}});
  try {
    const call=new Call('/api',s=>changed=s);
    const start=call.enableMicrophone();
    call.end();
    resolveMic({getTracks:()=>[{stop:()=>stopped=true}]});
    await start;
    assert.equal(stopped,true);assert.equal(changed.ended,true);assert.equal(call.credentials,undefined);
  } finally {
    globalThis.AudioContext=oldAudio;
    if(oldNavigator)Object.defineProperty(globalThis,'navigator',oldNavigator);else delete globalThis.navigator;
  }
});

test('quality validates independent targets, including 4K and arbitrary frame rates', async () => {
  const {validateQuality,qualities,captureBounds}=await import('../call.js');
  const q=validateQuality({...qualities.uhd,fps:25,bitrate:17_500_000});
  assert.equal(q.fps,25); assert.equal(q.width,3840); assert.equal(q.bitrate,17_500_000);
  assert.deepEqual(captureBounds(q,{width:1080,height:1920}),{width:2160,height:3840});
  for(const patch of [{fps:0},{fps:61},{fps:24.5},{width:3841},{height:0},{bitrate:Infinity},{priority:'invalid'}])
    assert.throws(()=>validateQuality({...q,...patch}));
});

test('live quality updates sender and capture, restoring previous settings on rejection', async () => {
  const {qualities}=await import('../call.js');
  const c=new Call('/api');let constraints,parameters,fail=false;
  c.display={getVideoTracks:()=>[{getSettings:()=>({width:3840,height:2160}),getConstraints:()=>constraints||{},applyConstraints:async p=>{constraints=p;}}]};
  c.video={sender:{track:{},getParameters:()=>({encodings:[{}]}),setParameters:async p=>{if(fail&&p.encodings[0].maxFramerate===60)throw new Error('unsupported');parameters=p;}}};
  await c.quality({...qualities.uhd,fps:25});
  assert.equal(constraints.frameRate.max,25);assert.equal(parameters.encodings[0].maxBitrate,24_000_000);
  fail=true;await assert.rejects(c.quality({...qualities.uhd,fps:60}));
  assert.equal(c.state.quality.fps,25);assert.equal(parameters.encodings[0].maxFramerate,25);assert.equal(c.state.qualityBusy,false);
});

test('video rates use interval deltas and ignore counter resets',async()=>{
  const {videoRate}=await import('../call.js');
  const a=videoRate({timestamp:1000,bytesSent:100,framesEncoded:1});
  const b=videoRate({timestamp:3000,bytesSent:1_000_100,framesEncoded:51},a);
  assert.equal(b.fps,25);assert.equal(b.mbps,4);
  assert.equal(videoRate({timestamp:4000,bytesSent:0,framesEncoded:0},b).fps,null);
});

test('chat validates input, bounds history and ignores malformed peer data', () => {
  const c=new Call('/api');const sent=[];
  const channel={label:'companion-v1',readyState:'open',bufferedAmount:0,send:v=>sent.push(JSON.parse(v))};
  c.state.connected=true;c.bindChannel(channel);channel.onopen();
  c.sendChat('  你好 🐶  ');
  assert.equal(sent.at(-1).text,'你好 🐶');assert.equal(c.state.messages[0].mine,true);
  assert.throws(()=>c.sendChat(' '));assert.throws(()=>c.sendChat('x'.repeat(2001)));
  for(const data of ['{','{"type":"chat","text":5}',JSON.stringify({type:'chat',text:'x'.repeat(2001)}),JSON.stringify({type:'camera',enabled:'true'})])channel.onmessage({data});
  assert.equal(c.state.messages.length,1);assert.equal(c.state.remoteCameraOn,false);
  for(let i=0;i<220;i++)channel.onmessage({data:JSON.stringify({type:'chat',text:String(i)})});
  assert.equal(c.state.messages.length,200);assert.equal(c.state.messages[0].text,'20');
  channel.onmessage({data:JSON.stringify({type:'camera',enabled:true})});assert.equal(c.state.remoteCameraOn,true);
  channel.bufferedAmount=65537;assert.throws(()=>c.sendChat('busy'));
  c.state.connected=false;assert.throws(()=>c.sendChat('offline'));
});

test('hangup while camera permission pending stops late capture', async () => {
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  let resolve,stopped=false,replaced=false;
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:()=>new Promise(r=>resolve=r)}}});
  try {
    const c=new Call('/api');c.state.connected=c.state.chatReady=true;
    c.cameraVideo={sender:{replaceTrack:async()=>{replaced=true;}}};
    const pending=c.toggleCamera();c.end();
    resolve({getTracks:()=>[{stop:()=>stopped=true}]});await pending;
    assert.equal(stopped,true);assert.equal(replaced,false);
  }finally{if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});

test('balanced screen content permits motion adaptation and bitrate-only changes preserve capture', async () => {
  const c=new Call('/api');let constraints={},captures=0,params;
  const track={getSettings:()=>({width:1920,height:1080}),getConstraints:()=>constraints,applyConstraints:async q=>{constraints=q;captures++;}};
  c.display={getVideoTracks:()=>[track]};c.video={sender:{track,getParameters:()=>({encodings:[{}]}),setParameters:async p=>{params=p;}}};
  await c.quality('auto');assert.equal(track.contentHint,'motion');assert.equal(params.degradationPreference,'balanced');assert.equal(captures,1);
  await c.quality({...c.state.quality,bitrate:4_000_000});assert.equal(captures,1);assert.equal(params.encodings[0].maxBitrate,4_000_000);
  await c.quality({...c.state.quality,priority:'maintain-resolution'});assert.equal(track.contentHint,'detail');assert.equal(captures,1);
  await c.quality({...c.state.quality,fps:24});assert.equal(captures,2);assert.equal(constraints.frameRate.max,24);
});

test('timing reports interval averages and ignores missing/reset counters',async()=>{
  const {videoTiming}=await import('../call.js');
  const a={type:'outbound-rtp',timestamp:1000,framesEncoded:10,totalEncodeTime:.2,packetsSent:100,totalPacketSendDelay:1};
  const b={...a,timestamp:4000,framesEncoded:70,totalEncodeTime:1.4,packetsSent:400,totalPacketSendDelay:2.5};
  assert.equal(videoTiming(b,a),'编码/帧 20 ms · 发送排队/包 5 ms');
  assert.equal(videoTiming({...a,timestamp:5000},b),'');assert.equal(videoTiming(a), '');assert.equal(videoTiming(a,a),'');
  assert.equal(videoTiming({type:'inbound-rtp',timestamp:2000,framesDecoded:20,totalDecodeTime:.2,jitterBufferEmittedCount:20,jitterBufferDelay:1,freezeCount:1}, {timestamp:1000,framesDecoded:10,totalDecodeTime:.1,jitterBufferEmittedCount:10,jitterBufferDelay:.5,freezeCount:0}), '解码/帧 10 ms · 接收缓冲/帧 50 ms · 本周期冻结 1 次');
});

test('4K60 applies motion capture and sender limits before attaching the first screen frame', async()=>{
  const {qualities}=await import('../call.js');const old=Object.getOwnPropertyDescriptor(globalThis,'navigator');let constraints,parameters;const order=[];
  const track={readyState:'live',getSettings:()=>({width:3840,height:2160}),getConstraints:()=>constraints||{},applyConstraints:async p=>{constraints=p;order.push('capture');},stop(){}};
  const stream={getVideoTracks:()=>[track],getAudioTracks:()=>[],getTracks:()=>[track]};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getDisplayMedia:async()=>stream}}});
  try{
    const c=new Call('/api');c.credentials={roomId:'12345678'};c.state.connected=true;c.request=async()=>({});
    c.video={sender:{track:null,getParameters:()=>({encodings:[{}]}),setParameters:async p=>{parameters=p;order.push('sender');},replaceTrack:async t=>{assert.equal(t,track);order.push('attach');}}};
    await c.quality('uhd60');await c.share();assert.equal(c.state.sharing,true);
    assert.deepEqual(order,['capture','sender','attach']);assert.equal(track.contentHint,'motion');assert.equal(constraints.width.max,3840);assert.equal(constraints.height.max,2160);assert.equal(constraints.frameRate.max,60);
    assert.equal(parameters.degradationPreference,'maintain-resolution');assert.equal(parameters.encodings[0].maxFramerate,60);assert.equal(parameters.encodings[0].maxBitrate,40_000_000);assert.equal(qualities.uhd.fps,30);
  }finally{if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});

test('capable browsers prefer an efficient H264 profile while preserving fallbacks and caching the probe',async()=>{
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator'),oldSender=globalThis.RTCRtpSender;
  const codecs=[{mimeType:'video/VP8'},{mimeType:'video/H264',sdpFmtpLine:'packetization-mode=1;profile-level-id=42e01f'},{mimeType:'video/rtx'},{mimeType:'video/H264',sdpFmtpLine:'level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640034'}];let probes=0,preferred;
  globalThis.RTCRtpSender={getCapabilities:()=>({codecs})};Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaCapabilities:{encodingInfo:async q=>{probes++;assert.equal(q.video.framerate,60);assert.equal(q.video.width,3840);return {supported:true,smooth:true,powerEfficient:q.video.contentType.includes('640034')};},decodingInfo:async()=>({supported:true,smooth:true,powerEfficient:true})}}});
  try{const c=new Call('/api');await c.quality('uhd60');const video={setCodecPreferences:p=>preferred=p};await c.configureScreenCodecs(video);await c.configureScreenCodecs(video);assert.equal(probes,2);assert.deepEqual(preferred,[codecs[3],codecs[1],codecs[0],codecs[2]]);assert.equal(codecs[0].mimeType,'video/VP8');}
  finally{globalThis.RTCRtpSender=oldSender;if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});

test('1080p hardware is not excluded by an unrelated 4K60 capability gate',async()=>{
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator'),oldSender=globalThis.RTCRtpSender;
  const codecs=[{mimeType:'video/VP8'},{mimeType:'video/H264',sdpFmtpLine:'packetization-mode=1;profile-level-id=42001f'}];const probes=[];let preferred;
  const probe=async ({video})=>{probes.push(video);return {supported:video.width<=1920,smooth:video.framerate<=30,powerEfficient:true};};
  globalThis.RTCRtpSender={getCapabilities:()=>({codecs})};Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaCapabilities:{encodingInfo:probe,decodingInfo:probe}}});
  try{const c=new Call('/api'),video={setCodecPreferences:p=>preferred=p};await c.quality('auto');await c.configureScreenCodecs(video);assert.deepEqual(preferred,[codecs[1],codecs[0]]);assert.equal(probes[0].width,1920);assert.equal(probes[0].height,1080);assert.equal(probes[0].framerate,30);assert.equal(probes[0].bitrate,8_000_000);
    await c.quality({...c.state.quality,width:1280,height:720,fps:15,bitrate:2_000_000});await c.configureScreenCodecs(video);assert.equal(probes.at(-1).width,1280);assert.equal(probes.at(-1).framerate,15);assert.equal(probes.at(-1).bitrate,2_000_000);
  }finally{globalThis.RTCRtpSender=oldSender;if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});

test('all supported FPS values preserve independent size, bitrate and adaptation policy',async()=>{
  const c=new Call('/api');let constraints={},parameters,captures=0;
  const track={getSettings:()=>({width:3840,height:2160}),getConstraints:()=>constraints,applyConstraints:async p=>{constraints=p;captures++;}};
  c.display={getVideoTracks:()=>[track]};c.video={sender:{track,getParameters:()=>({encodings:[{}]}),setParameters:async p=>{parameters=p;}}};
  for(const [width,height] of [[320,180],[640,360],[1280,720],[1920,1080],[2560,1440],[3840,2160]])
    for(const priority of ['balanced','maintain-resolution','maintain-framerate'])
      for(let fps=1;fps<=60;fps++){
        const q={width,height,fps,bitrate:500_000+fps*100_000,priority};await c.quality(q);
        assert.deepEqual(c.state.quality,{...q,resolutionLocked:false,fpsLocked:false});assert.equal(constraints.width.max,width);assert.equal(constraints.height.max,height);assert.equal(constraints.frameRate.max,fps);
        assert.equal(parameters.encodings[0].maxFramerate,fps);assert.equal(parameters.encodings[0].maxBitrate,q.bitrate);assert.equal(parameters.degradationPreference,priority);
        assert.equal(track.contentHint,priority==='maintain-resolution'&&fps<=30?'detail':'motion');
        assert.equal(parameters.encodings[0].scaleResolutionDownBy,3840/width);
        const before=captures;await c.quality({...q,bitrate:80_000_000});assert.equal(captures,before);assert.equal(parameters.encodings[0].maxBitrate,80_000_000);
      }
  assert.equal(c.video.sender.track,track);
});

test('unsupported capability probes and hangup during probing preserve call fallback',async()=>{
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator'),oldSender=globalThis.RTCRtpSender;let set=0,resolve;
  globalThis.RTCRtpSender={getCapabilities:()=>({codecs:[{mimeType:'video/H264',sdpFmtpLine:'packetization-mode=1;profile-level-id=42e01f'}]})};const capabilities={encodingInfo:async()=>({supported:true,smooth:false}),decodingInfo:async()=>({supported:true,smooth:true})};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaCapabilities:capabilities}});
  try{const video={setCodecPreferences:()=>set++};await new Call('/api').configureScreenCodecs(video);assert.equal(set,0);
    capabilities.encodingInfo=async()=>{throw Error('unsupported')};await new Call('/api').configureScreenCodecs(video);assert.equal(set,0);
    capabilities.encodingInfo=()=>new Promise(r=>resolve=r);const c=new Call('/api');const pending=c.configureScreenCodecs(video);c.end();resolve({supported:true,smooth:true});await pending;assert.equal(set,0);
  }finally{globalThis.RTCRtpSender=oldSender;if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});


test('microphone denial and device loss keep the room and mixed audio track alive', async () => {
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const c=new Call('/api');const mix={};c.mix=mix;
  c.pc={};c.credentials={roomId:'12345678'};c.state.connected=true;
  const graph=()=>({gain:{value:1},connect(){return this;},disconnect(){}});
  c.audio={resume:async()=>{},createMediaStreamSource:graph,createGain:graph};
  let denied=true,stopped=0;
  const track={readyState:'live',stop(){stopped++;this.readyState='ended';}};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:async()=>{if(denied)throw Object.assign(new Error('denied'),{name:'NotAllowedError'});return {getAudioTracks:()=>[track],getTracks:()=>[track]};}}}});
  try {
    await c.enableMicrophone();assert.equal(c.closed,false);assert.equal(c.state.connected,true);assert.equal(c.state.muted,true);assert.match(c.state.micIssue,/仍可观看/);
    denied=false;await c.mute();assert.equal(c.state.micAvailable,true);assert.equal(c.state.muted,false);assert.equal(c.mix,mix);
    track.readyState='ended';track.onended();assert.equal(c.closed,false);assert.equal(c.state.muted,true);assert.equal(c.credentials.roomId,'12345678');assert.match(c.state.micIssue,/麦克风已断开/);
    track.readyState='live';await c.mute();assert.equal(c.state.micAvailable,true);assert.equal(c.mix,mix);assert.equal(c.state.connected,true);assert.equal(stopped,1);
  } finally {if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});

test('system audio and microphone mute are independent', () => {
  const c=new Call('/api');c.state.micAvailable=c.state.systemAudio=true;c.state.muted=false;
  c.micGain={gain:{value:1}};c.displayGain={gain:{value:1}};
  c.mute();assert.equal(c.micGain.gain.value,0);assert.equal(c.displayGain.gain.value,1);
  c.muteSystemAudio();assert.equal(c.displayGain.gain.value,0);assert.equal(c.state.muted,true);
  c.mute();assert.equal(c.micGain.gain.value,1);assert.equal(c.displayGain.gain.value,0);
});

test('pending browser audio activation does not block receive-only room entry', {timeout:1000}, async () => {
  const oldAudio=globalThis.AudioContext,oldPC=globalThis.RTCPeerConnection,oldNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const track={stop(){}};
  globalThis.AudioContext=class {resume(){return new Promise(()=>{});} async close(){} createMediaStreamDestination(){return {stream:{getAudioTracks:()=>[track],getTracks:()=>[track]}};}};
  globalThis.RTCPeerConnection=class {addTrack(){} close(){}};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:async()=>{throw new Error('no microphone');}}}});
  const c=new Call('/api');c.request=async()=>({roomId:'12345678',role:'guest',iceServers:[]});c.poll=()=>{};c.armDeadline=()=>{};
  try {await c.start('12345678');assert.equal(c.state.roomId,'12345678');assert.equal(c.closed,false);assert.equal(c.state.muted,true);}
  finally {c.end();globalThis.AudioContext=oldAudio;globalThis.RTCPeerConnection=oldPC;if(oldNavigator)Object.defineProperty(globalThis,'navigator',oldNavigator);else delete globalThis.navigator;}
});


test('manual locks override every automatic policy and survive bitrate changes',async()=>{
  const {qualities}=await import('../call.js');
  const c=new Call('/api');let constraints={},params={encodings:[{}]};
  const track={getSettings:()=>({width:3840,height:2160}),getConstraints:()=>constraints,applyConstraints:async p=>{constraints=p;}};
  c.display={getVideoTracks:()=>[track]};c.video={sender:{getParameters:()=>structuredClone(params),setParameters:async p=>{params=p;}}};
  for(const priority of ['balanced','maintain-resolution','maintain-framerate']){
    for(const [resolutionLocked,fpsLocked,expected] of [[true,false,'maintain-resolution'],[false,true,'maintain-framerate'],[true,true,'maintain-framerate-and-resolution']]){
      await c.quality({...qualities.uhd,fps:45,priority,resolutionLocked,fpsLocked});
      assert.equal(params.degradationPreference,expected);assert.equal(params.encodings[0].maxFramerate,45);
      await c.quality({...c.state.quality,bitrate:3_000_000});
      assert.equal(params.degradationPreference,expected);assert.equal(c.state.quality.resolutionLocked,resolutionLocked);assert.equal(c.state.quality.fpsLocked,fpsLocked);
    }
  }
  await c.quality('smooth');assert.equal(c.state.quality.resolutionLocked,false);assert.equal(c.state.quality.fpsLocked,false);assert.equal(params.degradationPreference,'maintain-framerate');
});

test('unsupported or ignored browser lock rejects and restores previous quality without fallback',async()=>{
  const c=new Call('/api');let params={encodings:[{}]},constraints={},attempts=[],ignore=false;
  const track={getSettings:()=>({width:1920,height:1080}),getConstraints:()=>constraints,applyConstraints:async p=>{constraints=p;}};
  c.display={getVideoTracks:()=>[track]};c.video={sender:{getParameters:()=>structuredClone(params),setParameters:async p=>{
    attempts.push(p.degradationPreference);
    if(p.degradationPreference==='maintain-framerate-and-resolution'){
      if(ignore){params={...p,degradationPreference:'balanced'};return;}
      throw new TypeError('unsupported enum');
    }
    params=p;
  }}};
  await c.quality('clear');const previous={...c.state.quality};
  for(ignore of [false,true]){
    attempts=[];
    await assert.rejects(c.quality({...previous,resolutionLocked:true,fpsLocked:true}),/未自动改用其他策略/);
    assert.deepEqual(c.state.quality,previous);
    assert.deepEqual(attempts,['maintain-framerate-and-resolution','maintain-resolution']);
    assert.equal(params.degradationPreference,'maintain-resolution');assert.equal(c.state.qualityBusy,false);
  }
});
