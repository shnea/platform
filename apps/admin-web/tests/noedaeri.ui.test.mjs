import assert from 'node:assert/strict';
import {test,after} from 'node:test';
import {createRequire} from 'node:module';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
const adminRequire=createRequire(new URL('../package.json',import.meta.url));
const editorRequire=createRequire(new URL('../../../packages/editor/package.json',import.meta.url));
const {JSDOM}=editorRequire('jsdom'),{build}=editorRequire('esbuild');
const React=adminRequire('react'),{act}=React;
const dom=new JSDOM('<!doctype html><body></body>',{url:'https://platform.example',pretendToBeVisual:true});
for(const key of ['window','document','navigator','Node','HTMLElement','Element','HTMLInputElement','HTMLTextAreaElement','HTMLSelectElement','MutationObserver','localStorage','self'])Object.defineProperty(globalThis,key,{configurable:true,value:key==='self'?dom.window:dom.window[key]});
const {createRoot}=adminRequire('react-dom/client');
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
dom.window.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
dom.window.HTMLMediaElement.prototype.pause=function(){};
dom.window.confirm=()=>false;
const folder=mkdtempSync(join(tmpdir(),'platform-noedaeri-ui-')),bundle=join(folder,'ui.mjs');
await build({stdin:{contents:"export {NoedaeriWorkspace} from './src/features/noedaeri/NoedaeriWorkspace'; export {NoedaeriResult} from './src/features/noedaeri/NoedaeriResult'; export {IndependentTask} from './src/features/noedaeri/IndependentTask';",resolveDir:fileURLToPath(new URL('..',import.meta.url))},bundle:true,platform:'node',format:'esm',jsx:'automatic',outfile:bundle,loader:{'.css':'empty'},plugins:[{name:'ui-test-boundary',setup(builder){builder.onResolve({filter:/^react(?:\/|$)|^react-dom(?:\/|$)/},args=>({path:adminRequire.resolve(args.path),external:true}));builder.onResolve({filter:/shared\/auth$/},()=>({path:'mock-auth',namespace:'test'}));builder.onLoad({filter:/.*/,namespace:'test'},()=>({contents:"export const api=(...args)=>globalThis.__noedaeriTestApi(...args); export const auth={token:'ui-test-only',updateToken:async()=>true};",loader:'js'}));}}]});
const {NoedaeriWorkspace,NoedaeriResult,IndependentTask}=await import(pathToFileURL(bundle).href);
const featureIds=['raya.route','embeddings','n8n.execute','text.translate','vector.index'];
const environment='00000000-0000-4000-8000-000000000010',job='00000000-0000-4000-8000-000000000020';
let root,host,calls,configured,respond;
function reset(){calls=[];configured=true;respond=async()=>[];globalThis.__noedaeriTestApi=async(path,method='GET',body,headers)=>{calls.push({path,method,body,headers});if(path==='/ai/services')return {configured,features:featureIds.map(id=>({id,status:'implemented'}))};return respond(path,method,body,headers);};globalThis.fetch=async(path,options={})=>{calls.push({path,method:options.method??'GET',body:options.body?JSON.parse(options.body):undefined,headers:options.headers});const value=String(path).endsWith('/services')?{configured,reachable:true,services:[]} :String(path).endsWith('/retention')?{policies:[]}:await respond(String(path),options.method??'GET',options.body?JSON.parse(options.body):undefined,options.headers);return new Response(JSON.stringify(value),{status:200,headers:{'Content-Type':'application/json'}});};}
async function mount(element){reset();host=document.body.appendChild(document.createElement('div'));root=createRoot(host);await act(async()=>{root.render(element);});}
async function dispose(){await act(async()=>root.unmount());host.remove();}
const workspace=()=>React.createElement(NoedaeriWorkspace,{environmentId:environment,environmentLabel:'테스트 프로젝트 / DEV',available:true,filesEnabled:true,onBusyChange:()=>{}});
const button=text=>[...host.querySelectorAll('button')].find(node=>node.textContent.trim()===text);
async function click(node){assert.ok(node,'button exists');await act(async()=>node.click());}
async function menu(title){await click([...host.querySelectorAll('nav button')].find(node=>node.textContent.trim()===title));}
async function change(node,value){assert.ok(node);const prototype=node.tagName==='SELECT'?dom.window.HTMLSelectElement.prototype:node.tagName==='TEXTAREA'?dom.window.HTMLTextAreaElement.prototype:dom.window.HTMLInputElement.prototype;await act(async()=>{Object.getOwnPropertyDescriptor(prototype,'value').set.call(node,value);node.dispatchEvent(new dom.window.Event(node.tagName==='SELECT'?'change':'input',{bubbles:true}));});}
async function submit(){await act(async()=>host.querySelector('form').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true})));}
const posts=()=>calls.filter(call=>call.method==='POST');
after(()=>{dom.window.close();rmSync(folder,{recursive:true,force:true});});

test('15개 메뉴 진입과 샘플 안내는 실제 POST·업로드·receipt를 호출하지 않는다',async()=>{
 await mount(workspace());
 for(const title of ['영상 자막','PDF 추출','이미지 OCR','음성 인식','음성 생성','목소리 관리','이미지 처리','영상 처리','영상 썸네일','문장 번역','AI 작업','난이도 판단','임베딩','문서 색인','벡터 검색']){await menu(title);assert.ok(host.textContent.includes(title));assert.ok(host.textContent.includes('샘플'));}
 await menu('개요');await menu('실행 이력');await menu('사용량');assert.equal(posts().length,0);assert.ok(calls.every(call=>!call.path.includes('receipt')));await dispose();
});
test('샘플은 입력만 채우며 실험글의 필수 주제와 검색 컬렉션을 제공한다',async()=>{
 await mount(workspace());await menu('AI 작업');await change(host.querySelector('form select'),'article.draft');await click(button('샘플 입력 채우기'));assert.equal(JSON.parse(host.querySelectorAll('form textarea')[1].value).context.topic,'공통 AI 서비스 연동');assert.equal(posts().length,0);
 await menu('문서 색인');await change(host.querySelector('form select'),'replace_all');await click(button('샘플 입력 채우기'));assert.equal(host.querySelector('form select').value,'upsert');assert.equal(host.querySelector('form input').value,'noedaeri-test');assert.equal(posts().length,0);await dispose();
});
test('미설정 기능은 실행을 막고 샘플과 읽기 전용 상태 확인은 허용한다',async()=>{
 await mount(workspace());configured=false;await click(button('AI 설정 다시 확인'));await menu('문장 번역');assert.equal(button('테스트 실행').disabled,true);await click(button('샘플 입력 채우기'));await submit();assert.equal(posts().length,0);assert.ok(host.textContent.includes('미설정'));await dispose();
});
test('전체 교체는 대상 확인 전 호출하지 않고 빈 배열·한 번 요청을 유지한다',async()=>{
 await mount(workspace());await menu('문서 색인');await change(host.querySelector('form select'),'replace_all');await change(host.querySelector('form textarea'),'[]');respond=async(path,method,body)=>method==='POST'?{id:job,request_id:body.request_id,collection:body.collection,mode:body.mode,status:'succeeded',document_count:0,indexed_count:0,deleted_count:2,result:{dimensions:768,total_tokens:0,usage_estimated:true}}:[];
 await submit();assert.equal(posts().length,0);assert.ok(host.querySelector('dialog[open]').textContent.includes('테스트 프로젝트 / DEV'));await click(button('변경 확인 후 실행'));assert.equal(posts().length,1);assert.deepEqual(posts()[0].body.documents,[]);assert.equal('delete_ids' in posts()[0].body,false);assert.equal(posts()[0].headers['X-Confirm-Collection'],'portfolio');assert.ok(host.textContent.includes('삭제한 문서'));assert.equal(button('테스트 실행').disabled,true);assert.equal(button('샘플 입력 채우기').disabled,true);await dispose();
});
test('응답 유실 뒤 같은 요청 확인은 ID·내용을 고정하고 조회로 재실행하지 않는다',async()=>{
 await mount(workspace());await menu('문장 번역');await click(button('샘플 입력 채우기'));let count=0;respond=async(path,method,body)=>{if(method==='POST'){if(count++===0)throw Error('모의 응답 유실');return {id:job,request_id:body.request_id,task_type:'text.translate',status:'succeeded',result:{type:'text_translate',translated_text:'처리 결과가 준비되었습니다.',usage:{prompt_tokens:0,total_tokens:0}}};}return {id:job,request_id:posts()[0].body.request_id,task_type:'text.translate',status:'succeeded',result_expired:true,result:null};};
 await submit();assert.ok(button('같은 요청 확인'));await click(button('같은 요청 확인'));assert.equal(posts().length,2);assert.deepEqual(posts()[0].body,posts()[1].body);assert.ok(host.textContent.includes('Hello.'));assert.ok(host.textContent.includes('처리 결과가 준비되었습니다.'));await click(button('상태 조회'));assert.equal(posts().length,2);assert.ok(host.textContent.includes('만료'));assert.equal(host.querySelector('.noedaeri-comparison').textContent.includes('처리 결과가 준비되었습니다.'),false);await dispose();
});
test('컬렉션 통계는 0건도 표시하며 작업 목록이나 모델 호출로 오인하지 않는다',async()=>{
 await mount(workspace());await menu('문서 색인');respond=async()=>[{collection:'빈 컬렉션',document_count:0,dimensions:768,total_tokens:0,model:'models/gemini-embedding-001'}];await click(button('컬렉션 통계'));assert.equal(host.querySelectorAll('.noedaeri-table tbody tr').length,1);assert.ok(host.querySelector('.noedaeri-table').textContent.includes('0'));assert.equal(button('상세'),undefined);assert.equal(posts().length,0);await dispose();
});
test('Raya 확률·잘림과 벡터 값·누락 사용량을 결과 종류별로 렌더링한다',async()=>{
 await mount(React.createElement(NoedaeriResult,{context:'raya',request:null,value:{task_type:'chat.general',model_tier:'L2',probabilities:{L1:0,L2:0.8,L3:0.2},confidence:0.7,input_truncated:true,input_tokens:0,inference_ms:0}}));assert.equal(host.querySelectorAll('meter').length,3);assert.ok(host.textContent.includes('앞부분'));assert.equal(host.textContent.includes('AI 입력·결과'),false);await dispose();
 await mount(React.createElement(NoedaeriResult,{context:'embeddings',request:null,value:{model:'sample',dimensions:3,data:[{index:0,embedding:[0,-0.1,0.2]}],usage:{total_tokens:0}}}));assert.ok(host.textContent.includes('미확인'));assert.equal(host.querySelector('button').disabled,false);assert.ok(host.textContent.includes('0, -0.1, 0.2'));assert.equal(posts().length,0);await dispose();
});
test('검색 문맥 HTML은 실행하지 않고 코사인 값·빈 결과·이력 원문을 구분한다',async()=>{
 await mount(React.createElement(NoedaeriResult,{context:'search',request:null,value:{query:'test',collection:'sample',total_candidates:1,matched_count:1,results:[{document_id:'one',content:'<script>unsafe()</script>',similarity:0}]}}));assert.equal(host.querySelector('script'),null);assert.ok(host.textContent.includes('정답률 아님'));assert.ok(host.textContent.includes('<script>'));await dispose();
 await mount(React.createElement(NoedaeriResult,{context:'history',request:null,value:{id:job,task_type:'chat.general',request_id:'old',status:'succeeded',result:{ai_result:'답변',usage:{}}}}));assert.ok(host.textContent.includes('이전 실행 원문'));assert.ok(host.textContent.includes('답변'));assert.ok(host.textContent.includes('미확인'));await dispose();
});
test('독립 작업 결과의 텍스트·구간은 읽기로만 표시하고 샘플 음성 재생은 명시적으로 준비한다',async()=>{
 reset();respond=async()=>({id:job,status:'succeeded',kind:'stt.transcribe',content:{text:'음성 전사',segments:[{start:0,end:1,text:'첫 구간'}]},artifacts:[{name:'transcript.json',bytes:100}]});host=document.body.appendChild(document.createElement('div'));root=createRoot(host);await act(async()=>root.render(React.createElement(IndependentTask,{menu:'stt',environmentId:environment,available:true,onBusyChange:()=>{},initialJobId:job})));assert.ok(host.textContent.includes('음성 전사'));assert.ok(host.textContent.includes('전사 구간'));assert.equal(posts().length,0);assert.equal(host.querySelector('audio'),null);assert.ok(button('미리보기 준비'));await dispose();
});
test('파일 추가 후 바꾼 burned·한국어가 대기열에 반영되고 미설정이면 업로드가 막힌다',async()=>{
 await mount(workspace());await menu('영상 처리');const input=host.querySelector('input[type=file]');Object.defineProperty(input,'files',{configurable:true,value:[new dom.window.File(['fixture'],'sample.mp4',{type:'video/mp4'})]});await act(async()=>input.dispatchEvent(new dom.window.Event('change',{bubbles:true})));
 const subtitle=[...host.querySelectorAll('label')].find(node=>node.textContent.includes('영상 자동 자막')).querySelector('select');await change(subtitle,'burned');const language=[...host.querySelectorAll('label')].find(node=>node.textContent.includes('음성 인식 언어')).querySelector('select');await change(language,'ko');assert.ok(host.querySelector('.file-upload-list').textContent.includes('영상에 자막 입히기 · ko'));assert.equal(posts().length,0);await dispose();
 await mount(workspace());configured=false;await menu('영상 처리');const picker=host.querySelector('input[type=file]');Object.defineProperty(picker,'files',{configurable:true,value:[new dom.window.File(['fixture'],'sample.mp4')]});await act(async()=>picker.dispatchEvent(new dom.window.Event('change',{bubbles:true})));assert.equal(button('업로드 시작').disabled,true);assert.equal(posts().length,0);await dispose();
});
test('목소리 삭제는 ID·영향 확인을 요구하며 기존 합성 WAV는 보존한다',async()=>{
 await mount(workspace());respond=async(path,method)=>path.endsWith('/voices')?[{id:job,name:'검수 목소리',kind:'preset',speaker:'Sohee',status:'ready'}]:method==='DELETE'?{deleted:true}:[];await menu('목소리 관리');await click(button('삭제'));assert.equal(calls.filter(call=>call.method==='DELETE').length,0);assert.ok(host.querySelector('dialog').textContent.includes(job));assert.ok(host.querySelector('dialog').textContent.includes('이미 저장한 합성 WAV는 삭제하지 않습니다'));await click(button('확인 후 영구 삭제'));const deletion=calls.find(call=>call.method==='DELETE');assert.equal(deletion.headers['X-Confirm-Voice'],job);assert.equal(deletion.path,`/api/v1/files/admin/environments/${environment}/noedaeri/voices/${job}`);assert.equal(posts().length,0);await dispose();
});
test('PDF 페이지·OCR 줄 좌표·TTS 파일·자막 근사 시각을 원본 JSON과 별도로 표시한다',async()=>{
 for(const [menu,content,artifact,label] of [['pdf',{pages:[{page:1,method:'text',text:'첫 페이지',lines:[]} ]},'document.json','첫 페이지'],['ocr',{text:'인식 문장',lines:[{text:'첫 줄',bounding_box:{left:0,top:0,width:0.5,height:0.1},confidence:0.9}]},'ocr.json','줄별 위치'],['tts',{},'speech.wav','speech.wav'],['subtitles',{text:'자막 전사',segments:[{start:0,end:1,text:'첫 자막'}]},'subtitles.vtt','근삿값']]){
  reset();respond=async path=>path.endsWith('/voices')?[]:({id:job,status:'succeeded',content,artifacts:[{name:artifact,bytes:10}]});host=document.body.appendChild(document.createElement('div'));root=createRoot(host);await act(async()=>root.render(React.createElement(IndependentTask,{menu,environmentId:environment,available:true,onBusyChange:()=>{},initialJobId:job})));assert.ok(host.textContent.includes(label));assert.ok(host.querySelector('details').textContent);assert.equal(posts().length,0);await dispose();
 }
});
