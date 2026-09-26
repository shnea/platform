import { useEffect, useState } from "react";
import { Dialog } from "./Dialog";
import { SectionTabs } from "./SectionTabs";
import { fileApi } from "./file-api";

export type RetentionPolicy = {code:string;displayName:string;periodValue:number|null;periodUnit:string;enabled:boolean;revision:number};
type Overview={settings:{enabled:boolean;graceDays:number;revision:number;lastCheckedAt:string|null;errorCode:string|null};policies:RetentionPolicy[];eligibleFiles:number};
type Candidate={fileId:string;originalName:string;retentionCode:string;lastUsedAt:string;dueAt:string;deleteAfter:string|null};
const period=(p:RetentionPolicy)=>p.periodUnit==="FOREVER"?"자동 삭제 없음":`${p.periodValue}${({DAY:"일",MONTH:"개월",YEAR:"년"} as Record<string,string>)[p.periodUnit]} 미사용`;
const when=(v:string|null)=>v?new Date(v).toLocaleString("ko-KR"):"아직 검사하지 않음";
const errorText=(e:unknown)=>e instanceof TypeError?"서버에 연결하지 못했습니다. 다시 조회해 주세요.":e instanceof Error?e.message:"처리하지 못했습니다. 다시 시도해 주세요.";
export function RetentionPanel({environmentId,environmentLabel,onBusyChange,onChanged}:{environmentId:string;environmentLabel:string;onBusyChange:(v:boolean)=>void;onChanged:()=>void}) {
 const [tab,setTab]=useState<"policies"|"cleanup"|"history">("policies");
 const [data,setData]=useState<Overview|null>(null),[rows,setRows]=useState<Candidate[]>([]),[history,setHistory]=useState<Record<string,unknown>[]>([]);
 const [offset,setOffset]=useState(0),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(""),[notice,setNotice]=useState("");
 const [edit,setEdit]=useState<RetentionPolicy|null>(null),[impact,setImpact]=useState<{affectedFiles:number;eligibleFiles:number}|null>(null);
 const [enabled,setEnabled]=useState(false),[grace,setGrace]=useState(7),[confirm,setConfirm]=useState(false);
 useEffect(()=>{onBusyChange(busy);return()=>onBusyChange(false);},[busy,onBusyChange]);
 async function load(page=offset,saved=false) {
  setLoading(true);setError("");if(!saved)setNotice("");
  try {const [overview,candidates,audit]=await Promise.all([fileApi<Overview>(environmentId,"/retention"),fileApi<Candidate[]>(environmentId,`/retention/candidates?offset=${page}`),fileApi<Record<string,unknown>[]>(environmentId,"/retention/history")]);
   setData(overview);setRows(candidates);setHistory(audit);setOffset(page);setEnabled(overview.settings.enabled);setGrace(overview.settings.graceDays);
  }catch(e){setNotice("");setError((saved?"변경은 저장되었지만 최신 정보를 다시 조회하지 못했습니다. ":"")+errorText(e));}finally{setLoading(false);}
 }
 useEffect(()=>{void load(0);},[environmentId]);
 async function preview(){if(!edit)return;setBusy(true);setError("");try{setImpact(await fileApi(environmentId,"/retention/preview","POST",edit));}catch(e){setError(errorText(e));}finally{setBusy(false);}}
 async function savePolicy(){if(!edit||!impact)return;setBusy(true);setError("");setNotice("");try{await fileApi(environmentId,"/retention/policies","PUT",{policy:edit,expectedEligibleFiles:impact.eligibleFiles});setEdit(null);setImpact(null);setNotice("보존 코드를 저장했습니다.");await load(0,true);onChanged();}catch(e){setImpact(null);setError(errorText(e));}finally{setBusy(false);}}
 async function saveSettings(){if(!data)return;setBusy(true);setError("");setNotice("");try{await fileApi(environmentId,"/retention/settings","PUT",{enabled,graceDays:grace,revision:data.settings.revision,expectedEligibleFiles:data.eligibleFiles});setConfirm(false);setNotice("자동 정리 설정을 저장했습니다.");await load(0,true);onChanged();}catch(e){setConfirm(false);setError(errorText(e));}finally{setBusy(false);}}
 return <section aria-label="파일 보존 정책">
  <SectionTabs id="retention-section" label="보존 관리" value={tab} onChange={setTab} disabled={busy} items={[{value:"policies",label:"보존 코드"},{value:"cleanup",label:"자동 정리"},{value:"history",label:"변경 이력"}]}/>
  <div className="section-line"><h3>{tab==="policies"?"보존 코드":tab==="cleanup"?"자동 정리":"보존 변경 이력"}</h3><button className="secondary" disabled={loading||busy} onClick={()=>void load()}>보존 정보 새로고침</button></div>
  {loading&&<p role="status">보존 정보를 불러오는 중…</p>}
  {error&&!edit&&<p className="alert" role="alert">{error}</p>}{notice&&<p className="notice" role="status">{notice}</p>}
  {error&&data&&!edit&&<p className="warning">아래 값은 이전 조회 결과입니다. 최신 정보 조회에 성공한 뒤 변경할 수 있습니다.</p>}
  <div id="retention-section-panel" role="tabpanel" aria-labelledby={`retention-section-${tab}`}>
  {data&&tab==="policies"&&<><p className="small muted">최근 콘텐츠 이용을 기준으로 보관합니다. 기간 변경은 같은 코드를 사용하는 기존 파일에도 적용됩니다.</p>
   <button disabled={busy||loading||!!error} onClick={()=>{setEdit({code:"",displayName:"",periodValue:1,periodUnit:"MONTH",enabled:true,revision:0});setImpact(null);setError("");}}>보존 코드 추가</button>
   <ul className="file-list">{data.policies.map(p=><li key={p.code}><div className="file-description"><strong>{p.displayName} · {p.code}</strong><span>{period(p)} · {p.enabled?"새 업로드에 사용 가능":"새 업로드 사용 중지"}</span></div><button className="secondary" aria-label={`${p.code} 보존 코드 수정`} disabled={busy||loading||!!error} onClick={()=>{setEdit({...p});setImpact(null);setError("");}}>수정</button></li>)}</ul>
   <p className="small muted">기본 3개 코드는 삭제·사용 중지할 수 없습니다. 사용자 코드의 사용 중지는 신규 선택만 막으며 기존 파일에는 현재 기간을 계속 적용합니다.</p></>}
  {data&&tab==="cleanup"&&<><p>현재 자동 정리: <strong>{data.settings.enabled?"켜짐":"꺼짐"}</strong> · 현재 미사용 기간 초과 <strong>{data.eligibleFiles}개</strong></p>
   <p className="small muted">마지막 검사: {when(data.settings.lastCheckedAt)}{data.settings.errorCode?" · 검사 실패, 다음 회차에 재시도":""}</p>
   <form className="retention-settings" onSubmit={e=>{e.preventDefault();setConfirm(true);}}>
    <label className="checkbox"><input type="checkbox" checked={enabled} disabled={busy||loading||!!error} onChange={e=>setEnabled(e.target.checked)}/>이 환경의 자동 정리 사용</label>
    <label>삭제 유예 기간 (일)<input type="number" min={1} max={30} required value={grace} disabled={busy||loading||!!error} onChange={e=>setGrace(Number(e.target.value))}/></label>
    <p className="small muted">미사용 기간을 넘긴 파일을 검사한 시점부터 1~30일 유예합니다. 설정·기간 변경 후에는 유예를 새로 시작합니다. 그동안 콘텐츠를 이용하거나 영구로 바꾸면 삭제 대상에서 벗어납니다.</p>
    <button disabled={busy||loading||!!error}>자동 정리 변경 확인</button>
   </form>
   <h3>미사용 기간을 넘긴 파일</h3><p className="small muted">자동 정리가 꺼져 있으면 삭제하지 않습니다. 날짜가 없는 파일은 아직 유예가 시작되지 않았습니다.</p>
   {!rows.length&&!loading&&!error&&<p className="empty">현재 삭제 대상 파일이 없습니다.</p>}
   <ul className="file-list">{rows.map(row=><li key={row.fileId}><div className="file-description"><strong>{row.originalName}</strong><span>{row.retentionCode} · 마지막 이용 {when(row.lastUsedAt)}</span><span>미사용 기간 종료 {when(row.dueAt)}</span><span>{row.deleteAfter?`자동 삭제 예정 ${when(row.deleteAfter)}`:"삭제 유예 시작 전"}</span></div></li>)}</ul>
   <div className="pagination"><button className="secondary" disabled={busy||loading||offset===0} onClick={()=>void load(offset-20)}>이전 대상</button><span>{offset/20+1}페이지</span><button className="secondary" disabled={busy||loading||rows.length<20} onClick={()=>void load(offset+20)}>다음 대상</button></div>
  </>}
  {tab==="history"&&<><p className="small muted">최근 변경 50건입니다. 자동 삭제의 파일별 기록은 파일 감사 이력에 보관합니다.</p>
   {!history.length&&!loading&&!error&&<p className="empty">아직 보존 설정 변경이 없습니다.</p>}
   <ul className="file-list">{history.map(row=><li key={String(row.id)}><div className="file-description"><strong>{row.action==="retention.policy.saved"?"보존 코드 저장":row.action==="retention.settings.saved"?"자동 정리 설정 변경":"파일 보존 코드 변경"} {String(row.policy_code??"")}</strong><span>{when(String(row.created_at))} · 영향 파일 {String(row.affected_files)}개</span><details><summary>변경 전후와 처리자</summary><p className="small muted">{String(row.actor)}</p><pre>{String(row.before_value??"최초 등록")}</pre><pre>{String(row.after_value)}</pre></details></div></li>)}</ul></>}
  </div>
  {edit&&<Dialog title={impact?"보존 변경 영향 확인":edit.revision?"보존 코드 수정":"보존 코드 추가"} busy={busy} close={()=>{setEdit(null);setImpact(null);setError("");}}>
   <p className="small muted">{environmentLabel}</p>{error&&<p className="alert" role="alert">{error}</p>}
   {impact?<><p><strong>{edit.code}</strong> · {period(edit)}</p><p>기존 파일 {impact.affectedFiles}개에 적용됩니다. 새 기준으로 미사용 기간을 넘기는 파일은 <strong>{impact.eligibleFiles}개</strong>입니다.</p><p>자동 정리가 켜져 있으면 다음 검사부터 삭제 유예를 새로 시작합니다. 이미 삭제된 파일은 복원하지 않습니다.</p><div className="actions"><button className="secondary" disabled={busy} onClick={()=>setImpact(null)}>내용 수정</button><button disabled={busy} onClick={()=>void savePolicy()}>보존 변경 적용</button></div></>
   :<form onSubmit={e=>{e.preventDefault();void preview();}}>
    <label>보존 코드<input value={edit.code} required pattern="[a-z][a-z0-9_-]{0,59}|영구" maxLength={60} disabled={busy||edit.revision>0} onChange={e=>setEdit({...edit,code:e.target.value})}/></label>
    <label>표시 이름<input value={edit.displayName} required maxLength={120} disabled={busy} onChange={e=>setEdit({...edit,displayName:e.target.value})}/></label>
    {edit.code==="영구"?<p>영구 코드는 자동 삭제에서 제외합니다.</p>:<div className="retention-period"><label>기간 값<input type="number" min={1} max={edit.periodUnit==="DAY"?36500:edit.periodUnit==="MONTH"?1200:100} required value={edit.periodValue??1} disabled={busy} onChange={e=>setEdit({...edit,periodValue:Number(e.target.value)})}/></label><label>기간 단위<select value={edit.periodUnit} disabled={busy} onChange={e=>setEdit({...edit,periodUnit:e.target.value})}><option value="DAY">일</option><option value="MONTH">개월</option><option value="YEAR">년</option></select></label></div>}
    {!['default','tmp','영구'].includes(edit.code)&&<label className="checkbox"><input type="checkbox" checked={edit.enabled} disabled={busy} onChange={e=>setEdit({...edit,enabled:e.target.checked})}/>새 업로드에 사용</label>}
    <button disabled={busy}>변경 영향 확인</button>
   </form>}
  </Dialog>}
  {confirm&&data&&<Dialog title="자동 정리 변경 확인" busy={busy} close={()=>setConfirm(false)}><p>{environmentLabel}</p><p>자동 정리 <strong>{enabled?"켜기":"끄기"}</strong> · 삭제 유예 <strong>{grace}일</strong></p><p>현재 미사용 기간을 넘긴 파일은 {data.eligibleFiles}개입니다. {enabled?"다음 검사부터 유예를 시작하며, 유예가 끝난 뒤 원본이 자동 삭제됩니다.":"자동 삭제를 멈추고 진행 중인 유예를 해제합니다."}</p><div className="actions"><button className="secondary" disabled={busy} onClick={()=>setConfirm(false)}>취소</button><button disabled={busy} onClick={()=>void saveSettings()}>자동 정리 설정 적용</button></div></Dialog>}
 </section>;
}
