import { useEffect, useRef, useState } from "react";
import { api } from "./auth";
import { Dialog } from "./Dialog";

type Target = { id: string; username: string; provider: string };
type Preview = { items: Target[]; hasMore: boolean; revision: string };
type Result = { requested: number; deleted: number; failed: number;
  items: { id: string; username: string; status: "DELETED" | "FAILED" }[] };
const providers: Record<string, string> = { google: "구글", kakao: "카카오", naver: "네이버" };
const errorText = (error: unknown) => error instanceof Error ? error.message : "요청을 처리하지 못했습니다.";

export function MockResetPanel({ environmentId, environmentLabel, disabled, onBusyChange, onReset }: {
  environmentId: string; environmentLabel: string; disabled: boolean;
  onBusyChange: (busy: boolean) => void; onReset: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const live = useRef(true);
  const trigger = useRef<HTMLButtonElement>(null);
  const hadPreview = useRef(false);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => {
    // Preview loading disables the opener, so the native dialog cannot remember it.
    if (!preview && hadPreview.current) trigger.current?.focus();
    hadPreview.current = preview !== null;
  }, [preview]);
  const path = `/environments/${environmentId}/mock-users`;
  function loading(value: boolean) { setBusy(value); onBusyChange(value); }
  async function inspect() {
    if (busy || disabled) return;
    loading(true); setError(""); setResult(null); setConfirmed(false);
    try {
      const next = await api<Preview>(`${path}/reset-preview`);
      if (live.current) setPreview(next);
    } catch (e) { if (live.current) setError(errorText(e)); }
    finally { if (live.current) loading(false); }
  }
  async function reset() {
    if (!preview || !preview.items.length || !confirmed || busy || disabled || error) return;
    loading(true);
    try {
      const next = await api<Result>(`${path}/reset`, "POST", {
        revision: preview.revision, userIds: preview.items.map(item => item.id),
      });
      if (live.current) { setResult(next); setPreview(null); onReset(); }
    } catch (e) {
      if (live.current) {
        setError(`${errorText(e)} 일부 처리가 반영되었을 수 있습니다. 닫은 뒤 대상을 다시 확인하고 감사 이력을 확인해 주세요.`);
        onReset();
      }
    } finally { if (live.current) loading(false); }
  }
  return <div className="mock-reset">
    <h4>DEV 테스트 계정 초기화</h4>
    <p className="small muted">개발 로그인으로 만든 테스트 계정과 세션을 삭제합니다. 일반 회원·운영 계정·관리 권한이 있는 계정은 제외합니다.</p>
    <button ref={trigger} type="button" className="secondary danger" disabled={busy || disabled} onClick={inspect}>
      {busy ? "처리 중…" : "초기화 대상 확인"}
    </button>
    {!preview && error && <p className="alert" role="alert">{error}</p>}
    {result && <div className="mock-result" role="status">
      <h4>테스트 계정 초기화 결과</h4>
      <p>요청 {result.requested}명 · 삭제 {result.deleted}명 · 실패 {result.failed}명</p>
      {result.failed > 0 && <p className="warning">실패한 계정은 비활성화되거나 일부 세션이 종료되었을 수 있습니다. 대상을 다시 확인한 뒤 재시도하세요.</p>}
      <ul className="key-list">{result.items.map(item => <li key={item.id}>
        <div><span className="identifier">{item.username}</span><p className="identifier">{item.id}</p></div>
        <strong>{item.status === "DELETED" ? "삭제 완료" : "처리 실패"}</strong>
      </li>)}</ul>
      <p className="hint">처리 이력은 감사 이력에서 확인할 수 있습니다. 남은 테스트 계정은 대상을 다시 확인해 초기화하세요.</p>
    </div>}
    {preview && <Dialog title="DEV 테스트 계정 삭제 확인" busy={busy} close={() => {
      setPreview(null); setError(""); setConfirmed(false);
    }}>
      <p><strong>{environmentLabel}</strong></p>
      <p>이번 삭제 대상 <strong>{preview.items.length}명</strong>{preview.hasMore ? " · 추가 대상 있음" : ""}</p>
      <p className="small muted">한 번에 최대 20명을 확인하고 삭제합니다. 현재 목록에 표시된 계정만 처리합니다.</p>
      {preview.items.length ? <>
        <ul className="key-list mock-reset-targets">{preview.items.map(item => <li key={item.id}>
          <div><strong>{providers[item.provider] || item.provider} 테스트 계정</strong>
            <p className="identifier">{item.username}</p><p className="identifier">{item.id}</p></div>
        </li>)}</ul>
        <p className="warning">계정과 로그인 세션을 삭제하며 되돌릴 수 없습니다. 같은 테스트 ID로 다시 로그인하면 새 계정이 만들어집니다.</p>
        <p className="hint">연동 서비스가 자체 검증하는 기존 접근 토큰은 만료 전까지 유효할 수 있습니다.</p>
        <label className="checkbox"><input type="checkbox" checked={confirmed} disabled={busy || !!error}
          onChange={e => setConfirmed(e.target.checked)} />표시된 DEV 테스트 계정 삭제를 확인했습니다.</label>
      </> : <p className="empty">초기화할 테스트 계정이 없습니다. 일반 회원이나 보호 대상 계정은 삭제하지 않습니다.</p>}
      {error && <p className="alert" role="alert">{error}</p>}
      <div className="form-actions">
        <button type="button" className="secondary" disabled={busy} onClick={() => { setPreview(null); setError(""); }}>닫기</button>
        {preview.items.length > 0 && <button type="button" className="destructive" disabled={busy || !confirmed || !!error || disabled}
          onClick={reset}>{busy ? "삭제 중…" : `${preview.items.length}명 삭제`}</button>}
      </div>
    </Dialog>}
  </div>;
}
