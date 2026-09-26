import {Icon} from '../../shared/Icon';
import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/auth";
import { Dialog } from "../../shared/Dialog";

type Settings = { enabled: boolean; thresholdSeconds: number; consecutiveChecks: number; revision: number;
  updatedAt: string | null; updatedBy: string | null; requestId: string | null; lastCheckedAt: string | null;
  breachChecks: number; clearChecks: number; activeJobId: string | null; activeEventId: string | null; monitoringAvailable: boolean };
const date = (value: string | null) => value ? new Date(value).toLocaleString("ko-KR") : "아직 검사하지 않음";
const message = (e: unknown) => e instanceof Error ? e.message : "적체 경보 설정을 확인하지 못했습니다.";
export function JobBacklogSettings({ environmentId, environmentLabel, disabled, onBusyChange, openAlerts }: {
  environmentId: string; environmentLabel: string; disabled: boolean; onBusyChange: (value: boolean) => void; openAlerts: () => void;
}) {
  const [saved, setSaved] = useState<Settings | null>(null), [draft, setDraft] = useState<Settings | null>(null);
  const [loading, setLoading] = useState(true), [reload, setReload] = useState(0), [busy, setBusy] = useState(false);
  const [error, setError] = useState(""), [notice, setNotice] = useState(""), [actionError, setActionError] = useState("");
  const [confirm, setConfirm] = useState(false), live = useRef(true), lock = useRef(false), submit = useRef<HTMLButtonElement>(null);
  const refresh = useRef<HTMLButtonElement>(null);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    let current = true; setLoading(true); setSaved(null); setDraft(null); setError("");
    api<Settings>(`/environments/${environmentId}/job-backlog-settings`).then(value => { if (current) { setSaved(value); setDraft(value); setActionError(""); } })
      .catch(e => { if (current) setError(message(e)); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [environmentId, reload]);
  const changed = saved && draft && (saved.enabled !== draft.enabled || saved.thresholdSeconds !== draft.thresholdSeconds || saved.consecutiveChecks !== draft.consecutiveChecks);
  function close() { setConfirm(false); requestAnimationFrame(() => (submit.current && !submit.current.disabled ? submit.current : refresh.current)?.focus()); }
  async function save() {
    if (!draft || lock.current || actionError) return;
    lock.current = true; setBusy(true); onBusyChange(true);
    try {
      const value = await api<Settings>(`/environments/${environmentId}/job-backlog-settings`, "PUT", {
        enabled: draft.enabled, thresholdSeconds: draft.thresholdSeconds, consecutiveChecks: draft.consecutiveChecks, revision: draft.revision,
      });
      if (live.current) { setSaved(value); setDraft(value); setNotice("적체 경보 설정을 저장했습니다. 연속 검사 횟수를 새로 셉니다."); close(); }
    } catch (e) { if (live.current) setActionError(message(e) + " 닫은 뒤 설정 새로고침으로 저장 여부를 확인해 주세요."); }
    finally { lock.current = false; onBusyChange(false); if (live.current) setBusy(false); }
  }
  return <section className="job-panel" aria-labelledby="backlog-settings-title">
    <div className="section-line"><h3 id="backlog-settings-title">작업 대기 적체 경보</h3><button ref={refresh} className="secondary" disabled={disabled || loading || busy || confirm}
      onClick={() => { setNotice(""); setReload(v => v + 1); }} aria-label="설정 새로고침" title="설정 새로고침" data-tooltip="설정 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button></div>
    <p className="small muted">{environmentLabel}의 환경 반영 작업을 감시합니다. 재시도 예약 시간이 남은 작업과 실행 중 작업은 제외합니다.</p>
    {loading && <p role="status">적체 경보 설정을 불러오는 중…</p>}{error && <p className="alert" role="alert">{error} 설정 새로고침으로 다시 확인해 주세요.</p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    {!confirm && actionError && <p className="alert" role="alert">{actionError}</p>}
    {saved && draft && <>
      {!saved.monitoringAvailable && <p className="warning">서버의 이벤트 전달이 꺼져 있어 감시가 실행되지 않습니다. 서버 설정을 확인해 주세요.</p>}
      <p className="small">저장된 감시: <strong>{saved.enabled ? saved.activeEventId ? "적체 감지됨" : "켜짐" : "꺼짐"}</strong> · 마지막 검사 {date(saved.lastCheckedAt)}</p>
      {saved.enabled && <p className="small muted">조회 당시 연속 적체 {saved.breachChecks}회 · 연속 해소 {saved.clearChecks}회. 검사 결과는 새로고침으로 확인합니다.</p>}
      <form className="alert-email-form" onSubmit={e => { e.preventDefault(); if (!actionError) setConfirm(true); }}>
        <fieldset disabled={disabled || busy || confirm}>
          <label className="checkbox"><input type="checkbox" checked={draft.enabled} disabled={!saved.monitoringAvailable && !draft.enabled}
            onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />작업 대기 적체 감시</label>
          <label>대기 시간 기준 (초)<input type="number" min="60" max="86400" step="1" required value={draft.thresholdSeconds || ""}
            onChange={e => setDraft({ ...draft, thresholdSeconds: Number(e.target.value) })} aria-describedby="backlog-threshold-help" /></label>
          <p id="backlog-threshold-help" className="small muted">60~86,400초. 기본 300초(5분). 현재 대기 상태가 시작된 시점부터 계산합니다.</p>
          <label>연속 확인 횟수<input type="number" min="1" max="10" step="1" required value={draft.consecutiveChecks || ""}
            onChange={e => setDraft({ ...draft, consecutiveChecks: Number(e.target.value) })} aria-describedby="backlog-checks-help" /></label>
          <p id="backlog-checks-help" className="small muted">1~10회, 최소 30초 간격으로 확인합니다. 기준 이상 대기가 연속되면 한 번 알리고, 해당 작업이 기준에서 벗어난 것도 같은 횟수만큼 확인하면 해소로 기록합니다.</p>
        </fieldset>
        <p className="small muted">검사가 90초 넘게 끊기면 연속 횟수를 새로 셉니다. 해소는 대기 적체가 풀렸다는 뜻이며 작업 성공을 보장하지 않습니다.</p>
        <p className="small muted">이메일은 운영 알림의 수신 설정을 따릅니다. 감시만 켜면 알림 목록에 기록되며, 이메일 수신을 켠 경우에만 발송합니다. 개발 환경에서는 모의 발송합니다.</p>
        <div className="form-actions"><button className="secondary" type="button" disabled={disabled || busy || confirm} onClick={openAlerts}><Icon name="bell"/>운영 알림 보기</button>
          <button ref={submit} disabled={disabled || busy || !changed || !!actionError}><Icon name="save"/>적체 경보 설정 저장</button></div>
        {changed && <p className="small muted" role="status">저장하지 않은 변경이 있습니다. 이동하거나 새로고침하면 사라집니다.</p>}
      </form>
      {saved.updatedAt && <details className="job-event-history"><summary>최근 설정 변경 기록</summary><dl>
        <div><dt>변경 시각</dt><dd>{date(saved.updatedAt)}</dd></div><div><dt>관리자 ID</dt><dd className="identifier">{saved.updatedBy}</dd></div>
        <div><dt>요청 ID</dt><dd className="identifier">{saved.requestId}</dd></div><div><dt>설정 버전</dt><dd>{saved.revision}</dd></div>
      </dl></details>}
    </>}
    {confirm && draft && <Dialog title="적체 경보 설정 저장" busy={busy} close={close}>
      <p><strong>{environmentLabel}</strong></p><p>{draft.enabled ? `${draft.thresholdSeconds}초 이상 대기를 ${draft.consecutiveChecks}회 연속 확인하면 알립니다.` : "적체 감시를 끕니다."}</p>
      <p>현재 열린 적체는 ‘감시 종료’로 남기고 연속 횟수를 초기화합니다. 실제 복구로 표시하거나 복구 이메일을 보내지 않습니다. 이미 발송 중인 이메일은 회수할 수 없습니다.</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={close}><Icon name="arrow-left"/>돌아가기</button>
        <button disabled={busy || !!actionError} onClick={() => void save()}><Icon name="save"/>{busy ? "저장 중…" : "설정 저장"}</button></div>
    </Dialog>}
  </section>;
}
