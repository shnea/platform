import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
const dom=new JSDOM('<!doctype html><body></body>',{url:'https://editor.example',pretendToBeVisual:true});
for(const key of ['window','document','navigator','Node','Element','HTMLElement','SVGElement','HTMLSelectElement','MutationObserver','DOMParser','getComputedStyle'])Object.defineProperty(globalThis,key,{configurable:true,value:key==='getComputedStyle'?dom.window[key].bind(dom.window):dom.window[key]});
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window);globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window);globalThis.IS_REACT_ACT_ENVIRONMENT=true;
dom.window.Range.prototype.getClientRects=()=>[];dom.window.Range.prototype.getBoundingClientRect=()=>({left:0,right:0,top:0,bottom:0,width:0,height:0});
const {createElement:h,StrictMode,act}=await import('react');
const {createRoot}=await import('react-dom/client');
const {createApp,h:vh,shallowRef,nextTick}=await import('vue');
const react=await import('@shnea/editor/react'),vue=await import('@shnea/editor/vue');
const {fromMarkdown,parseDocument}=await import('@shnea/editor');
const {editorBinding}=await import('../dist/bindings/lifecycle.js');
const target=()=>{const node=document.createElement('div');document.body.append(node);return node;};
after(()=>dom.window.close());

async function host(kind,initial,viewer=false){
 const element=target();let props=initial;
 if(kind==='React'){
  const root=createRoot(element),render=()=>root.render(h(StrictMode,null,h(viewer?react.ShneaViewer:react.ShneaEditor,props)));
  await act(async()=>render());
  return {element,run:fn=>act(async()=>{await fn();}),async set(patch){props={...props,...patch};await act(async()=>render());},async close(){await act(async()=>root.unmount());element.remove();}};
 }
 const current=shallowRef(props),app=createApp({setup(){return ()=>vh(viewer?vue.ShneaViewer:vue.ShneaEditor,viewer?current.value:{...current.value,modelValue:current.value.value});}});app.mount(element);await nextTick();
 return {element,async run(fn){await fn();await nextTick();},async set(patch){current.value={...current.value,...patch};await nextTick();},async close(){app.unmount();await nextTick();element.remove();}};
}

for(const kind of ['React','Vue']){
 test(`${kind}: controlled echo·최신 콜백·서식/표 왕복·외부 교체·해제를 처리한다`,async()=>{
  let api,change;const errors=[];const value=fromMarkdown('# 한글 🙂\n\n**굵게**와 *기울임*\n\n- 목록\n  - 중첩\n\n| 하나 | 둘 |\n| --- | --- |\n| 셀 | 내용 |');
  const editor=await host(kind,{value,onReady:next=>{api=next;},onChange:event=>{change=event;},onError:e=>errors.push(e)});
  const instance=api,body=editor.element.querySelector('.tiptap');assert.equal(editor.element.querySelectorAll('.tiptap').length,1);
  await editor.run(()=>api.insertText('추가'));assert.equal(change.origin,'edit');
  await editor.set({value:structuredClone(change.document)});assert.equal(api,instance);assert.equal(editor.element.querySelector('.tiptap'),body);
  await editor.run(()=>api.undo());assert.deepEqual(api.getValue(),value);
  let fresh=0;await editor.set({onChange:event=>{fresh++;change=event;},appearance:{fontSize:20}});await editor.run(()=>api.insertText('새 콜백'));assert.equal(fresh,1);assert.equal(api,instance);
  const stored=JSON.parse(JSON.stringify(api.getValue())),read=await host(kind,{value:stored,onError:e=>errors.push(e),appearance:{fontSize:20}},true);
  assert.ok(read.element.querySelector('strong'));assert.ok(read.element.querySelector('em'));assert.equal(read.element.querySelectorAll('table').length,1);assert.equal(read.element.querySelectorAll('ul ul').length,1);
  assert.equal(read.element.querySelector('.shnea-viewer').style.getPropertyValue('--se-font-size'),'20px');
  await editor.set({value:{format:'wrong'}});assert.equal(JSON.stringify(api.getValue()),JSON.stringify(stored));assert.equal(errors.length,1);
  await editor.set({value:fromMarkdown('다른 문서'),documentKey:'other'});assert.match(editor.element.textContent,/다른 문서/);await editor.run(()=>assert.equal(api.undo(),false));
  await read.set({value:fromMarkdown('읽기 교체')});assert.match(read.element.textContent,/읽기 교체/);
  await editor.close();assert.equal(api,null);assert.throws(()=>instance.getValue(),/해제/);await read.close();
 });

 test(`${kind}: 업로드 중 echo는 전송을 유지하고 해제는 중단한다`,async()=>{
  let api,change,context;const attachments={scope:()=> 'test',upload:(_file,ctx)=>{context=ctx;return new Promise(()=>{});},resolve:async()=>{throw Error('호출되면 안 됨');}};
  const editor=await host(kind,{value:null,attachments,onReady:next=>{api=next;},onChange:event=>{change=event;}});
  await editor.run(()=>{const event=new window.Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{files:[new File(['x'],'test.txt')],getData:()=>''}});editor.element.querySelector('.tiptap').dispatchEvent(event);});
  assert.ok(context);await editor.set({value:structuredClone(change.document)});assert.equal(context.signal.aborted,false);assert.equal(editor.element.querySelectorAll('.sa-pending').length,1);
  await editor.close();assert.equal(context.signal.aborted,true);assert.equal(api,null);
 });
}

test('처음 잘못된 문서 후 재시도·같은 내용의 새 문서 키·모양 오류는 안전하게 처리한다',()=>{
 const element=target();let api;const binding=editorBinding(element,{ready:next=>{api=next;},change:()=>{},error:e=>{throw e;}});
 assert.throws(()=>binding.update({value:{format:'invalid'}}));assert.equal(element.children.length,0);
 const value=fromMarkdown('처음');binding.update({value});api.insertText('변경');const saved=api.getValue();
 assert.throws(()=>binding.update({value:fromMarkdown('파괴되면 안 됨'),appearance:{fontSize:999}}));assert.deepEqual(api.getValue(),saved);
 binding.update({value:saved,documentKey:'new'});assert.equal(api.undo(),false);assert.deepEqual(api.getValue(),parseDocument(saved));binding.destroy();binding.destroy();assert.equal(element.children.length,0);element.remove();
});
