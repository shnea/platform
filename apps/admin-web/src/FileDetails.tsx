import { useEffect, useRef, useState } from "react";
import { fileApi, fileSize, type FileInfo } from "./file-api";
import { SectionTabs } from "./SectionTabs";
import { Dialog } from "./Dialog";
import type { RetentionPolicy } from "./RetentionPanel";

type Views={state:string;kind:string;mediaType:string;errorCode:string|null;originalUrl:string;previewUrl:string|null;thumbnailUrl:string|null;viewerUrl:string;downloadUrl:string;expiresAt:string|null};
const states:Record<string,string>={QUEUED:"미리보기 생성 대기",PROCESSING:"미리보기 생성 중",READY:"미리보기 준비 완료",UNSUPPORTED:"미리보기 미지원",FAILED:"미리보기 생성 실패"};
const kinds:Record<string,string>={IMAGE:"이미지",VIDEO:"영상",AUDIO:"오디오",PDF:"PDF 문서",TEXT:"텍스트",MARKDOWN:"Markdown",OTHER:"일반 파일"};
const errorMessage=(e:unknown)=>e instanceof TypeError?"연결하지 못했습니다. 다시 조회해 주세요.":e instanceof Error?e.message:"파일 정보를 조회하지 못했습니다.";
const absolute=(url:string)=>new URL(url,location.origin).href;
export function FileDetails({file,environmentId,policies,onClose,onBusyChange,onChanged}:{file:FileInfo;environmentId:string;policies:RetentionPolicy[];onClose:()=>void;onBusyChange:(v:boolean)=>void;onChanged:()=>void}) {
 const [data,setData]=useState<Views|null>(null),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(""),[notice,setNotice]=useState("");
 const [tab,setTab]=useState("preview"),[expired,setExpired]=useState(false),[mediaError,setMediaError]=useState(false);
 const [code,setCode]=useState(file.retentionCode),[current,setCurrent]=useState(file.retentionCode),[confirm,setConfirm]=useState(false);
 const heading=useRef<HTMLHeadingElement>(null);
 useEffect(()=>{heading.current?.focus();},[]);
 useEffect(()=>{onBusyChange(busy);return()=>onBusyChange(false);},[busy,onBusyChange]);
 async function load(){setLoading(true);setError("");setMediaError(false);try{setData(await fileApi(environmentId,`/${file.fileId}/views`,"POST"));setExpired(false);}catch(e){setError(errorMessage(e));}finally{setLoading(false);}}
 useEffect(()=>{void load();},[file.fileId]);
 useEffect(()=>{if(!data||!['QUEUED','PROCESSING'].includes(data.state)||error)return;const timer=setTimeout(()=>void load(),5000);return()=>clearTimeout(timer);},[data,error]);
 useEffect(()=>{if(!data?.expiresAt)return;const timer=setTimeout(()=>setExpired(true),Math.max(0,new Date(data.expiresAt).getTime()-Date.now()));return()=>clearTimeout(timer);},[data]);
 async function copy(url:string){try{await navigator.clipboard.writeText(absolute(url));setNotice("URL을 복사했습니다.");}catch{setNotice("자동 복사를 사용할 수 없습니다. URL을 선택해 복사해 주세요.");}}
 async function save(){setBusy(true);setError("");try{await fileApi(environmentId,`/${file.fileId}/retention`,"PUT",{retentionCode:code,expectedRetentionCode:current});setCurrent(code);setConfirm(false);setNotice("파일의 보존 코드를 변경했습니다.");onChanged();}catch(e){setError(errorMessage(e));}finally{setBusy(false);}}
 async function retry(){setBusy(true);setError("");try{await fileApi(environmentId,`/${file.fileId}/views/retry`,"POST");await load();}catch(e){setError(errorMessage(e));}finally{setBusy(false);}}
 const source=data&&(tab==='thumbnail'?data.thumbnailUrl:tab==='original'?data.originalUrl:data.previewUrl);
 return <section className="file-detail" aria-label="파일 상세·보기">
  <div className="section-line"><button className="secondary" disabled={busy} onClick={onClose}>파일 목록으로</button><button className="secondary" disabled={busy||loading} onClick={()=>void load()}>{expired?"보기 URL 재발급":"보기 정보 새로고침"}</button></div>
  <h3 className="file-detail-name" tabIndex={-1} ref={heading}>{file.originalName}</h3><p className="small muted">{fileSize(file.size)} · {file.visibility==='PUBLIC'?'공개':'비공개'} · {data?kinds[data.kind]:"형식 확인 중"}</p>
  {loading&&<p role="status">보기 정보를 불러오는 중…</p>}{error&&<p className="alert" role="alert">{error}</p>}{notice&&<p className="notice" role="status">{notice}</p>}
  {expired&&<p className="warning" role="status">임시 보기 URL이 만료되었습니다. ‘보기 URL 재발급’을 눌러 다시 확인해 주세요.</p>}
  {data&&<><p>{states[data.state]}{data.errorCode==='FILE_PREVIEW_INPUT_LIMIT'?' · 미리보기 처리 한도를 초과했습니다.':data.errorCode==='FILE_PREVIEW_CODEC_UNSUPPORTED'?' · 지원하지 않는 영상 코덱입니다.':''}</p>
   {data.state==='FAILED'&&<button className="secondary" disabled={busy||loading} onClick={()=>void retry()}>미리보기 생성 다시 시도</button>}
   <SectionTabs id="file-view" label="파일 보기 방식" value={tab} disabled={busy} onChange={value=>{setTab(value);setMediaError(false);}} items={[{value:'preview',label:data.kind==='VIDEO'?'영상 보기':data.kind==='AUDIO'?'오디오 듣기':'미리보기'},{value:'thumbnail',label:'썸네일 보기'},{value:'original',label:'원본 보기'}]}/>
   <div id="file-view-panel" role="tabpanel" aria-labelledby={`file-view-${tab}`} className="file-preview">
    {expired||error?<p className="muted">보기 정보를 새로 조회해 주세요.</p>:!source||data.state!=='READY'?<p className="muted">{data.state==='QUEUED'||data.state==='PROCESSING'?'원본은 저장되었습니다. 미리보기가 준비되면 여기에 표시됩니다.':data.state==='FAILED'?'미리보기를 만들지 못했습니다. 다시 시도하거나 원본을 다운로드해 확인해 주세요.':tab==='thumbnail'?'이 파일에는 썸네일이 없습니다.':'이 형식은 화면 미리보기를 지원하지 않습니다. 원본을 다운로드해 확인해 주세요.'}</p>:
     tab==='thumbnail'||data.kind==='IMAGE'?<img key={source} src={source} alt={`${file.originalName} ${tab==='thumbnail'?'썸네일':'이미지'}`} onError={()=>setMediaError(true)}/>:
     data.kind==='VIDEO'?<video key={source} controls playsInline preload="metadata" poster={data.thumbnailUrl??undefined} src={source} onError={()=>setMediaError(true)}/>:
     data.kind==='AUDIO'?<audio key={source} controls preload="metadata" src={source} onError={()=>setMediaError(true)}/>:
     <iframe key={source} src={source} title={`${file.originalName} ${tab==='original'?'원본':'미리보기'}`} referrerPolicy="no-referrer"/>}
   </div>
   {(mediaError||data.kind==='VIDEO'||data.kind==='AUDIO'||data.kind==='PDF')&&<p className={mediaError?'warning':'small muted'}>{mediaError?'파일을 표시하지 못했습니다. URL을 새로 발급하거나 원본을 내려받아 확인해 주세요.':'브라우저·코덱·문서 암호화 여부에 따라 표시되지 않을 수 있습니다. 원본 다운로드를 함께 제공합니다.'}</p>}
   <h4>파일 URL</h4><p className="small muted">{data.expiresAt?`이 URL은 파일 접근 권한을 포함합니다. ${new Date(data.expiresAt).toLocaleTimeString('ko-KR')}까지 사용할 수 있으며 공개 범위 변경·삭제 시 차단됩니다. 다른 사람에게 전달할 때 주의해 주세요.`:'공개 파일의 고정 URL입니다. 비공개 전환·삭제 또는 프로젝트 중지 후에는 접근할 수 없습니다.'}</p>
   <ul className="file-url-list">{([['기본 뷰어',data.viewerUrl],['원본',data.originalUrl],['미리보기',data.previewUrl],['썸네일',data.thumbnailUrl],['다운로드',data.downloadUrl]] as [string,string|null][]).map(([label,url])=><li key={label}><label>{label} URL{url?<input readOnly value={absolute(url)} onFocus={e=>e.target.select()}/>:<span className="muted">현재 제공하지 않음</span>}</label>{url&&<div className="actions"><button className="secondary" disabled={expired||!!error} onClick={()=>void copy(url)} aria-label={`${label} URL 복사`}>복사</button>{!expired&&!error&&<a href={url} target="_blank" rel="noopener noreferrer" className="file-url-open" aria-label={`${label} 새 창에서 열기`}>열기</a>}</div>}</li>)}</ul>
  </>}
  <h4>보존·파일 정보</h4><p className="small muted">업로드 {new Date(file.createdAt).toLocaleString('ko-KR')} · 상세 진입 전 마지막 이용 {new Date(file.lastUsedAt).toLocaleString('ko-KR')}</p>
  <form className="file-retention-change" onSubmit={e=>{e.preventDefault();setConfirm(true);}}><label>파일 보존 코드<select value={code} disabled={busy} onChange={e=>setCode(e.target.value)}>{policies.filter(p=>p.enabled||p.code===current).map(p=><option key={p.code} value={p.code} disabled={!p.enabled}>{p.displayName} ({p.code}){!p.enabled?' · 사용 중지':''}</option>)}</select></label><button className="secondary" disabled={busy||code===current}>보존 코드 변경</button></form>
  <details><summary>파일 식별자와 무결성 정보</summary><p className="small">파일 ID: {file.fileId}</p><p className="file-hash small">SHA-256: {file.sha256}</p></details>
  {confirm&&<Dialog title="파일 보존 코드 변경" busy={busy} close={()=>setConfirm(false)}><p className="file-confirm-name">{file.originalName}</p><p>{current} → {code}</p><p>선택한 코드의 현재 기간이 적용됩니다. 자동 정리가 켜져 있고 미사용 기간을 넘기면 다음 검사부터 삭제 유예를 시작합니다.</p>{error&&<p role="alert" className="alert">{error}</p>}<button disabled={busy} onClick={()=>void save()}>보존 코드 변경 적용</button></Dialog>}
 </section>;
}
