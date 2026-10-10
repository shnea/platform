import {Icon} from '../../shared/Icon';
import { useEffect, useRef, useState } from "react";
import { fileApi, fileSize, type FileInfo } from "./file-api";
import { SectionTabs } from "../../shared/SectionTabs";
import { Dialog } from "../../shared/Dialog";
import type { RetentionPolicy } from "./RetentionPanel";
import {VideoPlayer,videoState,videoReason,type FileViewsData} from "../../shared/media/VideoPlayer";
import {ImageViewer} from '../../shared/media/ImageViewer';
import {FileDuplicates} from "./FileDuplicates";
import {FilePublicShare} from "./FilePublicShare";
import {FileShares} from "./FileShares";

type Views=FileViewsData;
const states:Record<string,string>={QUEUED:"미리보기 생성 대기",PROCESSING:"미리보기 생성 중",READY:"미리보기 준비 완료",UNSUPPORTED:"미리보기 미지원",FAILED:"미리보기 생성 실패"};
const kinds:Record<string,string>={IMAGE:"이미지",VIDEO:"영상",AUDIO:"오디오",PDF:"PDF 문서",TEXT:"텍스트",MARKDOWN:"Markdown",OTHER:"일반 파일"};
const errorMessage=(e:unknown)=>e instanceof TypeError?"연결하지 못했습니다. 다시 조회해 주세요.":e instanceof Error?e.message:"파일 정보를 조회하지 못했습니다.";
const absolute=(url:string)=>new URL(url,location.origin).href;
export function FileDetails({file,environmentId,policies,onClose,onOpen,onBusyChange,onChanged}:{file:FileInfo;environmentId:string;policies:RetentionPolicy[];onClose:()=>void;onOpen:(file:FileInfo)=>void;onBusyChange:(v:boolean)=>void;onChanged:()=>void}) {
 const [data,setData]=useState<Views|null>(null),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(""),[notice,setNotice]=useState("");
 const [tab,setTab]=useState("preview"),[expired,setExpired]=useState(false),[mediaError,setMediaError]=useState(false);
 const [code,setCode]=useState(file.retentionCode),[current,setCurrent]=useState(file.retentionCode),[confirm,setConfirm]=useState(false);
 const heading=useRef<HTMLHeadingElement>(null);
 useEffect(()=>{heading.current?.focus();},[]);
 useEffect(()=>{onBusyChange(busy);return()=>onBusyChange(false);},[busy,onBusyChange]);
 async function load(){setLoading(true);setError("");setMediaError(false);try{setData(await fileApi(environmentId,`/${file.fileId}/views`,"POST"));setExpired(false);}catch(e){setError(errorMessage(e));}finally{setLoading(false);}}
 useEffect(()=>{void load();},[file.fileId]);
 useEffect(()=>{if(!data||!(['QUEUED','PROCESSING'].includes(data.state)||data.video&&['QUEUED','PROCESSING'].includes(data.video.state))||error)return;const timer=setTimeout(()=>void load(),5000);return()=>clearTimeout(timer);},[data,error]);
 useEffect(()=>{if(!data?.expiresAt)return;const timer=setTimeout(()=>setExpired(true),Math.max(0,new Date(data.expiresAt).getTime()-Date.now()));return()=>clearTimeout(timer);},[data]);
 async function copy(url:string){try{await navigator.clipboard.writeText(absolute(url));setNotice("URL을 복사했습니다.");}catch{setNotice("자동 복사를 사용할 수 없습니다. URL을 선택해 복사해 주세요.");}}
 async function save(){setBusy(true);setError("");try{await fileApi(environmentId,`/${file.fileId}/retention`,"PUT",{retentionCode:code,expectedRetentionCode:current});setCurrent(code);setConfirm(false);setNotice("파일의 보존 코드를 변경했습니다.");onChanged();}catch(e){setError(errorMessage(e));}finally{setBusy(false);}}
 async function retry(){setBusy(true);setError("");try{await fileApi(environmentId,`/${file.fileId}/views/retry`,"POST");await load();}catch(e){setError(errorMessage(e));}finally{setBusy(false);}}
 async function retryVideo(){setBusy(true);setError("");try{await fileApi(environmentId,`/${file.fileId}/video/retry`,"POST");await load();}catch(e){setError(errorMessage(e));}finally{setBusy(false);}}
 const source=data&&(tab==='thumbnail'?data.thumbnailUrl:tab==='original'?data.originalUrl:data.previewUrl);
 return <section className="file-detail" aria-label="파일 상세·보기">
  <div className="section-line"><button className="secondary" disabled={busy} onClick={onClose}><Icon name="arrow-left"/>파일 목록으로</button><button className="secondary" disabled={busy||loading} onClick={()=>void load()}><Icon name="refresh-cw"/>{expired?"보기 URL 재발급":"보기 정보 새로고침"}</button></div>
  <h3 className="file-detail-name" tabIndex={-1} ref={heading}>{file.originalName}</h3><p className="small muted">{fileSize(file.size)} · {file.visibility==='PUBLIC'?'공개':'비공개'} · {data?kinds[data.kind]:"형식 확인 중"}</p>
  <FileDuplicates fileId={file.fileId} environmentId={environmentId} disabled={busy} onOpen={onOpen}/>
  <FilePublicShare file={file} environmentId={environmentId} disabled={busy} onBusyChange={setBusy}/>
  <FileShares file={file} environmentId={environmentId} disabled={busy} onBusyChange={setBusy}/>
  {loading&&<p role="status">보기 정보를 불러오는 중…</p>}{error&&<p className="alert" role="alert">{error}</p>}{notice&&<p className="notice" role="status">{notice}</p>}
  {expired&&<p className="warning" role="status">임시 보기 URL이 만료되었습니다. ‘보기 URL 재발급’을 눌러 다시 확인해 주세요.</p>}
  {data&&<><p>{data.video?'원본 미리보기: ':''}{states[data.state]}{data.errorCode==='FILE_PREVIEW_INPUT_LIMIT'?' · 원본 미리보기 처리 한도를 초과했습니다.':data.errorCode==='FILE_PREVIEW_CODEC_UNSUPPORTED'?' · 원본 직접 재생을 지원하지 않는 코덱입니다.':''}</p>
   {!data.video&&data.errorCode?.startsWith('FILE_MEDIA_')&&<p className="warning">{videoReason(data.errorCode)}</p>}
   {data.state==='FAILED'&&<button className="secondary" disabled={busy||loading} onClick={()=>void retry()} aria-label="미리보기 생성 다시 시도" title="미리보기 생성 다시 시도" data-tooltip="미리보기 생성 다시 시도" data-icon-only="true"><Icon name="refresh-cw"/></button>}
   {data.video&&<div><p role="status">{videoState(data.video)}</p>{['FAILED','UNSUPPORTED'].includes(data.video.state)&&<p className="warning">{videoReason(data.video.errorCode)}</p>}{data.video.state==='FAILED'&&<button className="secondary" disabled={busy||loading} onClick={()=>void retryVideo()} aria-label="영상 변환 다시 시도" title="영상 변환 다시 시도" data-tooltip="영상 변환 다시 시도" data-icon-only="true"><Icon name="refresh-cw"/></button>}</div>}
   <SectionTabs id="file-view" label="파일 보기 방식" value={tab} disabled={busy} onChange={value=>{setTab(value);setMediaError(false);}} items={[{value:'preview',label:data.kind==='VIDEO'?'영상 보기':data.kind==='AUDIO'?'오디오 듣기':'미리보기'},{value:'thumbnail',label:'썸네일 보기'},{value:'original',label:'원본 보기'}]}/>
   <div id="file-view-panel" role="tabpanel" aria-labelledby={`file-view-${tab}`} className="file-preview">
    {!error&&data.kind==='VIDEO'&&tab==='preview'?<VideoPlayer data={data}/>:expired||error?<p className="muted">보기 정보를 새로 조회해 주세요.</p>:!source||data.state!=='READY'&&tab!=='thumbnail'&&!(data.kind==='IMAGE'&&data.previewUrl)?<p className="muted">{data.state==='QUEUED'||data.state==='PROCESSING'?'원본은 저장되었습니다. 미리보기가 준비되면 여기에 표시됩니다.':data.state==='FAILED'?'미리보기를 만들지 못했습니다. 다시 시도하거나 원본을 다운로드해 확인해 주세요.':tab==='thumbnail'?'이 파일에는 썸네일이 없습니다.':'이 형식은 화면 미리보기를 지원하지 않습니다. 원본을 다운로드해 확인해 주세요.'}</p>:
     tab==='thumbnail'||data.kind==='IMAGE'?<ImageViewer src={source} previewUrl={tab==='original'?source:data.kind==='IMAGE'?data.previewUrl:source} originalUrl={data.kind==='IMAGE'?data.originalUrl:source} downloadUrl={data.kind==='IMAGE'?data.downloadUrl:undefined} name={`${file.originalName} ${tab==='thumbnail'?'썸네일':'이미지'}`} onError={()=>setMediaError(true)}/>:
     data.kind==='VIDEO'?<video key={source} controls playsInline preload="metadata" poster={data.thumbnailUrl??undefined} src={source} onError={()=>setMediaError(true)}/>:
     data.kind==='AUDIO'?<audio key={source} controls preload="metadata" src={source} onError={()=>setMediaError(true)}/>:
     <iframe key={source} src={source} title={`${file.originalName} ${tab==='original'?'원본':'미리보기'}`} referrerPolicy="no-referrer"/>}
   </div>
   {(mediaError||data.kind==='VIDEO'||data.kind==='AUDIO'||data.kind==='PDF')&&<p className={mediaError?'warning':'small muted'}>{mediaError?'파일을 표시하지 못했습니다. URL을 새로 발급하거나 원본을 내려받아 확인해 주세요.':'브라우저·코덱·문서 암호화 여부에 따라 표시되지 않을 수 있습니다. 원본 다운로드를 함께 제공합니다.'}</p>}
   <h4>파일 URL</h4><p className="small muted">{data.expiresAt?`이 URL은 파일 접근 권한을 포함합니다. ${new Date(data.expiresAt).toLocaleTimeString('ko-KR')}까지 사용할 수 있으며 공개 범위 변경·삭제 시 차단됩니다. 다른 사람에게 전달할 때 주의해 주세요.`:'공개 파일의 고정 URL입니다. 비공개 전환·삭제 또는 프로젝트 중지 후에는 접근할 수 없습니다.'}</p>
   {data.streamUrl&&<p className="small muted">HLS URL은 플레이어 연동용 재생 목록(.m3u8)이며 웹페이지가 아닙니다. 브라우저에서 영상을 보려면 ‘영상 재생 화면’을 여세요.{data.streamExpiresAt?` HLS URL은 ${new Date(data.streamExpiresAt).toLocaleTimeString('ko-KR')}까지 최대 2시간 사용할 수 있으며 뷰어 진입 링크와 만료 시간이 다릅니다.`:' 공개 범위 변경·삭제 시 재생이 중단됩니다.'}</p>}
   {data.video?.state==='READY'&&!data.video.subtitles&&<p className="small muted">이 영상에는 자동 자막 결과가 없습니다. 저장된 영상에는 선택 변경이 적용되지 않습니다. 자막이 필요하면 새 업로드의 파일별 자막 선택을 확인하세요.</p>}
   <ul className="file-url-list">{([['공개 공유',data.shareUrl], [data.kind==='VIDEO'?'영상 재생 화면':'기본 뷰어',data.viewerUrl],['원본',data.originalUrl],['미리보기',data.previewUrl],['썸네일',data.thumbnailUrl],['다운로드',data.downloadUrl],...(data.streamUrl?[['HLS 재생 목록',data.streamUrl]]:[])] as [string,string|null][]).map(([label,url])=>{const hls=label==='HLS 재생 목록';const unavailable=!!error||(hls?!!data.streamExpiresAt&&Date.parse(data.streamExpiresAt)<=Date.now():expired);return <li key={label}><label>{label} URL{url?<input readOnly value={absolute(url)} onFocus={e=>e.target.select()}/>:<span className="muted">현재 제공하지 않음</span>}</label>{url&&<div className="actions"><button className="secondary" disabled={unavailable} onClick={()=>void copy(url)} aria-label={`${label} URL 복사`} title="복사" data-tooltip="복사" data-icon-only="true"><Icon name="copy"/></button>{!unavailable&&!hls&&<a href={url} target="_blank" rel="noopener noreferrer" className="file-url-open" aria-label={`${label} 새 창에서 열기`} title={`${label} 새 창에서 열기`} data-tooltip={`${label} 새 창에서 열기`} data-icon-only="true"><Icon name="external-link"/></a>}</div>}</li>;})}</ul>
  </>}
  <h4>보존·파일 정보</h4><p className="small muted">업로드 {new Date(file.createdAt).toLocaleString('ko-KR')} · 상세 진입 전 마지막 이용 {new Date(file.lastUsedAt).toLocaleString('ko-KR')}</p>
  <form className="file-retention-change" onSubmit={e=>{e.preventDefault();setConfirm(true);}}><label>파일 보존 코드<select value={code} disabled={busy} onChange={e=>setCode(e.target.value)}>{policies.filter(p=>p.enabled||p.code===current).map(p=><option key={p.code} value={p.code} disabled={!p.enabled}>{p.displayName} ({p.code}){!p.enabled?' · 사용 중지':''}</option>)}</select></label><button className="secondary" disabled={busy||code===current}><Icon name="settings"/>보존 코드 변경</button></form>
  <details><summary>파일 식별자와 무결성 정보</summary><p className="small">파일 ID: {file.fileId}</p><p className="file-hash small">SHA-256: {file.sha256}</p></details>
  {confirm&&<Dialog title="파일 보존 코드 변경" busy={busy} close={()=>setConfirm(false)}><p className="file-confirm-name">{file.originalName}</p><p>{current} → {code}</p><p>선택한 코드의 현재 기간이 적용됩니다. 자동 정리가 켜져 있고 미사용 기간을 넘기면 다음 검사부터 삭제 유예를 시작합니다.</p>{error&&<p role="alert" className="alert">{error}</p>}<button disabled={busy} onClick={()=>void save()}><Icon name="settings"/>보존 코드 변경 적용</button></Dialog>}
 </section>;
}
