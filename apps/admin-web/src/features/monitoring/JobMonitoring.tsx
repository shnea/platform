import { useEffect, useState } from "react";
import { api } from "../../shared/auth";

type Metrics = { projectId: string; environmentId: string; measuredAt: string; windowFrom: string;
  queued: number; retryWaiting: number; running: number; dueWaiting: number; oldestWaitingAt: string | null;
  oldestWaitingSeconds: number | null; succeededLast24Hours: number; failedLast24Hours: number; cancelledLast24Hours: number };
const date = (value: string) => new Date(value).toLocaleString("ko-KR");
function duration(seconds: number | null) {
  if (seconds === null) return "대기 없음";
  const days = Math.floor(seconds / 86400), hours = Math.floor(seconds % 86400 / 3600), minutes = Math.floor(seconds % 3600 / 60);
  return `${days ? `${days}일 ` : ""}${hours ? `${hours}시간 ` : ""}${minutes ? `${minutes}분 ` : ""}${seconds % 60}초`;
}
export function JobMonitoring({ environmentId, environmentLabel, disabled, openJobs, openAlerts }: {
  environmentId: string; environmentLabel: string; disabled: boolean;
  openJobs: (state: string) => void; openAlerts: () => void;
}) {
  const [data, setData] = useState<Metrics | null>(null), [error, setError] = useState("");
  const [auto, setAuto] = useState(true), [reload, setReload] = useState(0), [loading, setLoading] = useState(true);
  const [now, setNow] = useState(Date.now()), [hidden, setHidden] = useState(document.hidden);
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 5000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    let live = true, pending = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function load() {
      if (!live || pending || document.hidden) return;
      clearTimeout(timer); pending = true; setLoading(true);
      try {
        const value = await api<Metrics>(`/environments/${environmentId}/job-metrics`);
        if (live) { setData(value); setError(""); setNow(Date.now()); }
      } catch (e) { if (live) setError(e instanceof Error ? e.message : "작업 지표를 불러오지 못했습니다."); }
      finally { pending = false; if (live) { setLoading(false); if (auto) timer = setTimeout(load, 30000); } }
    }
    function visibility() { setHidden(document.hidden); if (!document.hidden && auto) void load(); }
    document.addEventListener("visibilitychange", visibility);
    void load();
    return () => { live = false; clearTimeout(timer); document.removeEventListener("visibilitychange", visibility); };
  }, [environmentId, reload, auto]);
  const stale = data && now - Date.parse(data.measuredAt) >= 60000;
  const results = data ? [
    { label: "완료", count: data.succeededLast24Hours, tone: "completed" },
    { label: "최종 실패", count: data.failedLast24Hours, tone: "failed" },
    { label: "취소", count: data.cancelledLast24Hours, tone: "cancelled" },
  ] : [];
  const maximum = Math.max(0, ...results.map(result => result.count));
  return <section className="job-panel monitoring-panel" aria-labelledby="job-monitoring-title">
    <div className="section-line"><h3 id="job-monitoring-title">환경 반영 작업 현황</h3>
      <button className="secondary" disabled={disabled || loading} onClick={() => setReload(v => v + 1)}>지표 새로고침</button></div>
    <p className="small muted">{environmentLabel}만 집계합니다. 현재 환경 반영 작업은 환경당 하나만 대기·실행할 수 있습니다.</p>
    <label className="job-auto"><input type="checkbox" checked={auto} disabled={disabled || loading} onChange={e => setAuto(e.target.checked)} />30초마다 갱신</label>
    {hidden && <p className="small muted">화면이 숨겨져 자동 갱신을 쉬고 있습니다.</p>}
    {loading && <p role="status">{data ? "지표 갱신 중… 아래는 이전 측정값입니다." : "작업 지표를 불러오는 중…"}</p>}
    {error && <p className="alert" role="alert">{error} {data ? "아래는 이전 측정값입니다." : "현재 수치를 확인할 수 없습니다."} 지표 새로고침으로 다시 확인해 주세요.</p>}
    {data && <>
      <p className={stale ? "warning" : "small muted"} role="status">측정 시각 {date(data.measuredAt)}
        {stale ? " · 1분 이상 지난 값입니다. 새로고침해 현재 상태를 확인하세요." : error ? " · 재조회 필요" : ""}</p>
      <div className="monitoring-layout"><div>
      <h4>현재 작업 상태</h4>
      <dl className="monitoring-values" aria-label="현재 작업 상태">
        <div><dt>대기 중인 작업<span className="small muted">최초 대기와 재시도 대기를 합산</span></dt><dd>{data.queued + data.retryWaiting}건</dd></div>
        <div><dt>최초 대기</dt><dd>{data.queued}건 <button className="quiet" disabled={disabled} onClick={() => openJobs("QUEUED")}>대기 작업 보기</button></dd></div>
        <div><dt>재시도 대기</dt><dd>{data.retryWaiting}건 <button className="quiet" disabled={disabled} onClick={() => openJobs("RETRY_WAIT")}>재시도 대기 보기</button></dd></div>
        <div><dt>실행 중</dt><dd>{data.running}건 <button className="quiet" disabled={disabled} onClick={() => openJobs("RUNNING")}>실행 중 작업 보기</button></dd></div>
        <div><dt>실행 예정 시각이 지난 대기<span className="small muted">재시도 예약 시간이 남은 작업은 제외</span></dt><dd>{data.dueWaiting}건</dd></div>
        <div><dt>최장 대기 시간<span className="small muted">현재 대기 상태로 바뀐 시점부터 측정 시각까지</span></dt><dd>{duration(data.oldestWaitingSeconds)}
          {data.oldestWaitingAt && <span className="small muted">대기 시작 {date(data.oldestWaitingAt)}</span>}</dd></div>
      </dl>
      </div><div>
      <h4>최근 24시간 처리 결과</h4>
      <figure className="job-result-chart" aria-label="최근 24시간 처리 건수 비교">
        <figcaption className="small muted">{date(data.windowFrom)} 이상 ~ {date(data.measuredAt)} 미만 · 완료 시각 기준</figcaption>
        <div className="job-chart-scale small muted" aria-hidden="true"><span>0</span><span>{maximum.toLocaleString("ko-KR")}건</span></div>
        <dl className="job-chart-bars" aria-label="최근 24시간 처리 결과">
          {results.map(result => <div key={result.tone}>
            <dt>{result.label}</dt><dd>
              <span className="job-chart-track" aria-hidden="true"><span className={`job-chart-bar ${result.tone}`} style={{ width: `${maximum ? result.count / maximum * 100 : 0}%` }} /></span>
              <span className="job-chart-count">{result.count.toLocaleString("ko-KR")}건</span>
            </dd>
          </div>)}
        </dl>
        {maximum === 0 && <p className="small muted">이 기간에 종료된 작업이 없습니다.</p>}
      </figure>
      <p className="small muted">막대는 같은 눈금의 건수 비교입니다. 최종 실패는 이후 복구돼도 원래 실패 기록을 포함합니다.</p>
      <div className="form-actions"><button className="secondary" disabled={disabled} onClick={() => openJobs("FAILED")}>전체 기간 실패 작업 보기</button>
        <button className="secondary" disabled={disabled} onClick={openAlerts}>운영 알림 보기</button></div>
      </div></div>
      <p className="small muted">‘적체 경보 설정’에서 오래 대기하는 작업의 알림을 켤 수 있습니다. 플랫폼 전체 서비스 지표는 위의 ‘서비스 상태’에서 확인하세요. 디스크 지표는 후속 제공 예정입니다.</p>
    </>}
  </section>;
}
