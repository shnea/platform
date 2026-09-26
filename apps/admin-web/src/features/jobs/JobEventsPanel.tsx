import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/auth";
import { Dialog } from "../../shared/Dialog";

type Delivery = {
  id: string; jobId: string; environmentId: string; eventType: string;
  state: "PENDING" | "DELIVERED" | "FAILED"; attempts: number; cycleAttempts: number;
  errorCode: string | null; nextRunAt: string; deliveredAt: string | null;
};
type EventDetail = { delivery: Delivery; attempts: {
  attempt: number; state: string; errorCode: string | null; httpStatus: number | null;
  startedAt: string; endedAt: string | null;
}[] };
const types: Record<string, string> = { "job.succeeded": "작업 완료", "job.failed": "작업 최종 실패", "job.cancelled": "작업 취소",
  "job.backlogged": "작업 대기 적체", "job.backlog_recovered": "대기 적체 해소", "job.backlog_closed": "적체 감시 종료" };
const attemptStates: Record<string, string> = { RUNNING: "전달 중", DELIVERED: "전달 완료", FAILED: "전달 실패", ABANDONED: "전달 중단" };
const errors: Record<string, string> = {
  DELIVERY_UNCONFIRMED: "수신 확인을 받지 못했습니다. 같은 이벤트를 다시 보내도 수신 기록은 중복되지 않습니다.",
  DELIVERY_UNAVAILABLE: "수신 서비스가 일시적으로 요청을 처리하지 못했습니다.",
  DELIVERY_REJECTED: "수신 서비스가 요청을 거부했습니다. 서버의 인증키·이벤트 버전·오류 기록을 확인한 뒤 재전송하세요.",
  DELIVERY_INTERRUPTED: "이전 전달이 중단되어 새 실행자가 이어받습니다.",
  DELIVERY_EXHAUSTED: "자동 전달 시도 한도에 도달했습니다. 연결 상태를 확인한 뒤 재전송하세요.",
};
const date = (value: string | null) => value ? new Date(value).toLocaleString("ko-KR") : "—";
const message = (error: unknown) => error instanceof Error ? error.message : "이벤트 전달 정보를 확인하지 못했습니다.";
function Failure({ code }: { code: string | null }) {
  return code ? <p className="small job-failure">{errors[code] || "서버 기록으로 전달 실패 원인을 확인해 주세요."}<code>{code}</code></p> : null;
}

export function JobEventsPanel({ jobId, jobState, environmentLabel, auto, paused, onBusyChange, onDialogChange }: {
  jobId: string; jobState: string; environmentLabel: string; auto: boolean; paused: boolean;
  onBusyChange: (busy: boolean) => void; onDialogChange: (open: boolean) => void;
}) {
  const [rows, setRows] = useState<EventDetail[] | null>(null);
  const [loading, setLoading] = useState(true), [reload, setReload] = useState(0);
  const [error, setError] = useState(""), [actionError, setActionError] = useState("");
  const [confirm, setConfirm] = useState<Delivery | null>(null), [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const live = useRef(true), locked = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    if (paused || confirm || busy) return;
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    setLoading(true); setError("");
    async function load() {
      if (document.hidden) { timer = setTimeout(load, 5000); return; }
      try {
        const result = await api<EventDetail[]>(`/jobs/${jobId}/events`);
        if (!current) return;
        setRows(result); setError("");
        if (auto && (result.some(row => row.delivery.state === "PENDING") || ["QUEUED", "RUNNING", "RETRY_WAIT"].includes(jobState)))
          timer = setTimeout(load, 5000);
      } catch (e) { if (current) { setError(message(e)); setRows(null); } }
      finally { if (current) setLoading(false); }
    }
    void load();
    return () => { current = false; clearTimeout(timer); };
  }, [jobId, jobState, auto, paused, confirm, busy, reload]);
  function close() {
    setConfirm(null); onDialogChange(false); setReload(n => n + 1);
    requestAnimationFrame(() => heading.current?.focus());
  }
  async function retry() {
    if (!confirm || locked.current || actionError) return;
    locked.current = true; setBusy(true); onBusyChange(true);
    try {
      const result = await api<Delivery>(`/events/${confirm.id}/retry`, "POST");
      if (live.current) {
        setNotice(result.state === "DELIVERED" ? "이벤트 전달이 완료됐습니다." : "재전송을 접수했습니다. 아래에서 전달 결과를 확인하세요.");
        close();
      }
    } catch (e) { if (live.current) setActionError(message(e) + " 닫은 뒤 전달 상태를 다시 확인해 주세요."); }
    finally { locked.current = false; onBusyChange(false); if (live.current) setBusy(false); }
  }
  return <section className="job-events" aria-labelledby="job-events-title">
    <div className="section-line"><h4 id="job-events-title" ref={heading} tabIndex={-1}>이벤트 전달</h4>
      <button className="secondary" disabled={paused || busy || loading} onClick={() => setReload(n => n + 1)}>전달 상태 새로고침</button></div>
    <p className="small muted">작업 결과를 알림 서비스에 전달한 기록입니다. 전달 완료는 수신 기록 저장을 뜻하며, 외부 이메일 발송 결과는 아닙니다.</p>
    {loading && <p role="status">전달 상태를 확인하는 중…</p>}
    {error && <p className="alert" role="alert">{error} 전달 상태 새로고침으로 다시 확인해 주세요.</p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    {rows?.length === 0 && <p className="empty">아직 전달할 이벤트가 없습니다. 작업이 완료·최종 실패·취소되면 생성됩니다.</p>}
    {rows?.map(({ delivery, attempts }) => <article className="job-event" key={delivery.id}>
      <div className="section-line"><strong>{types[delivery.eventType] || delivery.eventType}</strong>
        <span className={`job-state job-${delivery.state === "DELIVERED" ? "succeeded" : delivery.state === "FAILED" ? "failed" : "running"}`} role="status">
          {delivery.state === "DELIVERED" ? "전달 완료" : delivery.state === "FAILED" ? "전달 최종 실패" : attempts[0]?.state === "RUNNING" ? "전달 중" : "전달 대기"}
        </span></div>
      <Failure code={delivery.errorCode} />
      <dl><div><dt>이벤트 ID</dt><dd className="identifier">{delivery.id}</dd></div>
        <div><dt>전달 시도</dt><dd>이번 접수 {delivery.cycleAttempts}/5회 · 누적 {delivery.attempts}회</dd></div>
        <div><dt>전달 완료 시각</dt><dd>{date(delivery.deliveredAt)}</dd></div>
        {delivery.state === "PENDING" && attempts[0]?.state !== "RUNNING" && <div><dt>다음 시도 예정</dt><dd>{date(delivery.nextRunAt)}</dd></div>}
      </dl>
      {delivery.state === "PENDING" && <p className="small muted">전달 워커가 실행 중이면 자동으로 처리합니다. 대기가 계속되면 서버의 전달 설정과 연결 상태를 확인하세요.</p>}
      {delivery.state === "FAILED" && <button disabled={paused || busy || loading || !!error}
        onClick={() => { setActionError(""); setConfirm(delivery); onDialogChange(true); }}>실패 이벤트 재전송</button>}
      <details className="job-event-history"><summary>전달 시도 이력 · 최근 {attempts.length}건</summary>
        <p className="small muted">최신순으로 최대 100건을 표시합니다.</p>
        {attempts.length ? <ol className="job-attempts">{attempts.map(attempt => <li key={attempt.attempt}>
          <div className="section-line"><strong>누적 {attempt.attempt}차 전달</strong><span>{attemptStates[attempt.state] || attempt.state}</span></div>
          <p className="small muted">시작 {date(attempt.startedAt)}<br />종료 {date(attempt.endedAt)}{attempt.httpStatus !== null && <><br />HTTP {attempt.httpStatus}</>}</p>
          <Failure code={attempt.errorCode} />
        </li>)}</ol> : <p className="empty">아직 전달을 시도하지 않았습니다.</p>}
      </details>
    </article>)}
    {confirm && <Dialog title="실패 이벤트 재전송" busy={busy} close={close}>
      <p><strong>{environmentLabel}</strong></p><p className="identifier">이벤트 {confirm.id}</p>
      <p>이 작업의 결과 이벤트를 알림 서비스에 다시 전달합니다. 환경 설정 작업을 다시 실행하지 않습니다.</p>
      <p className="warning">원인을 해결한 뒤 진행하세요. 같은 이벤트 ID와 기존 이력을 유지하며 최대 5회 전달을 시도합니다. 이미 수신한 이벤트는 중복 처리하지 않습니다.</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={close}>돌아가기</button>
        <button disabled={busy || !!actionError} onClick={() => void retry()}>{busy ? "접수 중…" : "재전송 접수"}</button></div>
    </Dialog>}
  </section>;
}
