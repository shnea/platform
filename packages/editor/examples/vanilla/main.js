// This import intentionally stays relative in the output: copy the browser folder alongside this page.
import {mountEditor,renderViewer,fromMarkdown,parseDocument} from '../browser/editor.js';
const appearance={fontSize:16,lineHeight:1.7};
let value=fromMarkdown('# 연결을 확인해 보세요\n\n**굵게**, *기울임*과 한글 🙂\n\n| 기능 | 상태 |\n| --- | --- |\n| 표 편집 | 준비됨 |'),saved,editor,dispose;
const notice=text=>{document.querySelector('#notice').textContent=text;};
const error=e=>{document.querySelector('#error').textContent=e.message;};
const view=()=>{dispose?.();dispose=renderViewer(document.querySelector('#viewer'),value,{appearance});document.querySelector('#json').textContent=JSON.stringify(value,null,2);};
function mount(){editor=mountEditor({element:document.querySelector('#editor'),value,appearance,onError:error,onChange:event=>{value=event.document;view();notice('편집 중 · 아직 보관하지 않은 변경이 있습니다.');}});}
document.querySelector('#save').addEventListener('click',()=>{saved=structuredClone(value);document.querySelector('#load').disabled=false;notice('현재 문서를 메모리에 보관했습니다. 새로고침하면 사라집니다.');});
document.querySelector('#load').addEventListener('click',()=>{value=parseDocument(saved);editor?.setValue(value);view();notice('보관한 문서를 불러왔습니다. 실행 취소 이력을 초기화했습니다.');});
document.querySelector('#new').addEventListener('click',()=>{value=parseDocument(null);editor?.setValue(value);view();notice('새 문서로 바꿨습니다.');});
document.querySelector('#undo').addEventListener('click',()=>editor?.undo());
document.querySelector('#toggle').addEventListener('click',event=>{if(editor){editor.destroy();editor=undefined;}else mount();event.currentTarget.textContent=editor?'에디터 해제':'에디터 다시 연결';document.querySelector('#undo').disabled=!editor;});
// Optional host endpoint: return JSON from an authenticated same-origin endpoint, never inline unescaped JSON in JSP.
const source=document.querySelector('#editor').dataset.documentUrl;
async function start(){try{if(source){const url=new URL(source,location.href);if(url.origin!==location.origin)throw Error('본문은 같은 서비스의 주소에서 불러와 주세요.');const response=await fetch(url,{credentials:'same-origin'});if(!response.ok)throw Error('문서를 불러오지 못했습니다. 다시 시도해 주세요.');value=parseDocument(await response.json());}mount();view();}catch(e){error(e);}}
void start();
window.addEventListener('pagehide',()=>{editor?.destroy();editor=undefined;dispose?.();dispose=undefined;});
window.addEventListener('pageshow',event=>{if(event.persisted){mount();view();}});
