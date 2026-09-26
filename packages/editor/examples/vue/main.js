import {createApp,h,ref,shallowRef} from 'vue';
import {ShneaEditor,ShneaViewer} from '@shnea/editor/vue';
import '@shnea/editor/style.css';
import {initial,appearance} from '../shared/document.js';

createApp({setup(){
 const value=shallowRef(initial),saved=shallowRef(null),visible=ref(true),documentKey=ref(0),notice=ref('작성한 내용은 이 페이지 메모리에만 보관합니다.'),error=ref('');let editor;
 return ()=>h('main',null,[h('a',{href:'../'},'에디터 연동 예제'),h('h1','Vue 에디터 연결'),
  h('p','입력 → 메모리에 보관 → 문서 교체 → 다시 불러오기 → 읽기 결과를 확인하세요. 실제 서비스에서는 보관·불러오기를 호스트 API로 교체합니다. 첨부 업로드는 연결하지 않았습니다.'),
  h('div',{class:'actions'},[
   h('button',{onClick:()=>{saved.value=structuredClone(value.value);notice.value='현재 문서를 메모리에 보관했습니다. 새로고침하면 사라집니다.';}},'메모리에 보관'),
   h('button',{disabled:!saved.value,onClick:()=>{value.value=structuredClone(saved.value);documentKey.value++;notice.value='보관한 문서를 불러왔습니다. 실행 취소 이력을 초기화했습니다.';}},'보관한 문서 불러오기'),
   h('button',{onClick:()=>{value.value=null;documentKey.value++;notice.value='새 문서로 바꿨습니다.';}},'새 문서'),
   h('button',{disabled:!visible.value,onClick:()=>editor?.undo()},'실행 취소'),
   h('button',{onClick:()=>{visible.value=!visible.value;}},visible.value?'에디터 해제':'에디터 다시 연결')]),
  h('p',{role:'status'},notice.value),error.value&&h('p',{role:'alert'},error.value),
  visible.value&&h(ShneaEditor,{modelValue:value.value,documentKey:documentKey.value,appearance,'onUpdate:modelValue':next=>{value.value=next;notice.value='편집 중 · 아직 보관하지 않은 변경이 있습니다.';},onReady:api=>{editor=api;},onError:e=>{error.value=e.message;}}),
  h('h2','읽기 결과'),h(ShneaViewer,{value:value.value,appearance,onError:e=>{error.value=e.message;}}),
  h('details',[h('summary','호스트에 전달되는 문서 JSON'),h('pre',JSON.stringify(value.value,null,2))])]);
}}).mount('#app');
