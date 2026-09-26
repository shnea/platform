import {Icon} from '../../shared/Icon';
import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/auth";
import { Dialog } from "../../shared/Dialog";
import { OperationalAlertsPanel } from "./OperationalAlertsPanel";
import { SectionTabs } from "../../shared/SectionTabs";
import { alertTitles } from "./alertLabels";

type Props = { environmentId: string; environmentLabel: string; disabled: boolean; onBusyChange: (busy: boolean) => void };
type Settings = { enabled: boolean; recipient: string; suppressionMinutes: number; recoveryEnabled: boolean; revision: number;
  deliveryMode: string; updatedAt: string | null; updatedBy: string | null; requestId: string | null };
type Delivery = { eventId: string; code: string; recipient: string; state: string; createdAt: string; startedAt: string | null;
  finishedAt: string | null; providerId: string | null };
const date = (value: string) => new Date(value).toLocaleString("ko-KR");
const message = (e: unknown) => e instanceof Error ? e.message : "알림 이메일 정보를 확인하지 못했습니다.";
const sections = [{ value: "list", label: "알림 목록" }, { value: "settings", label: "수신 설정" }, { value: "deliveries", label: "발송 이력" }] as const;
const states: Record<string, string> = { PENDING: "발송 대기", SENDING: "발송 중", MOCK: "모의 발송", ACCEPTED: "발송사 접수",
  FAILED: "발송 실패", UNKNOWN: "결과 확인 필요", CANCELLED: "설정 변경으로 취소", BLOCKED: "발송 경로 차단" };

export function AlertWorkspace(props: Props) {
  const [tab, setTab] = useState<typeof sections[number]["value"]>("list");
  return <><SectionTabs id="alert-section" label="운영 알림 항목" items={sections} value={tab} disabled={props.disabled} onChange={setTab} />
    <div id="alert-section-panel" role="tabpanel" aria-labelledby={`alert-section-${tab}`}>
      {tab === "list" ? <OperationalAlertsPanel {...props} /> : tab === "settings" ? <EmailSettings {...props} /> : <EmailDeliveries {...props} />}
    </div></>;
}
function EmailSettings({ environmentId, environmentLabel, disabled, onBusyChange }: Props) {
  const [saved, setSaved] = useState<Settings | null>(null), [draft, setDraft] = useState<Settings | null>(null);
  const [reload, setReload] = useState(0), [loading, setLoading] = useState(true), [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false), [error, setError] = useState(""), [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const live = useRef(true), lock = useRef(false), submit = useRef<HTMLButtonElement>(null);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    let current = true; setLoading(true); setSaved(null); setDraft(null); setError("");
    api<Settings>(`/environments/${environmentId}/operational-alerts/email-settings`).then(value => {
      if (current) { setSaved(value); setDraft(value); }
    }).catch(e => { if (current) setError(message(e)); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [environmentId, reload]);
  function close() { setConfirm(false); requestAnimationFrame(() => submit.current?.focus()); }
  async function save() {
    if (!draft || lock.current || actionError) return;
    lock.current = true; setBusy(true); onBusyChange(true);
    try {
      const result = await api<Settings>(`/environments/${environmentId}/operational-alerts/email-settings`, "PUT", {
        enabled: draft.enabled, recipient: draft.recipient.trim(), suppressionMinutes: draft.suppressionMinutes,
        recoveryEnabled: draft.recoveryEnabled, revision: draft.revision,
      });
      if (live.current) { setSaved(result); setDraft(result); setNotice("수신 설정을 저장했습니다. 이후 새로 발생하는 알림부터 적용합니다."); close(); }
    } catch (e) { if (live.current) setActionError(message(e) + " 닫은 뒤 설정 새로고침으로 저장 여부를 확인해 주세요."); }
    finally { lock.current = false; onBusyChange(false); if (live.current) setBusy(false); }
  }
  const changed = saved && draft && (saved.enabled !== draft.enabled || saved.recipient !== draft.recipient ||
    saved.suppressionMinutes !== draft.suppressionMinutes || saved.recoveryEnabled !== draft.recoveryEnabled);
  return <section className="job-panel" aria-labelledby="alert-settings-title">
    <div className="section-line"><h3 id="alert-settings-title">이메일 수신 설정</h3><button className="secondary" disabled={disabled || loading || busy}
      onClick={() => { setNotice(""); setReload(v => v + 1); }} aria-label="설정 새로고침" title="설정 새로고침" data-tooltip="설정 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button></div>
    <p className="small muted">{environmentLabel}의 환경 반영 작업 실패·복구 알림을 받을 주소입니다. 알림 목록은 이메일 사용 여부와 관계없이 보관합니다.</p>
    {notice && <p className="notice" role="status">{notice}</p>}{error && <p className="alert" role="alert">{error} 설정 새로고침으로 다시 확인해 주세요.</p>}
    {loading && <p role="status">수신 설정을 불러오는 중…</p>}
    {draft && <form className="alert-email-form" onSubmit={e => { e.preventDefault(); setActionError(""); setConfirm(true); }}>
      <p className={draft.deliveryMode === "BLOCKED" ? "warning" : "notice"}>
        {draft.deliveryMode === "MOCK" ? "개발 환경: 외부 이메일을 보내지 않습니다. 결과는 발송 이력에 모의 발송으로 남습니다."
          : draft.deliveryMode === "NCP" ? "운영 환경: 켜면 지정한 주소로 NCP 이메일을 보냅니다."
          : "이 환경에서 사용할 발송 경로가 준비되지 않았습니다. 이메일 수신을 켤 수 없습니다."}</p>
      <fieldset disabled={disabled || busy}>
        <label className="checkbox"><input type="checkbox" checked={draft.enabled} disabled={draft.deliveryMode === "BLOCKED" && !draft.enabled}
          onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />이메일 알림 받기</label>
        <label>수신 이메일<input type="email" maxLength={320} required={draft.enabled} value={draft.recipient}
          placeholder="ops@example.com" autoComplete="off" onChange={e => setDraft({ ...draft, recipient: e.target.value })} /></label>
        <label>같은 실패의 이메일 간격 (분)<input type="number" min={1} max={1440} required value={Number.isFinite(draft.suppressionMinutes) ? draft.suppressionMinutes : ""}
          onChange={e => setDraft({ ...draft, suppressionMinutes: e.target.valueAsNumber })} aria-describedby="alert-suppression-help" /></label>
        <p id="alert-suppression-help" className="small muted">1~1,440분. 같은 환경·종류의 실패 또는 적체가 반복되면 이 시간 동안 이메일을 생략합니다. 해소 후 다시 발생하면 새로 알립니다.</p>
        <label className="checkbox"><input type="checkbox" checked={draft.recoveryEnabled} onChange={e => setDraft({ ...draft, recoveryEnabled: e.target.checked })} />복구 이메일도 받기</label>
        <p className="small muted">환경 반영 성공과 대기 적체 해소를 각각 알립니다. 알림 확인 처리와 감시 설정 변경은 실제 복구가 아닙니다.</p>
      </fieldset>
      {saved?.updatedAt && <details className="job-event-history"><summary>최근 설정 변경 기록</summary><dl>
        <div><dt>변경 시각</dt><dd>{date(saved.updatedAt)}</dd></div><div><dt>관리자 ID</dt><dd className="identifier">{saved.updatedBy}</dd></div>
        <div><dt>요청 ID</dt><dd className="identifier">{saved.requestId}</dd></div><div><dt>설정 버전</dt><dd>{saved.revision}</dd></div>
      </dl></details>}
      <div className="form-actions"><button ref={submit} disabled={disabled || busy || !changed}><Icon name="save"/>수신 설정 저장</button></div>
      {changed && <p className="small muted" role="status">저장하지 않은 변경이 있습니다. 다른 화면으로 이동하면 사라집니다.</p>}
    </form>}
    {confirm && draft && <Dialog title="이메일 수신 설정 저장" busy={busy} close={close}>
      <p><strong>{environmentLabel}</strong></p><p>{draft.enabled ? `수신 주소: ${draft.recipient}` : "이메일 알림을 끕니다."}</p>
      {draft.enabled && <p>같은 실패는 {draft.suppressionMinutes}분 간격으로 알리고, 복구 이메일은 {draft.recoveryEnabled ? "받습니다" : "받지 않습니다"}.</p>}
      <p>{draft.deliveryMode === "BLOCKED" ? "발송 경로가 준비되지 않아 이메일을 보내지 않습니다."
        : !draft.enabled ? "저장 후 새 알림의 이메일 발송을 중지합니다."
        : draft.deliveryMode === "MOCK" ? "개발 환경에서는 모의 발송만 기록합니다." : "운영 환경에서는 설정한 주소로 이메일이 발송됩니다."}</p>
      <p className="small muted">저장하면 기존 발송 대기는 취소합니다. 이미 발송 중인 이메일은 취소할 수 없으며, 과거 알림을 다시 보내지 않습니다.</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={close}><Icon name="arrow-left"/>돌아가기</button>
        <button disabled={busy || !!actionError} onClick={() => void save()}><Icon name="save"/>{busy ? "저장 중…" : "설정 저장"}</button></div>
    </Dialog>}
  </section>;
}
function EmailDeliveries({ environmentId, disabled }: Props) {
  const [query, setQuery] = useState({ offset: 0, reload: 0 });
  const [rows, setRows] = useState<Delivery[] | null>(null), [error, setError] = useState(""), [loading, setLoading] = useState(true);
  useEffect(() => {
    let current = true; setLoading(true); setRows(null); setError("");
    api<Delivery[]>(`/environments/${environmentId}/operational-alerts/email-deliveries?limit=21&offset=${query.offset}`).then(value => {
      if (current) setRows(value);
    }).catch(e => { if (current) setError(message(e)); }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [environmentId, query]);
  return <section className="job-panel" aria-labelledby="alert-deliveries-title">
    <div className="section-line"><h3 id="alert-deliveries-title">이메일 발송 이력</h3><button className="secondary" disabled={disabled || loading}
      onClick={() => setQuery(q => ({ ...q, reload: q.reload + 1 }))} aria-label="발송 이력 새로고침" title="발송 이력 새로고침" data-tooltip="발송 이력 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button></div>
    <p className="small muted">발송사 접수는 수신함 도착을 보장하지 않습니다. 결과가 불확실한 이메일은 중복 발송을 막기 위해 자동 재전송하지 않습니다.</p>
    {loading && <p role="status">발송 이력을 불러오는 중…</p>}{error && <p className="alert" role="alert">{error} 새로고침으로 다시 확인해 주세요.</p>}
    {rows && (rows.length ? <ul className="job-attempts">{rows.slice(0, 20).map(row => <li key={row.eventId}>
      <div className="section-line"><strong>{alertTitles[row.code] || row.code}</strong>
        <span className={`job-state ${["FAILED", "UNKNOWN", "BLOCKED"].includes(row.state) ? "job-failed" : ""}`}>{states[row.state] || row.state}</span></div>
      <p className="identifier">{row.recipient}</p><p className="small muted">접수 {date(row.createdAt)}{row.finishedAt && ` · 처리 ${date(row.finishedAt)}`}</p>
      <details className="job-event-history"><summary>발송 상세</summary><dl>
        <div><dt>알림 ID</dt><dd className="identifier">{row.eventId}</dd></div>
        {row.startedAt && <div><dt>처리 시작</dt><dd>{date(row.startedAt)}</dd></div>}
        {row.providerId && <div><dt>발송사 요청 ID</dt><dd className="identifier">{row.providerId}</dd></div>}
      </dl></details>
    </li>)}</ul> : <p className="empty">발송 이력이 없습니다. 수신 설정을 켠 뒤 발생한 알림부터 기록합니다.</p>)}
    <div className="pagination" aria-label="발송 이력 페이지"><button className="secondary" disabled={disabled || loading || !query.offset}
      onClick={() => setQuery(q => ({ ...q, offset: q.offset - 20 }))}><Icon name="chevron-left"/>이전 발송</button><span className="small">페이지 {query.offset / 20 + 1}</span>
      <button className="secondary" disabled={disabled || loading || !rows || rows.length <= 20}
        onClick={() => setQuery(q => ({ ...q, offset: q.offset + 20 }))}><Icon name="chevron-right"/>다음 발송</button></div>
  </section>;
}
