import { useEffect, useState } from "react";
import { api, mode } from "./auth";

type Snapshot = { instanceId: string; startedAt: string; measuredAt: string; requests: number; clientErrors: number;
  serverErrors: number; serverErrorPercent: number | null; averageMs: number | null };
type Reading = { id: string; status: string; checkedAt: string; metricsStatus: string; metrics: Snapshot | null };
type Report = { measuredAt: string; services: Reading[] };
const names: Record<string,string> = { "project-service":"프로젝트", "file-service":"파일", "notification-service":"알림" };
const states: Record<string,string> = {UP:"응답 정상", DOWN:"준비 안 됨", UNREACHABLE:"연결 확인 실패", UNKNOWN:"상태 확인 불가"};
const date=(value:string)=>new Date(value).toLocaleString("ko-KR");
const number=(value:number)=>value.toLocaleString("ko-KR",{maximumFractionDigits:2});

export function ServiceMonitoring() {
  const [data,setData]=useState<Report|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(true);
  const [reload,setReload]=useState(0),[auto,setAuto]=useState(true),[now,setNow]=useState(Date.now());
  useEffect(()=>{const timer=setInterval(()=>setNow(Date.now()),5000);return()=>clearInterval(timer)},[]);
  useEffect(()=>{
    let live=true,pending=false,timer:ReturnType<typeof setTimeout>|undefined;
    async function load() {
      if(!live||pending||document.hidden)return;
      clearTimeout(timer);pending=true;setLoading(true);
      try { const report=await api<Report>("/service-metrics"); if(live){setData(report);setError("");setNow(Date.now())} }
      catch(e){if(live)setError(e instanceof Error?e.message:"서비스 지표를 불러오지 못했습니다.")}
      finally{pending=false;if(live){setLoading(false);if(auto)timer=setTimeout(load,30000)}}
    }
    const visibility=()=>{if(!document.hidden&&auto)void load()};
    document.addEventListener("visibilitychange",visibility);void load();
    return()=>{live=false;clearTimeout(timer);document.removeEventListener("visibilitychange",visibility)};
  },[reload,auto]);
  const stale=data!==null&&now-Date.parse(data.measuredAt)>=60000;
  return <section className="job-panel service-monitoring" aria-labelledby="service-monitoring-title">
    <div className="section-line"><h2 id="service-monitoring-title">플랫폼 서비스 상태</h2><button className="secondary" disabled={loading} onClick={()=>setReload(v=>v+1)}>서비스 새로고침</button></div>
    <p className="muted">{mode==="dev"?"개발":"운영"} 플랫폼 전체의 프로젝트·파일·알림 서비스를 확인합니다. 프로젝트 선택과 관계없는 공통 지표입니다.</p>
    <label className="job-auto"><input type="checkbox" checked={auto} disabled={loading} onChange={e=>setAuto(e.target.checked)} />30초마다 갱신</label>
    <p className="small muted">각 서비스 재시작 이후의 누적값입니다. 상태 검사·이 화면의 조회 요청은 제외하며, 내부 API와 거절된 요청은 포함합니다. 화면을 숨기면 자동 갱신을 쉽니다.</p>
    {loading&&<p role="status">{data?"서비스 확인 중… 아래는 이전 측정값입니다.":"서비스 상태를 확인하는 중…"}</p>}
    {error&&<p className="alert" role="alert">{error} {data?"아래는 이전 측정값입니다.":"현재 상태를 확인하지 못했습니다."} 서비스 새로고침으로 다시 확인해 주세요.</p>}
    {data&&<>
      <p className={stale?"warning":"small muted"} role="status">조회 시각 {date(data.measuredAt)}{stale?" · 1분 이상 지난 값입니다. 새로고침해 주세요.":" · 최대 10초 동안 같은 조회 결과를 사용합니다."}</p>
      <div className="service-status-list">{data.services.map(service=><article key={service.id} aria-label={`${names[service.id]??service.id} 서비스`}>
        <div className="section-line"><h3>{names[service.id]??service.id}</h3><strong className={service.status==="UP"?"state ready":"danger-text"}>{states[service.status]??"상태 확인 불가"}</strong></div>
        <p className="small muted">상태 확인 {date(service.checkedAt)}</p>
        {service.metrics?<><dl className="monitoring-values">
          <div><dt>처리 요청</dt><dd>{number(service.metrics.requests)}건</dd></div>
          <div><dt>클라이언트 오류 (4xx)</dt><dd>{number(service.metrics.clientErrors)}건</dd></div>
          <div><dt>서버 오류 (5xx)</dt><dd>{number(service.metrics.serverErrors)}건</dd></div>
        </dl><p className="small muted">집계 시작 {date(service.metrics.startedAt)}<br/>지표 측정 {date(service.metrics.measuredAt)}</p></>
          :<p className="warning">{service.metricsStatus==="DISABLED"?"지표 수집 연결이 설정되지 않았습니다. 서버 설정을 확인해 주세요.":"지표를 조회하지 못했습니다. 새로고침 후에도 계속되면 서비스 연결을 확인해 주세요."}</p>}
      </article>)}</div>
      <div className="monitoring-layout">
        <Comparison title="서버 오류율 (5xx)" unit="%" services={data.services} value={v=>v.serverErrorPercent} fixedMax={100} tone="failed" />
        <Comparison title="평균 처리 시간" unit="ms" services={data.services} value={v=>v.averageMs} tone="completed" />
      </div>
      <p className="small muted">오류율은 5xx ÷ 처리 요청 수입니다. 4xx는 별도 건수로 표시합니다. 요청이 없으면 비율과 평균을 계산하지 않습니다. 서비스마다 집계 시작과 처리하는 요청이 다르므로 성능 순위로 해석하지 마세요.</p>
      <details><summary>측정 범위와 한계</summary><p>응답 정상은 서비스 준비 상태와 DB 연결 확인 결과입니다. 로그인·메일 발송 등 모든 기능의 성공을 뜻하지 않습니다. 처리 시간은 서비스 내부 HTTP 처리 시간이며 인터넷·프록시 구간은 포함하지 않습니다.</p>
        <p>현재 Compose의 서비스별 단일 인스턴스를 조회합니다. 지표는 메모리에만 남고 재시작하면 초기화됩니다. Nginx·Keycloak·디스크, 최근 구간 오류율·시간대별 추이·장기 보관·서비스 장애 자동 알림은 아직 제공하지 않습니다. 프로젝트 서비스 자체가 중단되면 이 화면도 조회할 수 없습니다.</p></details>
    </>}
  </section>;
}
function Comparison({title,unit,services,value,fixedMax,tone}:{title:string;unit:string;services:Reading[];value:(v:Snapshot)=>number|null;fixedMax?:number;tone:string}) {
  const rows=services.map(service=>({service,value:service.metrics?value(service.metrics):null}));
  const max=fixedMax??Math.max(0,...rows.map(row=>row.value??0));
  return <figure className="job-result-chart service-chart"><figcaption><h3>{title}</h3><p className="small muted">서비스 재시작 이후 · {unit==="%"?"0~100% 고정 눈금":"전체 서비스 공통 눈금"}</p></figcaption>
    <div className="job-chart-scale small muted" aria-hidden="true"><span>0</span><span>{number(max)}{unit}</span></div>
    <dl className="job-chart-bars" aria-label={title}>{rows.map(row=><div key={row.service.id}><dt>{names[row.service.id]??row.service.id}</dt><dd>
      <span className="job-chart-track" aria-hidden="true"><span className={`job-chart-bar ${tone}`} style={{width:`${max&&row.value!==null?row.value/max*100:0}%`}} /></span>
      <span className="job-chart-count">{row.value===null?row.service.metrics?"요청 없음":"미수집":`${number(row.value)}${unit}`}</span>
    </dd></div>)}</dl>
  </figure>;
}
