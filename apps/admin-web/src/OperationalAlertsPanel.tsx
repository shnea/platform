import { useEffect, useRef, useState } from "react";
import { api } from "./auth";
import { Dialog } from "./Dialog";

type Alert = {
  id: string; projectId: string; environmentId: string; jobId: string; code: string; errorCode: string | null;
  requestId: string; occurredAt: string; createdAt: string; acknowledgedAt: string | null;
  acknowledgedBy: string | null; acknowledgementRequestId: string | null;
};
const date = (value: string) => new Date(value).toLocaleString("ko-KR");
const message = (e: unknown) => e instanceof Error ? e.message : "운영 알림을 확인하지 못했습니다.";
const errors: Record<string, string> = {
  ENVIRONMENT_PROVISION_FAILED: "인증 서버에 환경 설정을 반영하지 못했습니다.",
  JOB_EXECUTION_FAILED: "환경 반영 작업 처리 중 오류가 발생했습니다.",
  RETRY_EXHAUSTED: "환경 반영 작업이 실행 시도 한도에 도달했습니다.",
};
export function OperationalAlertsPanel({ environmentId, environmentLabel, disabled, onBusyChange }: {
  environmentId: string; environmentLabel: string; disabled: boolean; onBusyChange: (busy: boolean) => void;
}) {
  const [query, setQuery] = useState({ acknowledged: "false", offset: 0, reload: 0 });
  const [rows, setRows] = useState<Alert[] | null>(null), [loading, setLoading] = useState(true);
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const [selected, setSelected] = useState<Alert | null>(null), [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const live = useRef(true), locked = useRef(false), heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    if (selected) return;
    let current = true;
    setLoading(true); setRows(null); setError("");
    const params = new URLSearchParams({ limit: "21", offset: String(query.offset) });
    if (query.acknowledged) params.set("acknowledged", query.acknowledged);
    api<Alert[]>(`/environments/${environmentId}/operational-alerts?${params}`).then(value => {
      if (current) setRows(value);
    }).catch(e => { if (current) setError(message(e)); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [environmentId, query, selected]);
  function close() {
    setSelected(null); setQuery(q => ({ ...q, reload: q.reload + 1 }));
    requestAnimationFrame(() => heading.current?.focus());
  }
  async function acknowledge() {
    if (!selected || locked.current || actionError) return;
    locked.current = true; setBusy(true); onBusyChange(true);
    try {
      await api<Alert>(`/environments/${environmentId}/operational-alerts/${selected.id}/acknowledge`, "POST");
      if (live.current) {
        setNotice("알림의 확인 상태를 반영했습니다. 확인됨 필터에서 최초 확인 기록을 볼 수 있습니다.");
        setQuery(q => ({ ...q, offset: 0 })); close();
      }
    } catch (e) { if (live.current) setActionError(message(e) + " 닫은 뒤 알림 상태를 다시 확인해 주세요."); }
    finally { locked.current = false; onBusyChange(false); if (live.current) setBusy(false); }
  }
  return <section className="job-panel operational-alerts" aria-labelledby="operational-alerts-title">
    <div className="section-line"><h3 id="operational-alerts-title" ref={heading} tabIndex={-1}>운영 알림</h3>
      <button className="secondary" disabled={loading || disabled || busy} onClick={() => setQuery(q => ({ ...q, reload: q.reload + 1 }))}>알림 새로고침</button></div>
    <p className="small muted">{environmentLabel}의 작업 최종 실패 알림입니다. 확인 처리는 알림을 읽었다는 기록이며, 문제 해결이나 작업 재실행을 뜻하지 않습니다.</p>
    <label className="alert-filter">확인 상태<select value={query.acknowledged} disabled={loading || disabled || busy}
      onChange={e => { setNotice(""); setQuery({ acknowledged: e.target.value, offset: 0, reload: query.reload + 1 }); }}>
      <option value="false">미확인</option><option value="true">확인됨</option><option value="">전체</option>
    </select></label>
    {notice && <p className="notice" role="status">{notice}</p>}
    {loading && <p role="status">운영 알림을 불러오는 중…</p>}
    {error && <p className="alert" role="alert">{error} 알림 새로고침으로 다시 확인해 주세요.</p>}
    {rows && (rows.length ? <ul className="job-attempts">{rows.slice(0, 20).map(alert => <li key={alert.id}>
      <div className="section-line"><strong>{alert.code === "JOB_FAILED" ? "환경 반영 작업 최종 실패" : "운영 알림"}</strong>
        <span className={`job-state ${alert.acknowledgedAt ? "" : "job-running"}`}>{alert.acknowledgedAt ? "확인됨" : "미확인"}</span></div>
      <p>{alert.errorCode && errors[alert.errorCode] || "작업 상태와 서버 기록을 확인해 주세요."}</p>
      <p className="small muted">발생 {date(alert.occurredAt)} · 알림 수신 {date(alert.createdAt)}</p>
      <details className="job-event-history"><summary>알림 상세·확인 기록</summary>
        <dl><div><dt>알림 ID</dt><dd className="identifier">{alert.id}</dd></div>
          <div><dt>작업 ID</dt><dd className="identifier">{alert.jobId}</dd></div>
          <div><dt>실패 코드</dt><dd className="identifier">{alert.errorCode || "—"}</dd></div>
          <div><dt>작업 요청 ID</dt><dd className="identifier">{alert.requestId}</dd></div>
          {alert.acknowledgedAt && <><div><dt>최초 확인 시각</dt><dd>{date(alert.acknowledgedAt)}</dd></div>
            <div><dt>확인 관리자 ID</dt><dd className="identifier">{alert.acknowledgedBy}</dd></div>
            <div><dt>확인 요청 ID</dt><dd className="identifier">{alert.acknowledgementRequestId}</dd></div></>}
        </dl>
      </details>
      {!alert.acknowledgedAt && <button className="secondary" disabled={disabled || busy || loading}
        onClick={() => { setActionError(""); setSelected(alert); }}>알림 확인 처리</button>}
    </li>)}</ul> : <p className="empty">{query.acknowledged === "false" ? "미확인 운영 알림이 없습니다." : "조회 조건에 맞는 운영 알림이 없습니다."}</p>)}
    <div className="pagination" aria-label="운영 알림 페이지">
      <button className="secondary" disabled={loading || disabled || busy || !query.offset} onClick={() => setQuery(q => ({ ...q, offset: q.offset - 20 }))}>이전 알림</button>
      <span className="small">페이지 {query.offset / 20 + 1}</span>
      <button className="secondary" disabled={loading || disabled || busy || !rows || rows.length <= 20} onClick={() => setQuery(q => ({ ...q, offset: q.offset + 20 }))}>다음 알림</button>
    </div>
    {selected && <Dialog title="운영 알림 확인" busy={busy} close={close}>
      <p><strong>{environmentLabel}</strong></p><p>발생 {date(selected.occurredAt)}</p><p className="identifier">알림 {selected.id}</p>
      <p>이 알림을 확인됨으로 표시합니다. 최초 확인 관리자와 시각을 보관하며 이미 확인된 알림은 기존 기록을 유지합니다.</p>
      <p className="warning">확인해도 실패한 작업은 다시 실행되지 않습니다. 원인 확인과 작업 재시도는 별도로 진행하세요.</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={close}>돌아가기</button>
        <button disabled={busy || !!actionError} onClick={() => void acknowledge()}>{busy ? "처리 중…" : "확인 처리"}</button></div>
    </Dialog>}
  </section>;
}
