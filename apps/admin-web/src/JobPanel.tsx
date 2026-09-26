import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "./auth";
import { Dialog } from "./Dialog";
import { JobEventsPanel } from "./JobEventsPanel";

type Job = {
  id: string; environmentId: string; state: string; targetRevision: number;
  attempts: number; maxAttempts: number; nextRunAt: string; leaseUntil: string | null;
  requestId: string; errorCode: string | null; retryOf: string | null;
  createdAt: string; updatedAt: string; completedAt: string | null;
};
type Detail = { job: Job; attempts: {
  attempt: number; state: string; errorCode: string | null; startedAt: string; endedAt: string | null;
}[] };
const states: Record<string, string> = {
  QUEUED: "대기", RUNNING: "실행 중", RETRY_WAIT: "재시도 대기", SUCCEEDED: "완료",
  FAILED: "최종 실패", CANCELLED: "취소됨", ABANDONED: "실행 중단",
};
const failures: Record<string, string> = {
  ENVIRONMENT_PROVISION_FAILED: "인증 서버에 환경 설정을 반영하지 못했습니다.",
  JOB_EXECUTION_FAILED: "작업 처리 중 오류가 발생했습니다. 요청 ID로 서버 기록을 확인하세요.",
  WORKER_INTERRUPTED: "이전 실행이 중단되어 새 실행자가 작업을 이어받았습니다.",
  RETRY_EXHAUSTED: "실행 시도 한도에 도달했습니다. 연결 상태를 확인한 뒤 다시 접수하세요.",
  JOB_TARGET_CHANGED: "접수 후 환경 설정이 바뀌어 작업을 취소했습니다.",
};
const active = (state: string) => ["QUEUED", "RUNNING", "RETRY_WAIT"].includes(state);
const date = (value: string | null) => value ? new Date(value).toLocaleString("ko-KR") : "—";
const errorText = (error: unknown) => error instanceof Error ? error.message : "작업을 조회하지 못했습니다. 다시 시도해 주세요.";
function State({ value, attempt = false }: { value: string; attempt?: boolean }) {
  return <span className={`job-state job-${value.toLowerCase()}`}>{attempt && value === "FAILED" ? "실패" : states[value] || value}</span>;
}
function Failure({ code }: { code: string | null }) {
  return code ? <p className="small job-failure">{failures[code] || "작업 상태를 확인해 주세요."} <code>{code}</code></p> : null;
}

export function JobPanel({ environmentId, environmentLabel, ready, disabled, onBusyChange, onSettled }: {
  environmentId: string; environmentLabel: string; ready: boolean; disabled: boolean;
  onBusyChange: (busy: boolean) => void; onSettled: () => void;
}) {
  const [query, setQuery] = useState({ state: "", from: "", to: "", offset: 0, reload: 0 });
  const [rows, setRows] = useState<Job[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [auto, setAuto] = useState(true);
  const [checked, setChecked] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const live = useRef(true);
  const locked = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    if (selected || confirm) return;
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    setLoading(true); setRows(null); setError(""); setChecked(null);
    const params = new URLSearchParams({ environmentId, state: query.state, limit: "21", offset: String(query.offset) });
    if (!query.state) params.delete("state");
    if (query.from) params.set("createdFrom", new Date(query.from + "T00:00:00").toISOString());
    if (query.to) {
      const end = new Date(query.to + "T00:00:00"); end.setDate(end.getDate() + 1);
      params.set("createdTo", end.toISOString());
    }
    async function load() {
      if (document.hidden) { timer = setTimeout(load, 5000); return; }
      try {
        const result = await api<Job[]>(`/jobs?${params}`);
        if (!current) return;
        setRows(result); setError(""); setChecked(new Date().toISOString());
        if (auto && result.some(job => active(job.state))) timer = setTimeout(load, 5000);
      } catch (e) { if (current) { setError(errorText(e)); setRows(null); } }
      finally { if (current) setLoading(false); }
    }
    void load();
    return () => { current = false; clearTimeout(timer); };
  }, [environmentId, query, selected, confirm, auto]);
  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const from = String(data.get("from")), to = String(data.get("to"));
    if (from && to && from > to) { setError("접수 종료일은 시작일과 같거나 뒤여야 합니다."); return; }
    setQuery({ state: String(data.get("state")), from, to, offset: 0, reload: query.reload + 1 });
  }
  async function enqueue() {
    if (locked.current || actionError) return;
    locked.current = true; setBusy(true); onBusyChange(true);
    try {
      const job = await api<Job>(`/environments/${environmentId}/provision-jobs`, "POST");
      if (live.current) {
        setConfirm(false); setSelected(job.id);
        setNotice("작업을 접수했습니다. 완료 여부는 아래 상태와 시도 이력에서 확인하세요.");
      }
    } catch (e) { if (live.current) setActionError(errorText(e) + " 닫은 뒤 목록을 새로고침해 접수 여부를 확인하세요."); }
    finally { locked.current = false; if (live.current) { setBusy(false); onBusyChange(false); } }
  }
  return <section className="job-panel" aria-labelledby="jobs-title">
    <div className="section-line">
      <h3 id="jobs-title" ref={heading} tabIndex={-1}>비동기 작업</h3>
      {!selected && <button className="secondary" disabled={disabled || busy || ready}
        onClick={() => { setActionError(""); setConfirm(true); }}>환경 반영 접수</button>}
    </div>
    <p className="small muted">{environmentLabel}의 환경 반영 상태와 시도 이력입니다.
      {ready ? " 현재 로그인 설정은 반영된 상태입니다." : " 대기·실패한 환경 설정을 백그라운드에서 반영할 수 있습니다."}</p>
    <label className="job-auto"><input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} disabled={busy || disabled} />진행 중인 작업 5초마다 갱신</label>
    {notice && <p role="status" className="notice">{notice}</p>}
    {selected ? <JobDetail key={selected} id={selected} environmentLabel={environmentLabel} auto={auto}
      onBusyChange={onBusyChange} onSettled={onSettled} select={id => { setNotice(""); setSelected(id); }}
      back={() => { setSelected(null); setNotice(""); heading.current?.focus(); }} /> : <>
      <form className="job-filters" onSubmit={filter}>
        <label>작업 상태<select name="state" defaultValue={query.state}>{<option value="">전체 상태</option>}
          {Object.entries(states).filter(([key]) => key !== "ABANDONED").map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select></label>
        <label>접수 시작일<input name="from" type="date" defaultValue={query.from} /></label>
        <label>접수 종료일<input name="to" type="date" defaultValue={query.to} /></label>
        <button type="submit" disabled={loading || disabled}>조회</button>
      </form>
      <div className="job-tools"><span className="small muted">{checked ? `마지막 확인 ${date(checked)}` : "접수일은 현재 기기의 시간대를 기준으로 조회합니다."}</span>
        <button className="quiet" disabled={loading || disabled} onClick={() => setQuery(q => ({ ...q, reload: q.reload + 1 }))}>작업 새로고침</button></div>
      {loading && <p role="status">작업을 불러오는 중…</p>}
      {error && <p role="alert" className="alert">{error}</p>}
      {rows && (rows.length ? <ul className="job-list">{rows.slice(0, 20).map(job => <li key={job.id}>
        <button className="job-row" onClick={() => setSelected(job.id)} disabled={disabled}>
          <span className="job-summary"><strong>환경 설정 반영</strong><span className="small muted">접수 {date(job.createdAt)}</span><code>{job.id}</code></span>
          <span className="job-row-status"><State value={job.state} /><span className="small muted">시도 {job.attempts}/{job.maxAttempts} · 상세 보기</span></span>
        </button>
      </li>)}</ul> : <p className="empty">{query.state || query.from || query.to ? "조회 조건에 맞는 작업이 없습니다. 상태나 기간을 바꿔 보세요." : "아직 접수된 작업이 없습니다. 반영이 필요한 환경에서 작업을 접수할 수 있습니다."}</p>)}
      <div className="pagination" aria-label="작업 목록 페이지">
        <button className="secondary" disabled={loading || disabled || !query.offset} onClick={() => setQuery(q => ({ ...q, offset: q.offset - 20 }))}>이전 작업</button>
        <span className="small">페이지 {query.offset / 20 + 1}</span>
        <button className="secondary" disabled={loading || disabled || !rows || rows.length <= 20} onClick={() => setQuery(q => ({ ...q, offset: q.offset + 20 }))}>다음 작업</button>
      </div>
    </>}
    {confirm && <Dialog title="환경 반영 작업 접수" busy={busy} close={() => setConfirm(false)}>
      <p><strong>{environmentLabel}</strong></p><p>현재 설정을 인증 서버에 반영합니다. 같은 환경에 진행 중인 작업이 있으면 그 작업을 확인합니다.</p>
      <p className="warning">접수는 완료와 다릅니다. 실패하면 최대 3회 시도하며, 실행 중인 작업은 취소할 수 없습니다.</p>
      {actionError && <p role="alert" className="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={() => setConfirm(false)}>돌아가기</button>
        <button disabled={busy || !!actionError} onClick={() => void enqueue()}>{busy ? "접수 중…" : "작업 접수"}</button></div>
    </Dialog>}
  </section>;
}

function JobDetail({ id, environmentLabel, auto, back, select, onBusyChange, onSettled }: {
  id: string; environmentLabel: string; auto: boolean; back: () => void; select: (id: string) => void;
  onBusyChange: (busy: boolean) => void; onSettled: () => void;
}) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(true), [reload, setReload] = useState(0);
  const [error, setError] = useState(""), [actionError, setActionError] = useState("");
  const [action, setAction] = useState<"cancel" | "retry" | null>(null), [busy, setBusy] = useState(false);
  const [eventDialog, setEventDialog] = useState(false);
  const [notice, setNotice] = useState("");
  const live = useRef(true), locked = useRef(false), settled = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const notifySettled = useRef(onSettled); notifySettled.current = onSettled;
  useEffect(() => { live.current = true; heading.current?.focus(); return () => { live.current = false; }; }, []);
  useEffect(() => {
    if (action || busy || eventDialog) return;
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    setLoading(true); setError("");
    async function load() {
      if (document.hidden) { timer = setTimeout(load, 5000); return; }
      try {
        const value = await api<Detail>(`/jobs/${id}`);
        if (!current) return;
        setDetail(value); setError("");
        if (active(value.job.state) && auto) timer = setTimeout(load, 5000);
        if (!active(value.job.state) && !settled.current) { settled.current = true; notifySettled.current(); }
      } catch (e) { if (current) setError(errorText(e)); }
      finally { if (current) setLoading(false); }
    }
    void load();
    return () => { current = false; clearTimeout(timer); };
  }, [id, reload, auto, action, busy, eventDialog]);
  async function execute() {
    if (!action || locked.current || actionError) return;
    locked.current = true; setBusy(true); onBusyChange(true);
    try {
      const result = await api<Job>(`/jobs/${id}/${action}`, "POST");
      if (live.current) {
        if (action === "retry") select(result.id);
        else { setNotice("작업을 취소했습니다."); closeAction(); }
      }
    } catch (e) { if (live.current) setActionError(errorText(e) + " 닫은 뒤 최신 작업 상태를 확인해 주세요."); }
    finally { locked.current = false; onBusyChange(false); if (live.current) setBusy(false); }
  }
  const job = detail?.job;
  const attempts = detail?.attempts ?? [];
  function closeAction() {
    setAction(null); setReload(n => n + 1);
    // Status refresh disables/replaces the action button, so return to the stable detail heading.
    requestAnimationFrame(() => heading.current?.focus());
  }
  return <div className="job-detail">
    <div className="section-line"><h4 ref={heading} tabIndex={-1}>작업 상세</h4><div className="actions">
      <button className="quiet" disabled={busy || eventDialog} onClick={back}>작업 목록으로</button>
      <button className="secondary" disabled={busy || eventDialog || loading} onClick={() => setReload(n => n + 1)}>상태 새로고침</button>
    </div></div>
    {loading && <p role="status">상태를 확인하는 중…</p>}
    {error && <p className="alert" role="alert">{error} 표시된 정보는 마지막 조회 결과입니다.</p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    {job && <>
      <p className="job-detail-status" role="status"><State value={job.state} /> <span>시도 {job.attempts}/{job.maxAttempts}</span></p>
      <Failure code={job.errorCode} />
      <dl><div><dt>작업 ID</dt><dd className="identifier">{job.id}</dd></div>
        <div><dt>접수 시각</dt><dd>{date(job.createdAt)}</dd></div>
        <div><dt>마지막 변경</dt><dd>{date(job.updatedAt)}</dd></div>
        <div><dt>완료 시각</dt><dd>{date(job.completedAt)}</dd></div>
        {job.state === "RETRY_WAIT" && <div><dt>다음 시도 예정</dt><dd>{date(job.nextRunAt)}</dd></div>}
        <div><dt>요청 ID</dt><dd className="identifier">{job.requestId}</dd></div>
        {job.retryOf && <div><dt>원본 작업</dt><dd><button className="quiet job-link" disabled={busy} onClick={() => select(job.retryOf!)}>{job.retryOf}</button></dd></div>}
      </dl>
      <div className="actions">
        {["QUEUED", "RETRY_WAIT"].includes(job.state) && <button className="secondary danger" disabled={busy || loading || !!error}
          onClick={() => { setActionError(""); setAction("cancel"); }}>작업 취소</button>}
        {job.state === "FAILED" && <button disabled={busy || loading || !!error} onClick={() => { setActionError(""); setAction("retry"); }}>실패 작업 재시도</button>}
      </div>
      {job.state === "RUNNING" && <p className="small muted">실행 중에는 취소할 수 없습니다. 완료 상태를 확인해 주세요.</p>}
      <h4>시도 이력</h4>
      {attempts.length ? <ol className="job-attempts">{attempts.map(attempt => <li key={attempt.attempt}>
        <div className="section-line"><strong>{attempt.attempt}차 시도</strong><State value={attempt.state} attempt /></div>
        <p className="small muted">시작 {date(attempt.startedAt)}<br />종료 {date(attempt.endedAt)}</p>
        <Failure code={attempt.errorCode} />
      </li>)}</ol> : <p className="empty">아직 실행한 시도가 없습니다.</p>}
      <JobEventsPanel jobId={id} jobState={job.state} environmentLabel={environmentLabel} auto={auto}
        paused={!!action || busy} onBusyChange={onBusyChange} onDialogChange={setEventDialog} />
    </>}
    {action && job && <Dialog title={action === "cancel" ? "작업 취소 확인" : "실패 작업 재시도"} busy={busy} close={closeAction}>
      <p><strong>{environmentLabel}</strong></p><p className="identifier">{job.id}</p>
      <p>{action === "cancel" ? "대기 중인 작업을 취소합니다. 이미 시작됐다면 취소하지 않고 최신 상태를 안내합니다." : "원본 이력은 보존하고 현재 환경 설정으로 다시 접수합니다. 기존 후속 작업이나 진행 중인 작업이 있으면 그 작업으로 이동합니다."}</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={closeAction}>돌아가기</button>
        <button className={action === "cancel" ? "destructive" : ""} disabled={busy || !!actionError} onClick={() => void execute()}>{busy ? "처리 중…" : action === "cancel" ? "작업 취소" : "다시 접수"}</button></div>
    </Dialog>}
  </div>;
}
