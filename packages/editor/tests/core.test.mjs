import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';

const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'https://editor.example',pretendToBeVisual:true});
for(const key of ['window','document','navigator','Node','HTMLElement','HTMLSelectElement','Element','MutationObserver','DOMParser','getComputedStyle'])Object.defineProperty(globalThis,key,{configurable:true,value:typeof dom.window[key]==='function'&&key==='getComputedStyle'?dom.window[key].bind(dom.window):dom.window[key]});
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window);globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window);
const {emptyDocument,parseDocument,fromMarkdown,createEditorCore,renderViewer,EditorError}=await import('@shnea/editor');
const {mountEditor}=await import('@shnea/editor/ui');
after(()=>dom.window.close());
const target=()=>{const node=document.createElement('div');document.body.append(node);return node;};
const rejects=(fn,code='DOCUMENT_INVALID')=>assert.throws(fn,error=>error instanceof EditorError&&error.code===code);
const docOf=content=>({format:'shnea-editor',version:1,content:{type:'doc',content}});

test('문서 입출력은 독립 복사이며 빈 값만 명시적으로 초기화한다',()=>{
  assert.deepEqual(parseDocument(null),emptyDocument());
  const source=fromMarkdown('# 한글 제목\n\n본문 **굵게**'),copy=parseDocument(source);copy.content.content[0].content[0].text='수정';
  assert.equal(source.content.content[0].content[0].text,'한글 제목');
  rejects(()=>parseDocument({...source,version:2}),'DOCUMENT_VERSION_UNSUPPORTED');
  rejects(()=>parseDocument(''));rejects(()=>parseDocument({}));
});
test('알 수 없는 노드·속성·부적절한 구조와 과도한 깊이는 거부한다',()=>{
  rejects(()=>parseDocument(docOf([{type:'script'}])));
  rejects(()=>parseDocument(docOf([{type:'__proto__'}])));
  rejects(()=>parseDocument(docOf([{type:'paragraph',attrs:{onclick:'alert(1)'}}])));
  rejects(()=>parseDocument(docOf([{type:'text',text:'본문'}])));
  rejects(()=>parseDocument(docOf([{type:'heading',attrs:{level:99}}])));
  rejects(()=>parseDocument(docOf([{type:'table',content:[{type:'tableRow',content:[{type:'tableCell',attrs:{colspan:Infinity},content:[{type:'paragraph'}]}]}]}])));
  let inner={type:'paragraph'};for(let i=0;i<66;i++)inner={type:'blockquote',content:[inner]};
  rejects(()=>parseDocument(docOf([inner])),'DOCUMENT_LIMIT_EXCEEDED');
  const circular={type:'blockquote',content:[]};circular.content.push(circular);rejects(()=>parseDocument(docOf([circular])));
});
test('Markdown 구조·서식·한글·표 정렬·혼합 체크리스트·코드 언어를 보존한다',()=>{
  const source='# 제목 😀\n\n**굵게** *기울임* ~~삭제~~ `코드` [링크](https://example.com)\n\n> 인용\n\n3. 셋\n   - 중첩\n\n- [x] 완료\n- 일반\n- [ ] 대기\n\n| 이름 | 값 |\n| :--- | ---: |\n| 한글 | 12 |\n\n```js\n  const 값 = 1;\n```\n\n---\n\n![그림](./photo.png)';
  const value=fromMarkdown(source),json=JSON.stringify(value);
  for(const name of ['heading','bold','italic','strike','code','link','blockquote','orderedList','bulletList','taskList','taskItem','table','codeBlock','externalImage','horizontalRule'])assert.ok(json.includes(`"${name}"`),name);
  assert.ok(json.includes('"start":3'));assert.ok(json.includes('"checked":true'));assert.ok(json.includes('"language":"js"'));assert.ok(json.includes('"align":"right"'));
  assert.ok(json.includes('  const 값 = 1;'));assert.ok(json.includes('./photo.png'));assert.deepEqual(parseDocument(value),value);
  const node=target();const dispose=renderViewer(node,value);assert.equal(node.querySelectorAll('table tr').length,2);assert.equal(node.querySelectorAll('img').length,0);assert.match(node.textContent,/이미지 경로 확인 필요/);dispose();node.remove();
});
test('HTML은 실행하지 않고 악성 링크·Base64를 로드하지 않는다',()=>{
  const value=fromMarkdown('<script>alert(1)</script>\n\n[위험](javascript:alert(1))\n\n![위험](data:image/svg+xml;base64,AAAA)\n\n수식 $a$와 {{확장}}');
  const node=target(),dispose=renderViewer(node,value);assert.equal(node.querySelectorAll('script,iframe,img').length,0);assert.match(node.textContent,/<script>/);assert.match(node.textContent,/\$a\$/);dispose();node.remove();
  for(const href of ['javascript:alert(1)','data:text/html,x','//attacker.example','https://good.example\njavascript:x'])rejects(()=>parseDocument(docOf([{type:'paragraph',content:[{type:'text',text:'링크',marks:[{type:'link',attrs:{href}}]}]}])));
  rejects(()=>parseDocument(docOf([{type:'paragraph',content:[{type:'externalImage',attrs:{source:'data:image/png;base64,AAAA'}}]}])));
  rejects(()=>parseDocument(docOf([{type:'paragraph',content:[{type:'text',text:'링크',marks:[{type:'link',attrs:{href:'https://example.com',rel:null}}]}]}])));
});
test('편집 변경과 값 대체 이벤트를 구분하고 잘못된 값은 현재 문서를 보존한다',()=>{
  const node=target(),changes=[],editor=createEditorCore({element:node,onChange:c=>changes.push(c)});
  editor.insertText('안녕');assert.equal(changes.at(-1).origin,'edit');const saved=editor.getValue(),count=changes.length;
  rejects(()=>editor.setValue({...saved,version:99}),'DOCUMENT_VERSION_UNSUPPORTED');assert.deepEqual(editor.getValue(),saved);
  editor.setValue(fromMarkdown('새 문서'));assert.equal(changes.length,count);assert.equal(editor.undo(),false);
  editor.setValue(null,{emitChange:true});assert.equal(changes.at(-1).origin,'replace');assert.deepEqual(editor.getValue(),parseDocument(null));
  editor.destroy();editor.destroy();rejects(()=>editor.getValue(),'EDITOR_DESTROYED');node.remove();
});
test('두 인스턴스는 편집·실행 취소·자원 해제가 분리되고 중복 마운트는 막는다',()=>{
  const left=target(),right=target(),a=createEditorCore({element:left}),b=createEditorCore({element:right});
  rejects(()=>createEditorCore({element:left}),'EDITOR_MOUNTED');rejects(()=>renderViewer(left,null),'EDITOR_MOUNTED');
  a.insertMarkdown('# 왼쪽\n\n**본문**');assert.deepEqual(b.getValue(),parseDocument(null));assert.equal(a.undo(),true);assert.equal(a.redo(),true);
  a.destroy();b.insertText('오른쪽');assert.match(JSON.stringify(b.getValue()),/오른쪽/);b.destroy();left.remove();right.remove();
});
test('호스트 JSON 저장·재입력 후 뷰어와 편집 DOM의 내용·서식이 일치한다',()=>{
  const left=target(),right=target(),editor=createEditorCore({element:left,value:fromMarkdown('## 문서\n\n- [x] 확인\n\n본문 [링크](https://example.com)')});
  const saved=JSON.parse(JSON.stringify(editor.getValue()));editor.setValue(saved);const dispose=renderViewer(right,saved);
  for(const selector of ['h2','p','a','li > div'])assert.deepEqual([...left.querySelectorAll(selector)].map(x=>x.textContent),[...right.querySelectorAll(selector)].map(x=>x.textContent));
  assert.equal(right.querySelector('input[type=checkbox]').disabled,true);assert.equal(right.querySelector('input[type=checkbox]').checked,true);
  assert.equal(right.querySelector('[contenteditable]'),null);assert.equal(right.querySelector('a').getAttribute('rel'),'noopener noreferrer nofollow');
  dispose();editor.destroy();left.remove();right.remove();
});
test('읽기 전용 코어는 사용자 편집 API를 거부하고 호스트 값 교체는 허용한다',()=>{
  const node=target(),editor=createEditorCore({element:node,editable:false});rejects(()=>editor.insertText('수정'),'EDITOR_READ_ONLY');rejects(()=>editor.insertMarkdown('# 수정'),'EDITOR_READ_ONLY');editor.setValue(fromMarkdown('호스트 입력'));assert.match(node.textContent,/호스트 입력/);editor.destroy();node.remove();
});
test('Markdown 붙여넣기는 한 번에 취소되고 코드 안에서는 원문을 삽입한다',()=>{
  const node=target(),editor=createEditorCore({element:node});
  const paste=value=>{const event=new window.Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{getData:type=>type==='text/plain'?value:'',files:[]}});node.querySelector('[contenteditable]').dispatchEvent(event);};
  paste('# 붙여넣기\n\n**본문**');assert.ok(node.querySelector('h1'));assert.equal(editor.undo(),true);assert.equal(node.textContent,'');
  editor.setValue(fromMarkdown('```text\n코드\n```'));paste('# 원문');assert.equal(node.querySelectorAll('h1').length,0);assert.match(node.querySelector('code').textContent,/# 원문/);
  editor.destroy();node.remove();
});

test('공통 UI의 슬래시 삽입·닫기·문서 교체와 두 인스턴스가 분리된다',()=>{
 const one=target(),two=target(),a=mountEditor({element:one}),b=mountEditor({element:two});
 a.insertText('/h2');assert.equal(one.querySelector('.se-insert-menu').hidden,false);assert.equal(two.querySelector('.se-insert-menu').hidden,true);
 one.querySelector('[role=combobox]').dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));assert.ok(one.querySelector('h2'));assert.equal(one.querySelector('h2').textContent,'');
 a.setValue(null);a.insertText('/');one.querySelector('[role=combobox]').dispatchEvent(new window.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));assert.equal(one.querySelector('.se-insert-menu').hidden,true);
 a.setValue(fromMarkdown('문서'));assert.equal(a.undo(),false);assert.deepEqual(b.getValue(),parseDocument(null));
 a.destroy();a.destroy();b.destroy();assert.equal(one.childElementCount,0);one.remove();two.remove();
});
test('원문 붙여넣기 선택은 변환을 끄고 읽기 문서는 편집 명령을 거부한다',()=>{
 const node=target(),editor=createEditorCore({element:node});editor.setPasteMode('text');
 const event=new window.Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{getData:type=>type==='text/plain'?'# 원문':'',files:[]}});node.querySelector('[contenteditable]').dispatchEvent(event);
 assert.equal(node.querySelector('h1'),null);assert.equal(node.textContent,'# 원문');editor.destroy();node.remove();
 const read=target(),reader=createEditorCore({element:read,editable:false});rejects(()=>reader.run('bold'),'EDITOR_READ_ONLY');reader.destroy();read.remove();
});

test('슬래시 검색은 모든 종류를 찾고 beforeinput·조합 중 입력·원문 슬래시를 구분한다',()=>{
 const node=target(),editor=mountEditor({element:node}),body=node.querySelector('.tiptap'),search=node.querySelector('[role=combobox]');
 body.dispatchEvent(new window.InputEvent('beforeinput',{inputType:'insertText',data:'/',bubbles:true,cancelable:true,isComposing:true}));assert.equal(node.querySelector('[role=dialog]').hidden,true);
 body.dispatchEvent(new window.InputEvent('beforeinput',{inputType:'insertText',data:'/',bubbles:true,cancelable:true}));assert.equal(node.querySelector('[role=dialog]').hidden,false);
 assert.equal(node.querySelectorAll('[role=tab]').length,6);assert.equal(node.querySelector('[role=toolbar]'),null);
 search.value='table';search.dispatchEvent(new window.Event('input'));assert.match(node.querySelector('[aria-selected=true][role=option]').textContent,/표 삽입/);
 search.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));assert.equal(node.querySelectorAll('tr').length,3);assert.doesNotMatch(JSON.stringify(editor.getValue()),/\/table/);
 editor.setValue(null);body.dispatchEvent(new window.KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));search.dispatchEvent(new window.KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));assert.equal(body.textContent,'/');assert.equal(node.querySelector('[role=dialog]').hidden,true);
 editor.destroy();node.remove();
});

test('Markdown 붙여넣기는 선택 전 문서를 보존하며 원문·변환·취소를 적용한다',()=>{
 const node=target(),editor=mountEditor({element:node});
 const paste=source=>{const event=new window.Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{getData:type=>type==='text/plain'?source:'',files:[]}});node.querySelector('.tiptap').dispatchEvent(event);};
 paste('# 제목');assert.deepEqual(editor.getValue(),emptyDocument());assert.equal(node.querySelector('.se-paste-choice').hidden,false);
 [...node.querySelectorAll('button')].find(x=>x.textContent==='원문 그대로').click();assert.equal(node.querySelector('.tiptap').textContent,'# 제목');assert.equal(node.querySelector('h1'),null);
 editor.setValue(null);paste('# 제목');[...node.querySelectorAll('button')].find(x=>x.textContent==='Markdown 서식 적용').click();assert.equal(node.querySelector('h1').textContent,'제목');assert.equal(editor.undo(),true);assert.deepEqual(editor.getValue(),emptyDocument());
 paste('**취소**');[...node.querySelectorAll('button')].find(x=>x.textContent==='취소').click();assert.deepEqual(editor.getValue(),emptyDocument());
 paste('일반 텍스트');assert.equal(node.querySelector('[role=dialog]').hidden,true);assert.equal(node.querySelector('.tiptap').textContent,'일반 텍스트');editor.destroy();node.remove();
});

test('브라우저 선택 변경 직후 슬래시를 열어도 선택 글자가 서식 대상에서 사라지지 않는다',()=>{
 const node=target(),editor=mountEditor({element:node,value:fromMarkdown('선택할 글자')}),body=node.querySelector('.tiptap');body.focus();
 const range=document.createRange();range.selectNodeContents(body.querySelector('p'));window.getSelection().removeAllRanges();window.getSelection().addRange(range);
 body.dispatchEvent(new window.KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));const search=node.querySelector('[role=combobox]');search.value='bold';search.dispatchEvent(new window.Event('input'));search.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
 assert.equal(body.querySelector('strong').textContent,'선택할 글자');assert.equal(body.textContent,'선택할 글자');editor.destroy();node.remove();
});

test('표 가장자리 추가는 해당 표의 끝에 적용되고 선택 삭제·뷰어 분리가 동작한다',()=>{
 const node=target(),editor=mountEditor({element:node,value:fromMarkdown('| A | B |\n| --- | --- |\n| C | D |\n\n문단\n\n| E | F |\n| --- | --- |\n| G | H |')});
 const tables=node.querySelectorAll('.se-table');tables[1].querySelector('.se-table-add-column').click();assert.equal(tables[0].querySelectorAll('tr:first-child>*').length,2);assert.equal(tables[1].querySelectorAll('tr:first-child>*').length,3);
 tables[1].querySelector('.se-table-add-row').click();assert.equal(tables[1].querySelectorAll('tr').length,3);assert.equal(tables[1].querySelector('tr:last-child').textContent,'');
 [...tables[1].querySelectorAll('button')].find(x=>x.textContent==='선택 행 삭제').click();assert.equal(tables[1].querySelectorAll('tr').length,2);
 const read=target(),dispose=renderViewer(read,editor.getValue());assert.equal(read.querySelector('button'),null);assert.equal(read.querySelectorAll('table').length,2);
 dispose();editor.destroy();node.remove();read.remove();
});
