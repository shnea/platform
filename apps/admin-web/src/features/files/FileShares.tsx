import {useEffect,useRef,useState} from "react";
import {fileApi,type FileInfo} from "./file-api";
import {Dialog} from "../../shared/Dialog";

type Share={shareId:string;createdAt:string;expiresAt:string;state:string;url:string};
const states:Record<string,string>={ACTIVE:'사용 가능',REVOKED:'철회됨',EXPIRED:'만료됨',INVALIDATED:'공개 범위 변경으로 종료'};
const message=(e:unknown)=>e instanceof TypeError?'연결하지 못했습니다. 목록을 다시 조회해 처리 결과를 확인해 주세요.':e instanceof Error?e.message:'공유 링크를 처리하지 못했습니다.';
export function FileShares({file,environmentId,disabled,onBusyChange}:{file:FileInfo;environmentId:string;disabled:boolean;onBusyChange:(value:boolean)=>void}) {
 const [opened,setOpened]=useState(false),[data,setData]=useState<Share[]|null>(null),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false);
 const [password,setPassword]=useState(''),[days,setDays]=useState('7'),[error,setError]=useState(''),[notice,setNotice]=useState(''),[confirm,setConfirm]=useState<Share|null>(null);
 const results=useRef<HTMLDivElement>(null);
 const focusAfterRevoke=useRef(false);
 const base=`/${file.fileId}/shares`;
 useEffect(()=>{onBusyChange(busy);return()=>onBusyChange(false);},[busy,onBusyChange]);
 useEffect(()=>{if(!confirm&&!busy&&focusAfterRevoke.current){focusAfterRevoke.current=false;results.current?.focus({preventScroll:true});}},[confirm,busy]);
 async function load(){setLoading(true);setError('');try{setData(await fileApi(environmentId,base));}catch(e){setError(message(e));}finally{setLoading(false);}}
 useEffect(()=>{if(opened)void load();},[opened,file.fileId,environmentId]);
 async function create(){setBusy(true);setError('');setNotice('');try{const added=await fileApi<Share>(environmentId,base,'POST',{password,expiresInDays:Number(days)});setPassword('');setData(current=>[added,...(current??[])].slice(0,50));setNotice('공유 링크를 만들었습니다. 비밀번호는 조회할 수 없으니 받는 사람에게 별도로 전달해 주세요.');results.current?.focus();}catch(e){setError(message(e));}finally{setBusy(false);}}
 async function revoke(){if(!confirm)return;setBusy(true);setError('');try{await fileApi(environmentId,`${base}/${confirm.shareId}`,'DELETE');setData(current=>current?.map(s=>s.shareId===confirm.shareId?{...s,state:'REVOKED'}:s)??null);focusAfterRevoke.current=true;setConfirm(null);setNotice('공유 링크를 철회했습니다. 기존에 내려받은 파일은 회수되지 않습니다.');}catch(e){setError(message(e));}finally{setBusy(false);}}
 async function copy(url:string){try{await navigator.clipboard.writeText(new URL(url,location.origin).href);setNotice('공유 링크를 복사했습니다. 비밀번호는 별도로 전달해 주세요.');}catch{setNotice('자동 복사를 사용할 수 없습니다. URL을 선택해 복사해 주세요.');}}
 const locked=disabled||busy;
 return <details className="file-duplicates file-shares" onToggle={e=>setOpened(e.currentTarget.open)}>
  <summary>비밀번호 공유 링크</summary>
  <p className="small muted">링크와 비밀번호를 받은 사람은 파일 보기·다운로드를 할 수 있습니다. 공개 범위 변경·파일 삭제·프로젝트 중지 시 접근이 차단됩니다.</p>
  {file.visibility==='PUBLIC'?<p className="warning">공개 파일은 원본 URL로 누구나 볼 수 있습니다. 비밀번호로 보호하려면 목록에서 먼저 비공개로 변경해 주세요.</p>:<form className="file-share-form" onSubmit={e=>{e.preventDefault();void create();}}>
   <label>새 공유 비밀번호<input type="password" autoComplete="new-password" minLength={8} maxLength={64} required value={password} disabled={locked} aria-describedby="share-password-help" onChange={e=>setPassword(e.target.value)}/></label>
   <label>공유 기간<select value={days} disabled={locked} onChange={e=>setDays(e.target.value)}><option value="1">1일</option><option value="7">7일</option><option value="30">30일</option></select></label>
   <button disabled={locked||loading||!data}>{busy?'처리 중…':'공유 링크 만들기'}</button>
   <p id="share-password-help" className="small muted">8~64자 · 비밀번호는 저장 후 다시 확인할 수 없습니다. 링크당 15분에 최대 10회 비밀번호를 확인할 수 있으며, 동시 사용 링크는 최대 10개입니다.</p>
  </form>}
  {!confirm&&error&&<p role="alert" className="alert">{error}</p>}{notice&&<p role="status" className="notice">{notice}</p>}
  <div ref={results} tabIndex={-1} role="group" aria-label="공유 링크 목록" aria-busy={loading}>
   {loading?<p role="status">공유 링크를 조회하는 중…</p>:data&&data.length===0?<p>아직 만든 공유 링크가 없습니다.</p>:data&&<><p className="small muted">사용 가능 링크 우선 · 최근 이력 포함 {data.length}개 · 최대 50개</p><ul className="file-url-list">{data.map(share=>{
    const state=share.state==='ACTIVE'&&Date.parse(share.expiresAt)<=Date.now()?'EXPIRED':share.state;
    return <li key={share.shareId}><label><span>{states[state]}</span><span className="small muted">생성 {new Date(share.createdAt).toLocaleString('ko-KR')} · 만료 {new Date(share.expiresAt).toLocaleString('ko-KR')}</span><input aria-label="공유 URL" readOnly value={new URL(share.url,location.origin).href} onFocus={e=>e.target.select()}/></label>{state==='ACTIVE'&&<div className="actions"><button className="secondary" disabled={locked} onClick={()=>void copy(share.url)}>공유 링크 복사</button><a className="file-url-open" href={share.url} target="_blank" rel="noopener noreferrer">공유 화면 열기</a><button className="secondary danger" disabled={locked} onClick={()=>{setError('');setConfirm(share);}}>철회</button></div>}</li>;
   })}</ul></>}
  </div>
  <button className="secondary" disabled={locked||loading} onClick={()=>void load()}>공유 목록 새로고침</button>
  {confirm&&<Dialog title="공유 링크 철회" busy={busy} close={()=>setConfirm(null)}><p>이 링크와 비밀번호로 발급한 파일·영상 주소의 접근이 차단됩니다. 이미 내려받은 내용은 회수되지 않습니다.</p><p className="small">생성 {new Date(confirm.createdAt).toLocaleString('ko-KR')}</p>{error&&<p role="alert" className="alert">{error}</p>}<button className="secondary danger" disabled={busy} onClick={()=>void revoke()}>공유 링크 철회</button></Dialog>}
 </details>;
}
