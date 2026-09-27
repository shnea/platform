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
async function mount(data,options={}){
 const root=document.body.appendChild(document.createElement('div'));
 const dispose=mountAttachmentView(root,ref,{scope:()=>ref.scope,upload:async()=>ref,resolve:async()=>data,...options});
 await new Promise(resolve=>setTimeout(resolve,0));
 return {root,dispose:()=>{dispose();root.remove();}};
}
test('본문은 PC 미리보기·모바일 썸네일을 선택하고 클릭은 미리보기·원본은 링크로 분리한다',async()=>{
 const {root,dispose}=await mount(views);
 assert.deepEqual([...root.querySelectorAll('img[src]')].map(el=>el.getAttribute('src')),['https://editor.example/thumbnail']);
 const desktop=root.querySelector('picture source');
 assert.equal(desktop.media,'(min-width: 601px)');assert.equal(desktop.srcset,'https://editor.example/preview');
 assert.equal(desktop.nextElementSibling.tagName,'IMG');
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
 assert.equal(queued.root.querySelector('picture source'),null);
 assert.equal(queued.root.querySelector('.siv-thumbnail').disabled,true);
 queued.root.querySelector('.siv-thumbnail').click();assert.equal(queued.root.querySelector('.siv-stage img').hasAttribute('src'),false);
 queued.dispose();
});
test('플랫폼 연결 옵션은 호스트 원문 응답을 해석하고 잘못된 매핑을 화면에 알린다',async()=>{
 const path='/api/v1/files/image-1',query='?token=issued';
 const data={...views,thumbnailUrl:path+'/content/thumbnail'+query,previewUrl:path+'/content/preview'+query,originalUrl:path+'/content/original'+query,viewerUrl:path+'/view'+query,downloadUrl:path+'/content/download'+query};
 const options={platformImageOrigin:'https://platform.example'};
 const ready=await mount(data,options);
 assert.equal(ready.root.querySelector('picture img').src,'https://platform.example'+data.thumbnailUrl);
 assert.equal(ready.root.querySelector('picture source').srcset,'https://platform.example'+data.previewUrl);
 ready.root.querySelector('.siv-thumbnail').click();assert.equal(ready.root.querySelector('dialog img').src,'https://platform.example'+data.previewUrl);ready.dispose();
 const broken=await mount({...data,thumbnailUrl:'/api/files/image-1/content'},options);
 assert.equal(broken.root.querySelector('img'),null);assert.match(broken.root.textContent,/호스트 서버는 플랫폼 보기 응답의 URL을 변경하지 않고 전달/);broken.dispose();
});
