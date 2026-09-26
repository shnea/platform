import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
const dom=new JSDOM('<!doctype html><body></body>',{url:'https://editor.example',pretendToBeVisual:true});
for(const key of ['window','document','navigator','Node','HTMLElement','Element','MutationObserver','DOMParser','getComputedStyle'])Object.defineProperty(globalThis,key,{configurable:true,value:key==='getComputedStyle'?dom.window[key].bind(dom.window):dom.window[key]});
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window);globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window);
const {parseDocument,createEditorCore,renderViewer}=await import('@shnea/editor');
after(()=>dom.window.close());
const tick=()=>new Promise(r=>setTimeout(r,0));
const id=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const file=(n,kind='image',layout={})=>({type:'attachment',attrs:{id:id(n),fileId:`file-${n}`,scope:'dev',kind,name:`file-${n}`,size:1,...layout}});
const row=content=>({type:'mediaRow',attrs:{id:id(10)},content});
const value=content=>({format:'shnea-editor',version:3,content:{type:'doc',content}});
const target=()=>document.body.appendChild(document.createElement('div'));

test('호스트가 제공한 첨부는 검증 후 삽입하며 업로드 없이 실행 취소할 수 있다',()=>{
 const node=target(),editor=createEditorCore({element:node});const ref={fileId:'sample-image',scope:'public-demo',kind:'image',name:'샘플',size:525};
 editor.insertAttachment(ref);const saved=editor.getValue();assert.equal(saved.content.content[0].type,'mediaRow');assert.equal(saved.content.content[0].content[0].attrs.fileId,'sample-image');assert.equal(editor.undo(),true);
 assert.throws(()=>editor.insertAttachment({...ref,kind:'script'}));assert.throws(()=>editor.insertAttachment({...ref,fileId:''}));editor.destroy();node.remove();
});

test('이전 이미지 줄을 이전하며 혼합 미디어·너비·정렬과 문서 왕복을 검증한다',()=>{
 const old={...value([{...row([file(1)]),type:'imageRow'}]),version:2};
 const migrated=parseDocument(old);assert.equal(migrated.version,3);assert.equal(migrated.content.content[0].type,'mediaRow');
 const mixed=value([row([file(1,'image',{widthPercent:50,align:'left'}),file(2,'video')])]);assert.deepEqual(parseDocument(parseDocument(mixed)),parseDocument(mixed));
 for(const bad of [value([row([file(1,'audio')])]),value([row([])]),value([row([file(1),file(2),file(3),file(4)])]),...[0,24,101,NaN,'50',null].map(widthPercent=>value([file(1,'image',{widthPercent})])),value([file(1,'video',{align:'justify'})]),value([file(1,'file',{widthPercent:50})])])assert.throws(()=>parseDocument(bad));
});

test('너비·정렬 변경은 재생 인스턴스를 보존하며 실행 취소와 읽기 배치에 반영한다',async()=>{
 const node=target();let mounts=0,destroys=0;
 const adapter={scope:()=> 'dev',upload:async()=>{throw Error('unused');},resolve:async ref=>({fileId:ref.fileId,kind:'VIDEO',state:'READY',originalUrl:'/original',downloadUrl:'/download',viewerUrl:'/view',previewUrl:null,thumbnailUrl:null,expiresAt:null,streamUrl:'/stream'}),video:el=>{mounts++;el.append(document.createElement('video'));return {update(){},destroy(){destroys++;}};}};
 const editor=createEditorCore({element:node,value:value([row([file(1,'video')])]),attachments:adapter});await tick();node.querySelector('.sa-video-play').click();assert.equal(mounts,1);
 const frame=node.querySelector('.se-media-frame'),handle=frame.querySelector('[role=slider]');handle.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Home',bubbles:true,cancelable:true}));frame.querySelector('[data-align=right]').click();
 let attrs=editor.getValue().content.content[0].content[0].attrs;assert.equal(attrs.widthPercent,25);assert.equal(attrs.align,'right');assert.equal(mounts,1);assert.equal(destroys,0);
 assert.equal(editor.undo(),true);assert.equal(editor.getValue().content.content[0].content[0].attrs.align,'center');assert.equal(editor.undo(),true);assert.equal(editor.getValue().content.content[0].content[0].attrs.widthPercent,100);
 editor.redo();editor.redo();const read=target(),dispose=renderViewer(read,editor.getValue());assert.equal(read.querySelector('.se-media-frame').style.cssText,frame.style.cssText);assert.equal(read.querySelector('[role=slider]'),null);dispose();read.remove();editor.destroy();assert.equal(destroys,1);node.remove();
});

test('드래그는 한 번에 기록하고 취소하면 복원하며 묶음에서는 크기를 변경하지 않는다',async()=>{
 const node=target(),editor=createEditorCore({element:node,value:value([row([file(1)])])});
 const frame=node.querySelector('.se-media-frame'),handle=frame.querySelector('[role=slider]');frame.parentElement.getBoundingClientRect=()=>({width:1000});frame.getBoundingClientRect=()=>({width:1000});handle.setPointerCapture=()=>{};handle.hasPointerCapture=()=>false;
 const pointer=(type,x)=>{const e=new window.Event(type,{bubbles:true,cancelable:true});Object.assign(e,{button:0,pointerId:1,clientX:x});handle.dispatchEvent(e);};
 pointer('pointerdown',1000);pointer('pointermove',750);pointer('pointerup',750);assert.equal(editor.getValue().content.content[0].content[0].attrs.widthPercent,50);assert.equal(editor.undo(),true);assert.equal(editor.getValue().content.content[0].content[0].attrs.widthPercent,100);assert.equal(editor.undo(),false);
 pointer('pointerdown',1000);pointer('pointermove',600);pointer('pointercancel',600);assert.equal(frame.style.getPropertyValue('--se-media-width'),'100%');
 editor.setValue(value([row([file(1),file(2,'video')])]));node.querySelector('[role=slider]').dispatchEvent(new window.KeyboardEvent('keydown',{key:'Home',bubbles:true,cancelable:true}));assert.equal(editor.getValue().content.content[0].content[0].attrs.widthPercent,100);editor.destroy();node.remove();
});
