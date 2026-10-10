import {useEffect,useRef,useState,type FormEvent} from 'react';
import {api} from '../../shared/auth';
import {Icon} from '../../shared/Icon';
import {Dialog} from '../../shared/Dialog';
import {FileWorkspace} from '../files/FileWorkspace';
import {IndependentTask} from './IndependentTask';
import {VoiceManager} from './VoiceManager';
import {NoedaeriResult} from './NoedaeriResult';
import {aiFeature} from './result-contract';
import {sampleFields,fileSamples} from './test-samples';
import {taskMenu} from './independent-contract';
import {fileApi} from '../files/file-api';
import {activeJob,initialFields,menuGroups,tasks,testRequest,type TestFields,type TestRequest} from './test-contract';
import './noedaeri.css';

type Json=Record<string,unknown>;
const object=(value:unknown):Json=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Json:{};
const states:Record<string,string>={pending:'접수 대기',running:'실행 중',succeeded:'완료',failed:'실패',cancelled:'취소됨'};
const languages={ko:'한국어',en:'영어',ja:'일본어',zh:'중국어',es:'스페인어',fr:'프랑스어',de:'독일어'};
const message=(error:unknown)=>error instanceof Error?error.message:'요청을 처리하지 못했습니다.';
export function NoedaeriWorkspace({environmentId,environmentLabel,available,filesEnabled,onBusyChange}:{environmentId:string;environmentLabel:string;available:boolean;filesEnabled:boolean;onBusyChange:(value:boolean)=>void}) {
 const [menu,setMenu]=useState('overview'),[fields,setFields]=useState<TestFields>(initialFields),[requestId,setRequestId]=useState(()=>crypto.randomUUID());
 const [result,setResult]=useState<unknown>(null),[error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false),[confirm,setConfirm]=useState<TestRequest|null>(null);
 const [submission,setSubmission]=useState<TestRequest|null>(null),[services,setServices]=useState<Json>({}),[polling,setPolling]=useState(false);
 const [historyKind,setHistoryKind]=useState('jobs'),[jobId,setJobId]=useState(''),[mediaBusy,setMediaBusy]=useState(false);
 const [fileJobId,setFileJobId]=useState<string>();
 const [resultContext,setResultContext]=useState(''),[mediaServices,setMediaServices]=useState<Json>({});
 const mounted=useRef(true),version=useRef(0),pollCount=useRef(0),locked=useRef(false);
 const root=`/environments/${environmentId}/ai`;
 const selected=menuGroups.flatMap(group=>[...group.items]).find(item=>item.id===menu);
 const disabled=busy||mediaBusy||!available;
 const frozen=!!submission;
 const feature=aiFeature(menu,services),executable=feature.configured&&feature.known&&feature.implemented;
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;version.current++;onBusyChange(false);};},[onBusyChange]);
 useEffect(()=>{onBusyChange(busy||mediaBusy);},[busy,mediaBusy,onBusyChange]);
 async function refreshServices(){try{const value=await api<Json>('/ai/services');if(mounted.current){setServices(value);setError('');}}catch(error){if(mounted.current)setError(message(error));}}
 useEffect(()=>{if(available)void refreshServices();},[available]);
 function field<Key extends keyof TestFields>(key:Key,value:TestFields[Key]){setFields(old=>({...old,[key]:value}));}
 function changeMenu(value:string){if(busy||mediaBusy)return;version.current++;setMenu(value);setResult(null);setError('');setNotice('');setSubmission(null);setConfirm(null);setPolling(false);setJobId('');setFileJobId(undefined);setRequestId(crypto.randomUUID());}
 async function read(path:string) {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');const current=version.current;
  try{const value=menu==='history'&&historyKind==='tasks'?await fileApi<unknown>(environmentId,'/noedaeri'+path):await api<unknown>(root+path);if(mounted.current&&current===version.current){setResultContext(path==='/indexing/collections'?'collections':menu);setResult(value);}}catch(error){if(mounted.current&&current===version.current){setError(message(error));setPolling(false);}}finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function run(request:TestRequest) {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');setNotice('');setPolling(false);const current=version.current;
  try {
   const value=await api<unknown>(root+request.path,'POST',request.body,request.headers);
   if(!mounted.current||current!==version.current)return;
   setResultContext(menu);setResult(value);setJobId(String(object(value).id??''));pollCount.current=0;setPolling(activeJob(value));
  }catch(error){if(mounted.current&&current===version.current){setError(message(error));if('request_id' in request.body)setNotice('응답이 유실됐을 수 있습니다. 같은 요청 확인은 동일 ID·내용으로만 전송하며 새 요청은 별도 실행입니다.');}}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 function submit(event:FormEvent) {
  event.preventDefault();if(disabled||frozen||!executable)return;setError('');
  try {const request=testRequest(menu,fields,requestId);if(menu==='indexing'&&fields.mode!=='upsert'){setConfirm(request);return;}setSubmission(request);void run(request);}
  catch(error){setError(message(error));}
 }
 const currentJob=object(result),jobPath=menu==='history'&&historyKind==='tasks'?'/tasks':menu==='indexing'||menu==='history'&&historyKind==='indexing'?'/indexing':'/jobs';
 useEffect(()=>{
  if(!polling||!activeJob(result)||!currentJob.id||busy)return;
  if(pollCount.current>=180){setPolling(false);setNotice('자동 조회를 중단했습니다. 상태 조회로 계속 확인할 수 있으며 작업은 재실행하지 않습니다.');return;}
  const timer=setTimeout(()=>{pollCount.current++;void read(`${jobPath}/${currentJob.id}`);},5000);
  return()=>clearTimeout(timer);
 },[polling,result,busy,jobPath]);
 async function cancelJob() {
  if(!currentJob.id||!window.confirm('이 작업의 취소를 요청할까요? 취소 접수는 실행 종료와 다릅니다.'))return;
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try{await api(root+`${jobPath}/${currentJob.id}/cancel`,'POST');setNotice('취소를 요청했습니다. 상태 조회에서 실제 종료 여부를 확인하세요.');setPolling(false);}catch(error){setError(message(error));}finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 function download() {const url=URL.createObjectURL(new Blob([JSON.stringify(result,null,2)],{type:'application/json'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=`noedaeri-${menu}.json`;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
 const media=menu==='image'||menu==='video';
 const mediaRows=Array.isArray(mediaServices.services)?mediaServices.services:object(mediaServices.services).services;
 const mediaService=(Array.isArray(mediaRows)?mediaRows:[]).map(object).find(row=>row.kind===(menu==='image'?'image.package':'video.package'));
 const mediaUnavailable=mediaServices.configured!==true||mediaServices.reachable===false||mediaService?.available===false;
 useEffect(()=>{if(!media||!available||!filesEnabled)return;let cancelled=false;void fileApi<Json>(environmentId,'/noedaeri/services').then(value=>{if(!cancelled)setMediaServices(value);}).catch(error=>{if(!cancelled){setMediaServices({reachable:false});setError(message(error));}});return()=>{cancelled=true;};},[media,available,filesEnabled,environmentId]);
 const independent=['subtitles','pdf','ocr','stt','tts','thumbnail','voices'].includes(menu);
 return <section className="noedaeri-workspace" aria-label="뇌대리 테스트">
  <p className="small muted">{environmentLabel} · 실제 실행·문서 저장·파일 업로드입니다. 무료 한도·사용량이 발생할 수 있습니다. 키는 서버에서만 사용합니다.</p>
  {!available&&<p className="warning">사용 중인 프로젝트와 반영 완료 환경을 선택하세요.</p>}
  <div className="noedaeri-layout"><nav className="noedaeri-nav" aria-label="뇌대리 기능">
   <button className="secondary" aria-current={menu==='overview'?'page':undefined} disabled={busy||mediaBusy} onClick={()=>changeMenu('overview')}>개요</button>
   {menuGroups.map(group=><div key={group.name}><h3>{group.name}</h3>{group.items.map(item=><button key={item.id} className="secondary" aria-current={menu===item.id?'page':undefined} disabled={busy||mediaBusy} onClick={()=>changeMenu(item.id)}><span>{item.title}</span>{!item.ready&&<span className="small muted">연결 예정</span>}</button>)}</div>)}
   <div><h3>실행·운영</h3>{[['history','실행 이력'],['usage','사용량']].map(([id,title])=><button className="secondary" key={id} aria-current={menu===id?'page':undefined} disabled={busy||mediaBusy} onClick={()=>changeMenu(id)}>{title}</button>)}</div>
  </nav><div className="noedaeri-content">
   <h3>{selected?.title??(menu==='history'?'실행 이력':menu==='usage'?'사용량':'뇌대리 연결 개요')}</h3>
   {(feature.id||menu==='overview')&&<div className="noedaeri-service"><p className="small muted">AI 어댑터: {services.configured===true?'설정됨':services.configured===false?'미설정':'확인 전'}{feature.id&&` · ${feature.known?(feature.implemented?'연결 구현됨':'아직 미연결'):'기능 상태 미확인'}`} · 설정은 실제 공급자 실행·잔여 한도를 보장하지 않습니다.</p><button type="button" className="secondary" disabled={disabled} onClick={()=>void refreshServices()}>AI 설정 다시 확인</button></div>}
   {media&&<details className="noedaeri-sample"><summary>{fileSamples[menu].title} · 입력 안내</summary><p>{fileSamples[menu].text}</p><p className="small muted">안내만으로 실행하지 않습니다. 파일을 선택하고 업로드 버튼을 눌러야 실제 처리가 시작됩니다.</p></details>}
   {media&&<p className={mediaUnavailable?'warning':'small muted'}>파일 어댑터: {mediaServices.configured===true?'설정됨':mediaServices.configured===false?'미설정':'확인 전'} · {mediaServices.reachable===false?'서버 연결 실패':mediaService?.available===false?'기능 미설정':mediaService?'기능 설정 확인됨':'기능 상태 미확인'}. 미설정·연결 실패 시 테스트 업로드를 막습니다. 기존 결과는 재처리하지 않습니다.</p>}
   {error&&<p role="alert" className="alert">{error}</p>}{notice&&<p role="status" className="notice">{notice}</p>}
   {menu==='overview'&&<><p>이미지·영상·독립 자막·PDF·OCR·STT·TTS·썸네일과 AI·번역·Raya·임베딩·색인·검색을 이 환경에서 검수합니다. 입력은 버튼을 눌렀을 때만 실행하며 화면 새로고침으로 모델을 다시 호출하지 않습니다.</p><p>AI 어댑터 설정: {services.configured===true?'등록됨':services.configured===false?'미설정':'확인 중'}. 설정 확인은 실제 공급자 실행 성공을 의미하지 않습니다.</p><p className="warning">목소리 등록·관리와 TTS 선택을 연결했습니다. 실제 모델·참조 품질 검수는 별도입니다. 독립 인식·음성 작업의 실행 가능 상태는 각 기능의 서비스 설정을 확인하세요. 영상에 자막 입히기는 영상 처리의 sidecar/burned 선택과 구분합니다.</p><p>검색·색인은 PostgreSQL 계약입니다. n8n Qdrant 컬렉션과 자동 동기화되지 않으며 RAG 워크플로에서 색인 검색 API를 별도로 연결해야 합니다.</p></>}
   {selected&&!selected.ready&&<><p className="warning">뇌대리 제공 기능이지만 이 플랫폼의 독립 작업 어댑터는 아직 연결되지 않았습니다. 실행 가능한 기능으로 표시하지 않습니다.</p>{menu==='subtitles'&&<><p>통합 영상 처리에서 자막 파일 생성(SRT/VTT) 또는 영상에 입히기를 선택할 수 있습니다.</p><button className="secondary" disabled={busy} onClick={()=>changeMenu('video')}>영상 처리로 이동</button></>}</>}
   {media&&(filesEnabled?<><p className="small muted">{menu==='image'?'썸네일 JPEG·WebP 미리보기를 확인하세요.':'HLS 화질·자동 자막·자막/전사 다운로드를 상세·보기에서 확인하세요.'} 테스트 파일은 기본 비공개·tmp 보존입니다. 실제 저장되며 자동 정리가 꺼져 있으면 직접 삭제해야 합니다. 목록은 이 환경의 기존 파일도 포함합니다.</p><FileWorkspace key={menu} environmentId={environmentId} environmentLabel={environmentLabel} available={available} processingAvailable={!mediaUnavailable} onBusyChange={setMediaBusy} testKind={menu}/></>:<p className="warning">프로젝트 설정에서 파일 서비스 사용을 켜야 합니다.</p>)}
   {independent&&(filesEnabled?(menu==='voices'?<VoiceManager key={environmentId+(fileJobId??'')} environmentId={environmentId} available={available} onBusyChange={setMediaBusy} initialJobId={fileJobId}/>:<IndependentTask key={menu+environmentId+(fileJobId??'')} menu={menu} environmentId={environmentId} available={available} onBusyChange={setMediaBusy} initialJobId={fileJobId}/>):<p className="warning">파일 서비스 사용을 켜야 원본·결과를 저장할 수 있습니다.</p>)}
   {selected?.ready&&!media&&!independent&&<form className="noedaeri-form" onSubmit={submit}>
    <div className="actions"><button type="button" className="secondary" disabled={disabled||frozen} onClick={()=>{setFields(sampleFields(menu,fields));setError('');setNotice('샘플 입력만 채웠습니다. 실행 버튼을 눌러야 실제 호출·저장이 진행됩니다.');}}>샘플 입력 채우기</button></div>
    <fieldset disabled={disabled||frozen}>
     {(menu==='jobs'||menu==='raya')&&<label>작업 종류<select value={fields.task} onChange={event=>field('task',event.target.value)}>{Object.entries(tasks).map(([id,title])=><option key={id} value={id}>{title} ({id})</option>)}</select></label>}
     {(menu==='indexing'||menu==='search')&&<label>컬렉션<input required maxLength={64} value={fields.collection} onChange={event=>field('collection',event.target.value)}/></label>}
     {menu!=='indexing'&&<label>{menu==='embeddings'&&fields.batch?'텍스트 배열 JSON':menu==='search'?'검색 질의':menu==='translation'?'번역할 문장':'입력 텍스트'}<textarea required rows={6} maxLength={menu==='translation'?4000:menu==='raya'?16000:menu==='search'?10000:200000} value={fields.prompt} onChange={event=>field('prompt',event.target.value)}/></label>}
     {menu==='raya'&&<><label>작업 지침 (선택)<textarea rows={3} maxLength={8000} value={fields.instruction} onChange={event=>field('instruction',event.target.value)}/></label><label className="checkbox"><input type="checkbox" checked={fields.hasImages} onChange={event=>field('hasImages',event.target.checked)}/>이미지 포함 여부 (이미지 내용은 분석하지 않음)</label></>}
     {menu==='jobs'&&<label>부가 입력 JSON (context·messages·collection 등)<textarea rows={6} value={fields.inputJson} onChange={event=>field('inputJson',event.target.value)}/><span className="hint">프로젝트·환경·소유자·캐시 판정을 임의로 넣지 마세요. 블로그 저장·캐시·업무 반영은 실행하지 않습니다.</span></label>}
     {menu==='translation'&&<div className="form-grid"><label>원문 언어<select value={fields.sourceLanguage} onChange={event=>field('sourceLanguage',event.target.value)}><option value="auto">자동 감지</option>{Object.entries(languages).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label><label>번역 언어<select value={fields.targetLanguage} onChange={event=>field('targetLanguage',event.target.value)}>{Object.entries(languages).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label></div>}
     {menu==='embeddings'&&<><label className="checkbox"><input type="checkbox" checked={fields.batch} onChange={event=>field('batch',event.target.checked)}/>배치 배열 (최대 100건)</label><label>차원<select value={fields.dimensions} onChange={event=>field('dimensions',event.target.value)}>{['768','1536','3072'].map(value=><option key={value}>{value}</option>)}</select></label><p className="small muted">models/gemini-embedding-001 · 검색·색인은 768차원입니다.</p></>}
     {menu==='indexing'&&<><label>색인 작업<select value={fields.mode} onChange={event=>field('mode',event.target.value)}><option value="upsert">추가·갱신</option><option value="replace_all">전체 교체</option><option value="delete">ID 삭제</option></select></label>{fields.mode!=='delete'&&<label>문서 배열 JSON (최대 100건)<textarea rows={9} value={fields.documents} onChange={event=>field('documents',event.target.value)}/></label>}{fields.mode!=='replace_all'&&<label>삭제 ID 배열 JSON<textarea rows={3} value={fields.deleteIds} onChange={event=>field('deleteIds',event.target.value)}/></label>}<p className="warning">실제 저장된 문서에 반영됩니다. 전체 교체의 빈 배열은 이 컬렉션 전체 삭제입니다. 100건 초과 분할 교체·자동 청크 분할은 지원하지 않습니다.</p></>}
     {menu==='search'&&<div className="form-grid"><label>결과 수<input type="number" min={1} max={50} required value={fields.queryLimit} onChange={event=>field('queryLimit',event.target.value)}/></label><label>최소 코사인 유사도<input type="number" min={-1} max={1} step={0.01} required value={fields.similarity} onChange={event=>field('similarity',event.target.value)}/></label></div>}
    </fieldset>
    {['jobs','translation','indexing'].includes(menu)&&<label>요청 ID<input readOnly value={requestId}/><span className="hint">전송 내용·ID를 유지합니다. 새 요청은 별도의 실행입니다.</span></label>}
    <div className="actions"><button disabled={disabled||frozen||!executable}><Icon name="play"/>테스트 실행</button>{submission&&error&&'request_id' in submission.body&&<button type="button" className="secondary" disabled={disabled||!executable} onClick={()=>void run(submission)}>같은 요청 확인</button>}{frozen&&<button type="button" className="secondary" disabled={disabled} onClick={()=>{setSubmission(null);setResult(null);setJobId('');setError('');setNotice('');setPolling(false);setRequestId(crypto.randomUUID());}}>새 테스트 입력</button>}{menu==='indexing'&&<button type="button" className="secondary" disabled={disabled} onClick={()=>{setPolling(false);void read('/indexing/collections');}}>컬렉션 통계</button>}</div>
   </form>}
   {menu==='history'&&<><form className="noedaeri-form" onSubmit={event=>{event.preventDefault();void read(`/${historyKind}${jobId?'/'+jobId:''}`);}}><label>작업 구분<select disabled={busy} value={historyKind} onChange={event=>{setHistoryKind(event.target.value);setResult(null);setPolling(false);}}><option value="jobs">AI·번역</option><option value="indexing">문서 색인</option><option value="tasks" disabled={!filesEnabled}>독립 자막·PDF·OCR·음성·썸네일</option></select></label><label>작업 ID (비우면 최근 목록)<input disabled={busy} value={jobId} onChange={event=>setJobId(event.target.value)} pattern="[0-9a-fA-F-]{36}"/></label><button disabled={disabled}><Icon name="refresh-cw"/>조회</button><p className="small muted">이미지·영상 통합 처리 이력은 파일 상세·보기에 유지합니다.</p></form>{historyKind==='tasks'&&(Array.isArray(result)?result:[result]).map((value,index)=>{const row=object(value),target=taskMenu(row.kind);return target&&<p key={index}><button className="secondary" disabled={disabled} onClick={()=>{changeMenu(target);setFileJobId(String(row.id));}}>{String(row.kind)} · {String(row.status)} · 기능 결과 보기</button></p>;})}</>}
   {menu==='usage'&&<><p className="small muted">현재 환경의 공급자 보고 사용량입니다. 실제 비용·무료 한도·잔여 토큰으로 단정하지 않습니다.</p><button className="secondary" disabled={disabled} onClick={()=>void read('/usage')}><Icon name="refresh-cw"/>사용량 조회</button></>}
   {busy&&<p role="status">요청 처리 중…</p>}
   {result!==null&&!media&&<section className="noedaeri-result" aria-label="실행 결과">
    <div className="section-line"><h4>결과</h4><button className="secondary" disabled={busy} onClick={download}><Icon name="download"/>JSON 다운로드</button></div>
    {!!currentJob.status&&<p role="status">{states[String(currentJob.status)]??String(currentJob.status)}{currentJob.reused===true?' · 기존 요청 재사용':''}</p>}
    {!!currentJob.id&&<><p className="small">작업 ID: {String(currentJob.id)}</p><div className="actions"><button className="secondary" disabled={disabled} onClick={()=>void read(`${jobPath}/${currentJob.id}`)}>상태 조회</button>{activeJob(result)&&jobPath!=='/tasks'&&<button className="secondary" disabled={disabled||jobPath==='/indexing'&&currentJob.status!=='pending'} onClick={()=>void cancelJob()}>취소 요청</button>}</div></>}
    {!!currentJob.expires_at&&<p className="small muted">결과 만료: {new Date(String(currentJob.expires_at)).toLocaleString('ko-KR')} · 화면 조회만으로 수령 확인을 보내지 않습니다.</p>}
    {(currentJob.result_expired===true||currentJob.result_state==='expired')&&<p className="warning">결과 보존 기간이 지나 내용을 제공하지 않습니다. 조회가 모델을 재실행하지 않으며 새 실행에는 새 요청 ID가 필요합니다.</p>}
    {currentJob.result_received===true&&<p className="notice">이미 수령 확인된 결과입니다. 작업 이력만 제공됩니다.</p>}
    {!!currentJob.error_code&&<p className="alert">{String(currentJob.error_code)} {String(currentJob.error_message??'')}</p>}
    <NoedaeriResult value={result} context={resultContext} request={submission?.body??null}/>
    {Array.isArray(result)&&resultContext!=='collections'&&result.map((item,index)=>{const row=object(item);return <div className="section-line" key={index}><span>{String(row.task_type??row.collection??'')} · {states[String(row.status)]??String(row.document_count??'')} · {String(row.id??'')}</span>{!!row.id&&<button className="secondary" disabled={disabled} onClick={()=>{setJobId(String(row.id));void read(`${jobPath}/${row.id}`);}}>상세</button>}</div>;})}
    <details><summary>원본 JSON 보기</summary><pre tabIndex={0}>{JSON.stringify(result,null,2).slice(0,60000)}</pre><p className="small muted">화면은 최대 60,000자이며 다운로드에는 전체 결과가 포함됩니다.</p></details>
   </section>}
   {confirm&&<Dialog title={fields.mode==='replace_all'?'컬렉션 전체 교체':'색인 문서 삭제'} busy={busy} close={()=>setConfirm(null)}><p>{environmentLabel} / {fields.collection}</p><p className="warning">지정 범위의 실제 색인이 변경됩니다. 전체 교체는 기존 문서를 제거하며 빈 배열이면 전체 삭제합니다. 결과 요약 만료는 색인 삭제가 아닙니다.</p><button disabled={disabled} onClick={()=>{const request=confirm;setConfirm(null);setSubmission(request);void run(request);}}>변경 확인 후 실행</button></Dialog>}
  </div></div>
 </section>;
}
