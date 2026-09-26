import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {Call,friendly,qualities,qualityControlLabel} from '../call.js';

test('hidden previews pause locally, resume without replacing tracks and preserve picture-in-picture',async()=>{
  const html=await readFile(new URL('../index.html',import.meta.url),'utf8');
  const events=new Map(),nodes=new Map([...html.matchAll(/id="([^"]+)"/g)].map(([,id])=>[id,{value:'',options:[],addEventListener(type,fn){events.set(id+':'+type,fn);},paused:true,play(){this.paused=false;return Promise.resolve();},pause(){this.paused=true;}}]));
  const document={visibilityState:'visible',getElementById:id=>nodes.get(id),addEventListener(type,fn){events.set(type,fn);}};
  const session=new Call('/api');session.state.cameraOn=session.state.remoteCameraOn=true;
  const track={enabled:true,readyState:'live'},stream={getVideoTracks:()=>[track]};
  for(const id of ['local-camera','remote-camera','screen'])nodes.get(id).srcObject=stream;
  const context=vm.createContext({Call,friendly,qualities,qualityControlLabel,session,URLSearchParams,location:{hash:''},document,navigator:{},window:{addEventListener(){}},setTimeout,clearTimeout});
  const source=(await readFile(new URL('../app.js',import.meta.url),'utf8')).replace(/^import .*\n/,'');vm.runInContext(source+'\ncall=session;',context);
  const sync=()=>vm.runInContext('syncVideoPlayback()',context);
  sync();assert.equal(nodes.get('local-camera').paused,false);
  vm.runInContext('cameraHidden=true',context);sync();assert.equal(nodes.get('local-camera').paused,true);assert.equal(nodes.get('remote-camera').paused,true);assert.equal(nodes.get('screen').paused,false);
  vm.runInContext('cameraHidden=false',context);sync();assert.equal(nodes.get('local-camera').paused,false);
  document.visibilityState='hidden';events.get('visibilitychange')();assert.equal(nodes.get('screen').paused,true);assert.equal(nodes.get('local-camera').paused,true);
  document.pictureInPictureElement=nodes.get('screen');events.get('screen:enterpictureinpicture')();assert.equal(nodes.get('screen').paused,false);assert.equal(nodes.get('remote-camera').paused,true);
  document.pictureInPictureElement=null;events.get('screen:leavepictureinpicture')();assert.equal(nodes.get('screen').paused,true);
  document.visibilityState='visible';events.get('visibilitychange')();assert.equal(nodes.get('screen').paused,false);
  assert.equal(nodes.get('local-camera').srcObject,stream);assert.equal(track.enabled,true);assert.equal(track.readyState,'live');
  session.state.cameraOn=false;sync();assert.equal(nodes.get('local-camera').paused,true);
});
