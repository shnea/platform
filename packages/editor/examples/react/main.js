import {createElement as h,StrictMode,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {ShneaEditor,ShneaViewer} from '@shnea/editor/react';
import '@shnea/editor/style.css';
import {initial,appearance} from '../shared/document.js';

function App(){
 const [value,setValue]=useState(initial),[saved,setSaved]=useState(null),[visible,setVisible]=useState(true),[documentKey,setKey]=useState(0),[notice,setNotice]=useState('작성한 내용은 이 페이지 메모리에만 보관합니다.'),[error,setError]=useState('');
 const editor=useRef(null);
 return h('main',null,h('a',{href:'../'},'에디터 연동 예제'),h('h1',null,'React 에디터 연결'),
  h('p',null,'입력 → 메모리에 보관 → 문서 교체 → 다시 불러오기 → 읽기 결과를 확인하세요. 실제 서비스에서는 보관·불러오기를 호스트 API로 교체합니다. 첨부 업로드는 연결하지 않았습니다.'),
  h('div',{className:'actions'},
   h('button',{onClick:()=>{setSaved(structuredClone(value));setNotice('현재 문서를 메모리에 보관했습니다. 새로고침하면 사라집니다.');}},'메모리에 보관'),
   h('button',{disabled:!saved,onClick:()=>{setValue(structuredClone(saved));setKey(k=>k+1);setNotice('보관한 문서를 불러왔습니다. 실행 취소 이력을 초기화했습니다.');}},'보관한 문서 불러오기'),
   h('button',{onClick:()=>{setValue(null);setKey(k=>k+1);setNotice('새 문서로 바꿨습니다.');}},'새 문서'),
   h('button',{disabled:!visible,onClick:()=>editor.current?.undo()},'실행 취소'),
   h('button',{onClick:()=>setVisible(v=>!v)},visible?'에디터 해제':'에디터 다시 연결')),
  h('p',{role:'status'},notice),error&&h('p',{role:'alert'},error),
  visible&&h(ShneaEditor,{value,documentKey,appearance,onReady:api=>{editor.current=api;},onChange:event=>{setValue(event.document);setNotice('편집 중 · 아직 보관하지 않은 변경이 있습니다.');},onError:e=>setError(e.message)}),
  h('h2',null,'읽기 결과'),h(ShneaViewer,{value,appearance,onError:e=>setError(e.message)}),
  h('details',null,h('summary',null,'호스트에 전달되는 문서 JSON'),h('pre',null,JSON.stringify(value,null,2))));
}
createRoot(document.querySelector('#app')).render(h(StrictMode,null,h(App)));
