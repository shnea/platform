import {useEffect,useRef,useState,type FormEvent} from 'react';
import {fileApi,fileHash,chunkHash,type Upload,type FileInfo} from '../files/file-api';
import {activeJob} from './test-contract';
import {independentDefinitions,independentOptions,subtitleCues} from './independent-contract';
import {fileSamples} from './test-samples';

type Json=Record<string,unknown>;
const object=(value:unknown):Json=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Json:{};
const array=(value:unknown):Json[]=>Array.isArray(value)?value.map(object):[];
const statusNames:Record<string,string>={pending:'접수 대기',running:'실행 중',succeeded:'완료',failed:'실패',cancelled:'취소됨'};
type Submission={requestId:string;uploadRequest:string;sourceFileId:string;file:File|null;hash:string;kind:string;input?:Json;options:Json};
export function IndependentTask({menu,environmentId,available,onBusyChange,initialJobId}:{menu:string;environmentId:string;available:boolean;onBusyChange:(value:boolean)=>void;initialJobId?:string}) {
 const definition=independentDefinitions[menu];
 const [file,setFile]=useState<File|null>(null),[sourceId,setSourceId]=useState(''),[language,setLanguage]=useState(menu==='tts'?'Korean':'auto'),[itn,setItn]=useState(true),[correction,setCorrection]=useState(true),[mode,setMode]=useState('auto');
 const [seconds,setSeconds]=useState('0'),[speech,setSpeech]=useState('안녕하세요. 뇌대리 음성 생성 테스트입니다.'),[voice,setVoice]=useState(''),[instruction,setInstruction]=useState('');
 const [voiceKind,setVoiceKind]=useState('preset'),[voiceName,setVoiceName]=useState('한국어 안내'),[speaker,setSpeaker]=useState('Sohee'),[reference,setReference]=useState(''),[voices,setVoices]=useState<Json[]>([]);
 const [result,setResult]=useState<Json>({}),[error,setError]=useState(''),[busy,setBusy]=useState(false),[progress,setProgress]=useState(''),[services,setServices]=useState<Json>({}),[history,setHistory]=useState<Json[]>([]);
 const [frozen,setFrozen]=useState(false),[sourceUrl,setSourceUrl]=useState(''),[previewUrl,setPreviewUrl]=useState(''),[trackUrl,setTrackUrl]=useState(''),[page,setPage]=useState(0),[line,setLine]=useState(-1);
 const [sourceName,setSourceName]=useState(''),[polling,setPolling]=useState(false);
 const [cues,setCues]=useState<ReturnType<typeof subtitleCues>>([]);
 const submission=useRef<Submission|null>(null),locked=useRef(false),mounted=useRef(true),pollCount=useRef(0),urls=useRef<string[]>([]),controller=useRef<AbortController|null>(null),media=useRef<HTMLMediaElement|null>(null);
 const root='/noedaeri';
 const artifactList=array(result.artifacts),content=object(result.content),pages=array(content.pages),shown=menu==='pdf'?object(pages[page]):content,lines=array(shown.lines),segments=array(content.segments);
 const configured=services.configured===true;
 const rawServices=services.services;const remote=Array.isArray(rawServices)?array(rawServices):array(object(rawServices).services);
 const service=remote.find(value=>value.kind===definition.kind||value.service===definition.kind),unavailable=service?.available===false||services.reachable===false;
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;controller.current?.abort();urls.current.forEach(url=>URL.revokeObjectURL(url));onBusyChange(false);};},[onBusyChange]);
 useEffect(()=>{onBusyChange(busy);},[busy,onBusyChange]);
 useEffect(()=>{if(available)void fileApi<Json>(environmentId,root+'/services').then(value=>{if(mounted.current)setServices(value);}).catch(error=>{if(mounted.current)setError(String(error.message));});},[environmentId,available]);
 useEffect(()=>{if(available&&menu==='tts')void fileApi<Json[]>(environmentId,root+'/voices').then(value=>{if(mounted.current)setVoices(value);}).catch(error=>{if(mounted.current)setError(String(error.message));});},[environmentId,available,menu]);
 function remember(blob:Blob){if(!mounted.current)throw new Error('이미 닫힌 테스트 화면입니다.');const url=URL.createObjectURL(blob);urls.current.push(url);return url;}
 function clearPreview(){urls.current.forEach(url=>URL.revokeObjectURL(url));urls.current=[];setSourceUrl('');setPreviewUrl('');setTrackUrl('');setCues([]);setPage(0);setLine(-1);}
 async function read(id=String(result.id??'')) {
  if(!id||locked.current)return;locked.current=true;setBusy(true);setError('');
  try{const value=await fileApi<Json>(environmentId,root+'/tasks/'+id);if(mounted.current){setResult(value);if(!activeJob(value)||value.recoveryRequired===true)setPolling(false);}}
  catch(error){if(mounted.current){setError(error instanceof Error?error.message:String(error));setPolling(false);}}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 useEffect(()=>{
  if(available&&initialJobId)void read(initialJobId);
 },[available,initialJobId]);
 useEffect(()=>{
  if(!polling||busy||!activeJob(result))return;
  if(pollCount.current>=180){setPolling(false);setProgress('자동 조회를 중단했습니다. 상태 조회는 작업을 다시 실행하지 않습니다.');return;}
  const timer=setTimeout(()=>{pollCount.current++;void read();},5000);return()=>clearTimeout(timer);
 },[polling,busy,result]);
 async function run(event?:FormEvent) {
  event?.preventDefault();if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try {
   const signal=new AbortController();controller.current=signal;
   if(!submission.current) {
    clearPreview();
    const needsFile=menu!=='tts'&&(menu!=='voices'||voiceKind==='clone');
    if(needsFile&&!file&&!sourceId)throw new Error('파일을 선택하거나 이 환경의 저장된 파일 ID를 입력하세요.');
    if(menu==='voices'&&voiceKind==='clone'&&file&&file.size>64*1024*1024)throw new Error('참조 음성은 64MiB 이하여야 합니다.');
    const options=independentOptions(menu,{mode,language,correction,itn,seconds,voice,instruction});
    const input=menu==='tts'?{text:speech,language}:menu==='voices'?{name:voiceName,kind:voiceKind,...(voiceKind==='clone'?{reference_text:reference}:{speaker})}:undefined;
    submission.current={requestId:crypto.randomUUID(),uploadRequest:crypto.randomUUID(),sourceFileId:needsFile?sourceId:'',file:needsFile?file:null,hash:'',kind:definition.kind,options,...(input?{input}:{})};setFrozen(true);
   }
   const value=submission.current;
   if(value.file&&!value.sourceFileId&&menu!=='tts') {
    if(!value.hash)value.hash=await fileHash(value.file,signal.signal,number=>{if(mounted.current)setProgress(`원본 확인 ${number}%`);});
    let upload=await fileApi<Upload>(environmentId,root+'/uploads','POST',{requestId:value.uploadRequest,originalName:value.file.name,size:value.file.size,sha256:value.hash,visibility:'PRIVATE',retentionCode:'tmp'},signal.signal);
    while(upload.state==='UPLOADING'&&upload.receivedBytes<value.file.size) {
     const offset=upload.receivedBytes,chunk=value.file.slice(offset,offset+upload.maxChunkBytes),checksum=await chunkHash(chunk);
     upload=await fileApi<Upload>(environmentId,'/uploads/'+upload.uploadId,'PATCH',chunk,signal.signal,{'Upload-Offset':String(offset),'X-Chunk-SHA256':checksum});
     if(mounted.current)setProgress(`원본 저장 ${Math.round(upload.receivedBytes/value.file.size*100)}%`);
    }
    const stored=await fileApi<FileInfo>(environmentId,'/uploads/'+upload.uploadId+'/complete','POST',{},signal.signal);value.sourceFileId=stored.fileId;
   }
   const body={requestId:value.requestId,kind:value.kind,options:value.options,...(value.input?{input:value.input}:{}),...(value.sourceFileId?{sourceFileId:value.sourceFileId}:{})};
   const accepted=await fileApi<Json>(environmentId,root+'/tasks','POST',body,signal.signal);
   if(mounted.current){setResult(accepted);setProgress('접수됨. 같은 요청 확인은 같은 ID·내용을 유지합니다. 파일 저장 뒤에만 서버가 receipt를 보냅니다.');pollCount.current=0;setPolling(true);}
  }catch(error){if(mounted.current)setError((error instanceof Error?error.message:String(error))+' 응답 유실 시 새 실행 대신 같은 요청 확인을 사용하세요.');}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function action(path:string) {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try{const value=await fileApi<Json>(environmentId,root+'/tasks/'+result.id+path,'POST',{});if(mounted.current){setResult(value);pollCount.current=0;setPolling(true);}}
  catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function bytes(name:string,original=false) {
  const ticket=await fileApi<{downloadUrl:string}>(environmentId,original?`/${result.sourceFileId}/download-ticket`:`${root}/tasks/${result.id}/artifacts/${encodeURIComponent(name)}/ticket`,'POST',{});
  if(!/^\/api\/v1\/files\/downloads\/[A-Za-z0-9_-]{43}$/.test(ticket.downloadUrl))throw new Error('잘못된 다운로드 주소입니다.');
  const response=await fetch(ticket.downloadUrl,{cache:'no-store'});if(!response.ok)throw new Error('결과 파일 다운로드 권한 또는 만료를 확인하세요.');return response.blob();
 }
 async function preview() {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try {
   clearPreview();
   if(menu==='tts'||menu==='voices'||menu==='thumbnail')setPreviewUrl(remember(new Blob([await bytes(menu==='tts'?'speech.wav':menu==='voices'?'reference.wav':'thumbnail.jpg')],{type:menu==='thumbnail'?'image/jpeg':'audio/wav'})));
   else {
    const original=await bytes('',true);
    const selectedName=String(result.sourceName??submission.current?.file?.name??(menu==='ocr'?'source.png':'source.mp4'));setSourceName(selectedName);
    const extension=selectedName.split('.').pop()?.toLowerCase();const mime=menu==='ocr'?`image/${extension==='jpg'?'jpeg':extension}`:extension==='wav'?'audio/wav':extension==='mp3'?'audio/mpeg':extension==='webm'?'video/webm':'video/mp4';
    setSourceUrl(remember(new Blob([original],{type:mime})));
    if(menu==='subtitles'){const vtt=await bytes('subtitles.vtt');setTrackUrl(remember(new Blob([vtt],{type:'text/vtt'})));setCues(subtitleCues(await vtt.text()));}
   }
  }catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function download(name:string) {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try{const url=remember(await bytes(name));const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function loadHistory(){try{setHistory(await fileApi<Json[]>(environmentId,root+'/tasks'));}catch(error){setError(error instanceof Error?error.message:String(error));}}
 const choices=menu==='tts'?['Korean','English','Japanese','Chinese','German','French','Russian','Portuguese','Spanish','Italian']:menu==='pdf'||menu==='ocr'?['auto','ko','en','ja','zh-Hans','zh-Hant']:['auto','ko','en','ja','zh','yue'];
 return <div className="noedaeri-independent">
  <p>{definition.description}</p><p className="small muted">원본 전용 업로드는 영상 통합 처리·이미지 파생물을 자동 요청하지 않습니다. 원본과 결과는 실제 비공개 파일로 저장됩니다. tmp 자동 정리가 꺼져 있으면 파일 메뉴에서 삭제하세요.</p>
  <p>뇌대리 설정: {configured?'등록됨':services.configured===false?'미설정':'확인 전'} · {configured&&services.reachable===false?'서버 연결 확인 실패':service?(service.available===false?'기능 미설정':'기능 설정 확인됨'):'기능 상태 미확인'}. 설정은 실행 성공을 보장하지 않습니다.</p>
  <button className="secondary" disabled={busy||!available} onClick={()=>{void fileApi<Json>(environmentId,root+'/services').then(value=>{if(mounted.current)setServices(value);}).catch(error=>{if(mounted.current)setError(String(error.message));});}}>서비스 상태 다시 확인</button>
  {error&&<p role="alert" className="alert">{error}</p>}{progress&&<p role="status">{progress}</p>}
  <form className="noedaeri-form" onSubmit={run}><fieldset disabled={busy||frozen||!available||!configured||unavailable}>
   <details className="noedaeri-sample"><summary>{fileSamples[menu].title} · 입력 안내</summary><p>{fileSamples[menu].text}</p>{(menu==='tts'||menu==='thumbnail')&&<button type="button" className="secondary" onClick={()=>{if(menu==='tts'){setSpeech(fileSamples.tts.text);setLanguage('Korean');}else setSeconds('1');}}>샘플 입력 채우기</button>}<p className="small muted">입력 안내·채우기는 파일 업로드나 모델 실행을 하지 않습니다. 파일은 직접 선택하며 목소리 참조에는 녹음과 일치하는 대본을 입력하세요.</p></details>
   {menu==='voices'&&<><label>이름<input required maxLength={120} value={voiceName} onChange={event=>setVoiceName(event.target.value)}/></label><label>등록 종류<select value={voiceKind} onChange={event=>setVoiceKind(event.target.value)}><option value="preset">기본 목소리 프리셋</option><option value="clone">참조 음성</option></select></label>{voiceKind==='preset'?<label>기본 목소리<select value={speaker} onChange={event=>setSpeaker(event.target.value)}>{['Sohee','Vivian','Serena','Uncle_Fu','Dylan','Eric','Ryan','Aiden','Ono_Anna'].map(value=><option key={value}>{value}</option>)}</select></label>:<label>파일에서 실제 말한 대본<textarea required maxLength={1000} value={reference} onChange={event=>setReference(event.target.value)}/></label>}</>}
   {menu!=='tts'&&(menu!=='voices'||voiceKind==='clone')&&<><label>원본 파일<input type="file" accept={definition.accept} onChange={event=>{setFile(event.target.files?.[0]??null);setSourceId('');}}/></label><label>또는 저장된 파일 ID<input value={sourceId} disabled={!!file} onChange={event=>setSourceId(event.target.value)} placeholder="현재 프로젝트·환경의 READY 파일 UUID"/></label></>}
   {menu==='tts'&&<><label>합성 텍스트<textarea required maxLength={4000} value={speech} onChange={event=>setSpeech(event.target.value)}/></label><label>목소리<select value={voice} onChange={event=>{setVoice(event.target.value);if(voices.find(profile=>profile.id===event.target.value)?.kind==='clone')setInstruction('');}}><option value="">내장 기본 · Sohee</option>{voices.filter(profile=>profile.status==='ready'&&(profile.kind==='preset'||profile.sample_available===true)).map(profile=><option key={String(profile.id)} value={String(profile.id)}>{String(profile.name)} · {String(profile.kind)}</option>)}</select></label><label>말투 지침 (프리셋만)<input disabled={voices.find(profile=>profile.id===voice)?.kind==='clone'} maxLength={300} value={instruction} onChange={event=>setInstruction(event.target.value)}/></label><p className="small muted">존재하지 않는 ID의 기본 목소리 대체는 결과의 voice_source·speaker를 확인하세요. 참조 목소리에는 말투 지침을 넣지 않습니다.</p></>}
   {menu==='thumbnail'?<label>추출 시점 (초)<input required type="number" min={0} max={3600} step="0.1" value={seconds} onChange={event=>setSeconds(event.target.value)}/></label>:menu!=='voices'&&<label>{menu==='tts'?'합성 언어':'인식 언어'}<select value={language} onChange={event=>setLanguage(event.target.value)}>{choices.map(value=><option key={value}>{value}</option>)}</select></label>}
   {(menu==='subtitles'||menu==='stt')&&<label className="checkbox"><input type="checkbox" checked={itn} onChange={event=>setItn(event.target.checked)}/>숫자·표기 정규화 (ITN)</label>}
   {(menu==='pdf'||menu==='ocr')&&<label className="checkbox"><input type="checkbox" checked={correction} onChange={event=>setCorrection(event.target.checked)}/>언어 보정</label>}
   {menu==='pdf'&&<label>추출 방법<select value={mode} onChange={event=>setMode(event.target.value)}><option value="auto">자동 · 페이지별 텍스트/OCR</option><option value="text">내장 텍스트만</option><option value="ocr">전체 OCR</option></select></label>}
  </fieldset><div className="actions"><button disabled={busy||!available||!configured||unavailable}>{frozen?'같은 요청 확인':'테스트 실행'}</button>{frozen&&<button type="button" className="secondary" disabled={busy} onClick={()=>{submission.current=null;setFrozen(false);setResult({});setPolling(false);setProgress('');}}>새 테스트 입력</button>}</div></form>
  {!!result.id&&<section><h4>실행 결과</h4><p>작업 {String(result.id)} · 원격 {String(result.jobId??'접수 전')} · {statusNames[String(result.status)]??String(result.status)} · 단계 {String(result.stage??'대기')}</p>
   <div className="actions"><button className="secondary" disabled={busy} onClick={()=>void read()}>상태 조회</button>{activeJob(result)&&<button className="secondary" disabled={busy||result.cancelRequested===true} onClick={()=>{if(window.confirm('작업을 취소할까요? 실행 종료 확인 전까지 상태는 실행 중입니다.'))void action('/cancel');}}>취소 요청</button>}{result.recoveryRequired===true&&<button className="secondary" disabled={busy} onClick={()=>void action('/recover')}>같은 작업 복구 확인</button>}</div>
   {result.errorCode!=null&&<p className="warning">{String(result.errorCode)}</p>}{result.recoveryRequired===true&&<p className="warning">제한된 재시도가 끝났습니다. 복구 확인은 같은 원격 작업을 조회·수령하며 모델을 새로 실행하지 않습니다.</p>}
   <p className="small muted">원격 보관 상한 {String(result.remoteExpiresAt??'-')} · 저장 확인 {menu==='voices'&&result.status==='succeeded'&&!result.jobId?'프리셋은 별도 receipt 없음':result.receiptAt?'전송됨':'대기'} · 플랫폼 파일은 별도 보존 정책을 따릅니다.</p>
   {menu==='voices'&&result.voiceId!=null&&<p>등록 목소리 {String(result.voiceId)} · 뇌대리 프로필은 명시적으로 삭제할 때까지 유지합니다. 플랫폼 참조 미리보기의 tmp 정리와 다릅니다. 목록을 새로 조회해 ready·샘플 가용 상태를 확인하세요.</p>}
   {menu!=='voices'&&result.status==='succeeded'&&artifactList.length===0&&<p className="warning">플랫폼 결과가 삭제·정리되었거나 원본에 접근할 수 없습니다. 조회로 재생성하지 않습니다.</p>}
   {artifactList.length>0&&<><div className="actions">{artifactList.map(item=><button key={String(item.name)} className="secondary" disabled={busy} onClick={()=>void download(String(item.name))}>{String(item.name)} ({String(item.bytes)} B)</button>)}</div>{menu!=='pdf'&&<button className="secondary" disabled={busy} onClick={()=>void preview()}>미리보기 준비</button>}</>}
   {(menu==='tts'||menu==='voices')&&previewUrl&&<audio controls src={previewUrl}/>} {menu==='thumbnail'&&previewUrl&&<img className="noedaeri-image" src={previewUrl} alt="추출한 영상 썸네일"/>}
   {menu==='ocr'&&sourceUrl&&<figure className="noedaeri-ocr-source"><img src={sourceUrl} alt="OCR 원본"/>{line>=0&&<span className="noedaeri-ocr-box" style={{left:`${Number(object(lines[line]?.bounding_box).left)*100}%`,top:`${Number(object(lines[line]?.bounding_box).top)*100}%`,width:`${Number(object(lines[line]?.bounding_box).width)*100}%`,height:`${Number(object(lines[line]?.bounding_box).height)*100}%`}}/>}</figure>}
   {(menu==='stt'||menu==='subtitles')&&sourceUrl&&<>{menu==='stt'&&/\.(wav|mp3|flac|ogg|m4a|aac|aiff)$/i.test(sourceName)?<audio ref={element=>{media.current=element;}} controls src={sourceUrl}/>:<video ref={element=>{media.current=element;}} className="noedaeri-video" controls src={sourceUrl}>{trackUrl&&<track label="자동 생성 자막 · 근사 시각" src={trackUrl} kind="subtitles" default/>}</video>}<p className="small muted">{sourceName} · 원본 코덱이 브라우저에서 지원되지 않으면 재생되지 않을 수 있습니다. 구간은 전사 기준이며 SRT/VTT cue와 다를 수 있습니다.</p></>}
   {menu==='pdf'&&pages.length>0&&<label>페이지<select value={page} onChange={event=>{setPage(Number(event.target.value));setLine(-1);}}>{pages.map((value,index)=><option key={index} value={index}>{String(value.page)} · {String(value.method)}</option>)}</select></label>}
   {typeof shown.text==='string'&&<pre className="noedaeri-output">{shown.text||'인식된 텍스트 없음 (무음·문자 미검출도 성공할 수 있습니다)'}</pre>}
   {lines.length>0&&<><h4>줄별 위치 · 정규화 좌표</h4><ol>{lines.slice(0,100).map((value,index)=><li key={index}><button className="secondary" onClick={()=>setLine(index)}>{String(value.text)}</button><span className="small"> {JSON.stringify(value.bounding_box)} · confidence {String(value.confidence??'-')}</span></li>)}</ol>{lines.length>100&&<p>처음 100줄 표시. 전체는 JSON으로 다운로드하세요.</p>}</>}
   {segments.length>0&&<><h4>전사 구간</h4><ol>{segments.slice(0,100).map((value,index)=><li key={index}><button className="secondary" onClick={()=>{if(media.current)media.current.currentTime=Number(value.start);}}>{Number(value.start).toFixed(2)}–{Number(value.end).toFixed(2)}초</button> {String(value.text)}</li>)}</ol>{segments.length>100&&<p>처음 100구간 표시. 전체는 JSON으로 다운로드하세요.</p>}</>}
   {menu==='subtitles'&&trackUrl&&<><h4>자동 자막 cue · 처음 100개</h4>{cues.length===0?<p>생성된 cue 없음</p>:<ol>{cues.map((value,index)=><li key={index}><button className="secondary" onClick={()=>{if(media.current)media.current.currentTime=value.start;}}>{value.start.toFixed(2)}–{value.end.toFixed(2)}초</button> {value.text}</li>)}</ol>}</>}
   {menu==='tts'&&<p>실제 목소리 {String(object(result.manifest).speaker??'-')} · 선택 경로 {String(object(result.manifest).voice_source??'-')} · 길이 {String(object(result.manifest).duration_seconds??'-')}초</p>}
   <details><summary>원본 JSON (처음 60,000자)</summary><pre>{JSON.stringify(result,null,2).slice(0,60000)}</pre></details>
   <button className="secondary" onClick={()=>{const url=remember(new Blob([JSON.stringify(result,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download=`noedaeri-${result.id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}}>전체 작업 JSON 다운로드</button>
  </section>}
  <section><h4>독립 작업 이력</h4><button className="secondary" disabled={busy||!available} onClick={()=>void loadHistory()}>이력 조회</button><ul>{history.filter(item=>item.kind===definition.kind).map(item=><li key={String(item.id)}><button className="secondary" disabled={busy} onClick={()=>{setSourceUrl('');setPreviewUrl('');setTrackUrl('');setPage(0);setPolling(false);void read(String(item.id));}}>{String(item.createdAt)} · {String(item.status)} · {String(item.id)}</button></li>)}</ul></section>
 </div>;
}
