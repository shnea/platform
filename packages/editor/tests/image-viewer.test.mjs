import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {mountAttachmentView} from '../dist/media/attachment-view.js';

const dom=new JSDOM('<!doctype html><body></body>',{url:'https://editor.example'});
const {document,HTMLDialogElement}=dom.window;
dom.window.ResizeObserver=class{observe(){} disconnect(){}};
HTMLDialogElement.prototype.showModal=function(){this.open=true;};
HTMLDialogElement.prototype.close=function(){if(this.open){this.open=false;this.dispatchEvent(new dom.window.Event('close'));}};
after(()=>dom.window.close());
const ref={fileId:'image-1',scope:'dev',kind:'image',name:'사진.png',size:10000000};
const views={fileId:ref.fileId,kind:'IMAGE',state:'READY',originalUrl:'/original',downloadUrl:'/download',viewerUrl:'/view',previewUrl:'/preview',thumbnailUrl:'/thumbnail',expiresAt:null};
async function mount(data){
 const root=document.body.appendChild(document.createElement('div'));
 const dispose=mountAttachmentView(root,ref,{scope:()=>ref.scope,upload:async()=>ref,resolve:async()=>data});
 await new Promise(resolve=>setTimeout(resolve,0));
 return {root,dispose:()=>{dispose();root.remove();}};
}
test('본문은 썸네일만, 클릭은 미리보기만 요청하고 원본은 링크로 분리한다',async()=>{
 const {root,dispose}=await mount(views);
 assert.deepEqual([...root.querySelectorAll('img[src]')].map(el=>el.getAttribute('src')),['https://editor.example/thumbnail']);
 const trigger=root.querySelector('.siv-thumbnail'),full=root.querySelector('.siv-stage img');
 trigger.click();assert.equal(full.src,'https://editor.example/preview');
 assert.equal(root.querySelector('dialog a[aria-label="원본 보기"]').href,'https://editor.example/original');
 assert.ok(![...root.querySelectorAll('img[src]')].some(el=>el.src.endsWith('/original')));
 root.querySelector('dialog').close();assert.equal(full.hasAttribute('src'),false);
 trigger.click();assert.equal(full.src,'https://editor.example/preview');
 dispose();assert.equal(document.querySelector('dialog'),null);
});
test('썸네일 누락이나 미리보기 준비 중에 원본을 대신 불러오지 않는다',async()=>{
 const missing=await mount({...views,thumbnailUrl:null});
 assert.equal(missing.root.querySelector('img'),null);assert.match(missing.root.textContent,/썸네일을 사용할 수 없습니다/);missing.dispose();
 const queued=await mount({...views,state:'QUEUED',previewUrl:null});
 assert.equal(queued.root.querySelector('.siv-thumbnail').disabled,true);
 queued.root.querySelector('.siv-thumbnail').click();assert.equal(queued.root.querySelector('.siv-stage img').hasAttribute('src'),false);
 queued.dispose();
});
