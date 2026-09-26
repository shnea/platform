import {useEffect,useRef,useState,type ComponentProps,type FormEvent} from 'react';
import {JobPanel} from './JobPanel';
import {SectionTabs} from '../../shared/SectionTabs';
import {Dialog} from '../../shared/Dialog';
import {Icon} from '../../shared/Icon';
import {api} from '../../shared/auth';

const tabs=[{value:'external',label:'외부 프로젝트 작업'},{value:'internal',label:'환경 반영 작업'}] as const;
const states:Record<string,string>={QUEUED:'대기',RUNNING:'실행 중',RETRY_WAIT:'재시도 대기',SUCCEEDED:'완료',FAILED:'최종 실패',CANCELLED:'취소됨',ABANDONED:'실행 중단'};
type Job={id:string;queue:string;state:string;payload:unknown;result:unknown;attempts:number;maxAttempts:number;progress:number;workerId:string|null;leaseUntil:string|null;nextRunAt:string;errorCode:string|null;requestId:string|null;createdAt:string;completedAt:string|null};
type Detail={job:Job;attempts:{attempt:number;workerId:string;state:string;errorCode:string|null;startedAt:string;endedAt:string|null}[]};
const date=(value:string|null)=>value?new Date(value).toLocaleString('ko-KR'):'—';
const message=(error:unknown)=>error instanceof Error?error.message:'조회에 실패했습니다. 다시 시도해 주세요.';
export function JobWorkspace(props:ComponentProps<typeof JobPanel>){
 const [tab,setTab]=useState<'external'|'internal'>(props.initialState?'internal':'external');
 return <><SectionTabs id="jobs-section" label="작업 종류" items={tabs} value={tab} onChange={setTab} disabled={props.disabled}/>
 <div id="jobs-section-panel" role="tabpanel" aria-labelledby={`jobs-section-${tab}`}>
 {tab==='internal'?<JobPanel {...props}/>:<ExternalJobs environmentId={props.environmentId} available={props.ready} onBusyChange={props.onBusyChange}/>}</div></>;
}
function ExternalJobs({environmentId,available,onBusyChange}:{environmentId:string;available:boolean;onBusyChange:(value:boolean)=>void}){
 const [query,setQuery]=useState({queue:'',state:'',offset:0,reload:0}),[rows,setRows]=useState<{items:Job[];hasMore:boolean}|null>(null);
 const [id,setId]=useState<string|null>(null),[detail,setDetail]=useState<Detail|null>(null),[loading,setLoading]=useState(false),[error,setError]=useState('');
 const [action,setAction]=useState<'cancel'|'retry'|null>(null),[busy,setBusy]=useState(false),[actionError,setActionError]=useState(''),[notice,setNotice]=useState('');
 const [auto,setAuto]=useState(true),[checked,setChecked]=useState<string|null>(null);
 const heading=useRef<HTMLHeadingElement>(null),locked=useRef(false);
 const prefix=`/environments/${environmentId}/external-jobs`;
 useEffect(()=>{
  if(action)return;let current=true;let timer:ReturnType<typeof setTimeout>;setError('');setLoading(true);
  async function load(){
   if(document.hidden){timer=setTimeout(load,5000);return;}
   try{
    if(id){const value=await api<Detail>(`${prefix}/${id}`);if(current)setDetail(value);}
    else{const params=new URLSearchParams({limit:'20',offset:String(query.offset)});if(query.queue)params.set('queue',query.queue);if(query.state)params.set('state',query.state);
     const value=await api<{items:Job[];hasMore:boolean}>(`${prefix}?${params}`);if(current)setRows(value);}
    if(current){setError('');setChecked(new Date().toISOString());}
   }catch(e){if(current)setError(message(e));}finally{if(current){setLoading(false);if(auto)timer=setTimeout(load,5000);}}
  }void load();return()=>{current=false;clearTimeout(timer);};
 },[prefix,id,query,auto,action]);
 function filter(event:FormEvent<HTMLFormElement>){event.preventDefault();const data=new FormData(event.currentTarget);setRows(null);setQuery({queue:String(data.get('queue')).trim(),state:String(data.get('state')),offset:0,reload:query.reload+1});}
 function select(next:string|null){setId(next);setDetail(null);setError('');setNotice('');requestAnimationFrame(()=>heading.current?.focus());}
 async function execute(){if(!action||!id||locked.current)return;locked.current=true;setBusy(true);onBusyChange(true);setActionError('');
  try{const value=await api<Job>(`${prefix}/${id}/${action}`,'POST');setAction(null);setId(value.id);setDetail(null);setQuery(q=>({...q,reload:q.reload+1}));setNotice(action==='retry'?'새 작업을 접수했습니다.':'대기 작업을 취소했습니다.');requestAnimationFrame(()=>heading.current?.focus());}
  catch(e){setActionError(message(e)+' 닫은 뒤 상태를 새로고침해 주세요.');}
  finally{locked.current=false;setBusy(false);onBusyChange(false);}
 }
 const job=detail?.job;
 return <section className="job-panel"><div className="section-line"><h3 ref={heading} tabIndex={-1}>{id?'외부 작업 상세':'외부 프로젝트 작업'}</h3><div className="actions">
 {id&&<button className="quiet" onClick={()=>select(null)} disabled={busy}><Icon name="arrow-left"/>목록으로</button>}
 <button className="secondary" onClick={()=>setQuery(q=>({...q,reload:q.reload+1}))} disabled={loading||busy} title="작업 새로고침" aria-label="작업 새로고침"><Icon name="refresh-cw"/></button></div></div>
 <p className="small">플랫폼이 대기열과 재시도를 관리하고, 프로젝트 워커가 실행합니다. 완료 이력은 30일간 보관합니다.</p>
 <label className="job-auto"><input type="checkbox" checked={auto} onChange={e=>setAuto(e.target.checked)} disabled={busy}/>5초마다 갱신</label>
 {checked&&<p className="small">마지막 조회 {date(checked)}</p>}{loading&&<p role="status">작업을 확인하는 중…</p>}
 {error&&<p className="alert" role="alert">{error} 기존 결과가 표시되면 마지막 조회 값입니다.</p>}{notice&&<p className="notice" role="status">{notice}</p>}
 {!id?<><form className="job-filters" onSubmit={filter}><label>큐 이름<input name="queue" defaultValue={query.queue} maxLength={64} placeholder="전체 큐"/></label>
 <label>상태<select name="state" defaultValue={query.state}><option value="">전체 상태</option>{Object.entries(states).filter(([key])=>key!=='ABANDONED').map(([key,value])=><option value={key} key={key}>{value}</option>)}</select></label>
 <button disabled={loading}><Icon name="search"/>조회</button></form>
 {rows?.items.length===0&&!error&&<p className="empty">조건에 맞는 작업이 없습니다. 외부 서버에서 작업을 등록하고 워커를 연결해 주세요. <a href="/integrations/jobs.md" target="_blank" rel="noreferrer">Job 연결 지침</a></p>}
 <ul className="job-list">{rows?.items.map(item=><li key={item.id}><button className="external-job-row quiet" onClick={()=>select(item.id)}><span><strong>{item.queue}</strong><code>{item.id}</code><span className="small">{date(item.createdAt)} · 시도 {item.attempts}/{item.maxAttempts}</span></span><span className={`job-state job-${item.state.toLowerCase()}`}>{states[item.state]}{item.state==='RUNNING'?` · ${item.progress}%`:''}</span><Icon name="chevron-right"/></button></li>)}</ul>
 <div className="pagination"><button className="secondary" disabled={loading||query.offset===0} onClick={()=>{setRows(null);setQuery(q=>({...q,offset:q.offset-20}));}}><Icon name="chevron-left"/>이전</button><span>페이지 {query.offset/20+1}</span><button className="secondary" disabled={loading||!rows?.hasMore||query.offset>=10000} onClick={()=>{setRows(null);setQuery(q=>({...q,offset:q.offset+20}));}}><Icon name="chevron-right"/>다음</button></div></>:job&&<div className="job-detail">
 <p className="job-detail-status"><span className={`job-state job-${job.state.toLowerCase()}`}>{states[job.state]}</span><span>시도 {job.attempts}/{job.maxAttempts} · 진행률 {job.progress}%</span></p>
 <dl><div><dt>작업 ID</dt><dd className="identifier">{job.id}</dd></div><div><dt>큐 / 워커</dt><dd>{job.queue} / {job.workerId||'—'}</dd></div><div><dt>다음 실행 / 점유 기한</dt><dd>{date(job.nextRunAt)} / {date(job.leaseUntil)}</dd></div><div><dt>완료 시각</dt><dd>{date(job.completedAt)}</dd></div><div><dt>오류 코드</dt><dd>{job.errorCode||'—'}</dd></div><div><dt>접수 요청 ID</dt><dd className="identifier">{job.requestId||'—'}</dd></div></dl>
 <details><summary>입력·결과 JSON</summary><h4>입력</h4><pre className="external-json">{JSON.stringify(job.payload,null,2)}</pre><h4>결과</h4><pre className="external-json">{JSON.stringify(job.result,null,2)}</pre></details>
 <h4>실행 이력</h4><ul className="job-attempts">{detail.attempts.map(a=><li key={a.attempt}><p>{a.attempt}회 · {a.workerId} · {states[a.state]} {a.errorCode&&<code>{a.errorCode}</code>}</p><p className="small">{date(a.startedAt)} → {date(a.endedAt)}</p></li>)}</ul>{!detail.attempts.length&&<p className="small">아직 워커가 가져가지 않았습니다.</p>}
 <div className="actions">{['QUEUED','RETRY_WAIT'].includes(job.state)&&<button className="danger" disabled={busy||loading||!!error} onClick={()=>{setAction('cancel');setActionError('');}}><Icon name="x"/>작업 취소</button>}{job.state==='FAILED'&&<button disabled={busy||loading||!!error||!available} onClick={()=>{setAction('retry');setActionError('');}}><Icon name="rotate-ccw"/>다시 접수</button>}</div></div>}
 {action&&<Dialog title={action==='retry'?'외부 작업 다시 접수':'대기 작업 취소'} busy={busy} close={()=>{setAction(null);requestAnimationFrame(()=>heading.current?.focus());}}><p className="identifier">{id}</p><p>{action==='retry'?'같은 입력으로 새 작업을 만듭니다. 이전 실행의 외부 처리 결과를 먼저 확인해 주세요.':'이 작업의 이후 실행을 취소합니다. 이전 시도에서 이미 처리한 결과는 되돌리지 않습니다.'}</p>{actionError&&<p role="alert" className="alert">{actionError}</p>}<div className="form-actions"><button className="secondary" disabled={busy} onClick={()=>setAction(null)}>돌아가기</button><button disabled={busy||!!actionError} onClick={()=>void execute()}>{busy?'처리 중…':action==='retry'?'다시 접수':'취소 확정'}</button></div></Dialog>}
 </section>;
}
