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
    const start=call.start();await Promise.resolve();
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
  c.display={getVideoTracks:()=>[{getSettings:()=>({width:3840,height:2160}),applyConstraints:async p=>{constraints=p;}}]};
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
