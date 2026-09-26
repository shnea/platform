import {useEffect,useState} from "react";
import {fileApi,type FileInfo} from "./file-api";
type Settings={title:string;description:string;showThumbnail:boolean;revision:number};
export function FilePublicShare({file,environmentId,disabled,onBusyChange}:{file:FileInfo;environmentId:string;disabled:boolean;onBusyChange:(v:boolean)=>void}){
 const [open,setOpen]=useState(false),[data,setData]=useState<Settings|null>(null),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(""),[notice,setNotice]=useState("");
 const url=new URL(`/api/v1/files/${file.fileId}/share`,location.origin).href;
 async function load(){setLoading(true);setError("");try{setData(await fileApi(environmentId,`/${file.fileId}/public-share`));}catch(e){setError(e instanceof Error?e.message:"공유 설정을 불러오지 못했습니다.");}finally{setLoading(false);}}
 useEffect(()=>{if(open&&!data)void load();},[open]);
 async function save(){setBusy(true);onBusyChange(true);setError("");setNotice("");try{setData(await fileApi(environmentId,`/${file.fileId}/public-share`,"PUT",data));setNotice("공유 미리보기를 저장했습니다. 외부 서비스의 기존 카드는 캐시 갱신 후 바뀔 수 있습니다.");}catch(e){setError(e instanceof Error?e.message:"저장하지 못했습니다.");}finally{setBusy(false);onBusyChange(false);}}
 if(file.visibility!=="PUBLIC")return null;
 return <details className="file-share-panel" onToggle={e=>setOpen(e.currentTarget.open)}><summary>공개 링크·공유 미리보기</summary>
 <p className="small muted">카카오톡 등에 보낼 링크입니다. 제목·설명과 대표 이미지가 공개되며, 비공개 전환·삭제·파일 서비스 사용 중지 시 접근을 차단합니다. 외부에 저장된 카드는 즉시 회수되지 않습니다.</p>
 <label>공개 공유 URL<input readOnly value={url} onFocus={e=>e.target.select()}/></label><div className="actions"><button className="secondary" onClick={()=>void navigator.clipboard.writeText(url).then(()=>setNotice("공개 공유 URL을 복사했습니다.")).catch(()=>setNotice("URL을 선택해 직접 복사해 주세요."))}>공유 URL 복사</button><a href={url} target="_blank" rel="noopener noreferrer">공유 페이지 열기</a></div>
 {loading&&<p role="status">공유 설정을 불러오는 중…</p>}{error&&<p className="alert" role="alert">{error}</p>}{notice&&<p className="notice" role="status">{notice}</p>}
 {error&&<button className="secondary" disabled={busy||loading} onClick={()=>void load()}>설정 다시 조회</button>}
 {data&&<form onSubmit={e=>{e.preventDefault();void save();}}><fieldset disabled={busy||disabled||loading}><legend className="sr-only">공유 카드 내용</legend>
 <label>공유 제목<input maxLength={120} value={data.title} placeholder={file.originalName} onChange={e=>setData({...data,title:e.target.value})}/><span className="hint">비워 두면 파일 이름을 사용합니다. 최대 120자.</span></label>
 <label>공유 설명<input maxLength={300} value={data.description} onChange={e=>setData({...data,description:e.target.value})}/><span className="hint">최대 300자. 비워 두면 기본 안내를 표시합니다.</span></label>
 <label className="checkbox"><input type="checkbox" checked={data.showThumbnail} onChange={e=>setData({...data,showThumbnail:e.target.checked})}/>파일 썸네일을 대표 이미지로 사용</label><p className="small muted">썸네일이 없거나 선택을 끄면 공통 파일 이미지를 표시합니다.</p>
 <button type="submit">{busy?"저장 중…":"공유 미리보기 저장"}</button></fieldset></form>}
 </details>;
}
