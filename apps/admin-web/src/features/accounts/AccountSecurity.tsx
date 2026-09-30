import { useEffect, useState } from "react";
import { auth } from "../../shared/auth";
import { Dialog } from "../../shared/Dialog";

const returnKey = "platform-account-security";
type CredentialGroup = {
  type: string;
  userCredentialMetadatas?: { credential: { id: string; userLabel?: string } }[];
};

export function resumeAccountSecurity() {
  try { return sessionStorage.getItem(returnKey) !== null; } catch { return false; }
}

export function AccountSecurity({ close }: { close: () => void }) {
  const [groups, setGroups] = useState<CredentialGroup[] | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState(() => {
    try { return sessionStorage.getItem(returnKey) === "disable"; } catch { return false; }
  });
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        await auth.updateToken(30);
        const response = await fetch(`${auth.authServerUrl}/realms/${encodeURIComponent(auth.realm!)}/account/credentials`, {
          headers: { Authorization: `Bearer ${auth.token}`, Accept: "application/json" },
          cache: "no-store", signal: controller.signal,
        });
        if (!response.ok) throw new Error("인증 설정을 불러오지 못했습니다. 닫은 뒤 다시 시도해 주세요.");
        const result: CredentialGroup[] = await response.json();
        if (!Array.isArray(result) || !result.some(group => group.type === "otp"))
          throw new Error("인증 앱 설정을 확인하지 못했습니다. 계정 관리에서 확인해 주세요.");
        if (!controller.signal.aborted) setGroups(result);
      } catch (cause) {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "인증 설정을 불러오지 못했습니다.");
      }
    }
    void load();
    return () => controller.abort();
  }, []);
  const factors = (groups ?? [])
    .filter(group => group.type === "otp" || group.type === "recovery-authn-codes")
    .flatMap(group => (group.userCredentialMetadatas ?? []).map(({ credential }) => ({ ...credential, type: group.type })))
    .sort((a, b) => Number(a.type === "otp") - Number(b.type === "otp"));
  const enabled = factors.length > 0;
  async function change(action: string, intent: "enable" | "disable") {
    setBusy(true);
    setError("");
    try {
      try { sessionStorage.setItem(returnKey, intent); } catch { /* Navigation also works without storage. */ }
      await auth.login({ action, redirectUri: location.origin + "/" });
    } catch {
      setBusy(false);
      setError("인증 화면을 열지 못했습니다. 다시 시도해 주세요.");
    }
  }
  function dismiss() {
    try { sessionStorage.removeItem(returnKey); } catch { /* Storage is optional. */ }
    close();
  }
  return <Dialog title="내 계정 · 계정 보안" close={dismiss} busy={busy}>
    <p>로그인할 때 비밀번호와 함께 인증 앱의 일회용 코드를 사용할 수 있습니다.</p>
    {groups === null && !error && <p role="status">인증 설정을 불러오는 중입니다…</p>}
    {error && <p role="alert">{error}</p>}
    <label className="checkbox">
      <input type="checkbox" checked={enabled} disabled={groups === null || busy} aria-describedby="otp-help"
        onChange={() => {
          if (!enabled) void change("CONFIGURE_TOTP", "enable");
          else if (factors.length === 1) void change(`delete_credential:${factors[0].id}`, "disable");
          else setRemoving(true);
        }} />
      OTP 사용
    </label>
    <p id="otp-help" className="hint">{groups === null ? "설정을 확인한 뒤 변경할 수 있습니다."
      : enabled ? "사용 중입니다. 인증 앱 또는 복구 코드로 2단계 인증을 완료해야 로그인할 수 있습니다."
      : "사용하지 않습니다. 체크한 뒤 인증 앱 등록을 완료하면 다음 로그인부터 적용됩니다."}</p>
    {removing && enabled && <section aria-label="OTP 사용 해제">
      <h3>등록한 인증 수단 제거</h3>
      <p>아래 인증 수단을 모두 제거하면 OTP 사용이 꺼집니다. 각 항목은 본인 확인 후 제거하며, 취소하거나 남겨 두면 2단계 인증을 계속 사용합니다.</p>
      {factors.map(factor => <p key={factor.id}>
        <button className="quiet" disabled={busy} onClick={() => void change(`delete_credential:${factor.id}`, "disable")}>
          {factor.type === "otp" ? `인증 앱${factor.userLabel ? ` (${factor.userLabel})` : ""} 제거` : "복구 코드 제거"}
        </button>
      </p>)}
    </section>}
    <p className="hint">취소하면 해당 단계는 적용되지 않으며 앞서 완료한 변경은 유지됩니다. 비밀번호 변경과 기기별 관리는 계정 관리에서 할 수 있습니다.</p>
    <div className="actions">
      <button className="quiet" disabled={busy} onClick={() => void auth.accountManagement()}>계정 관리 열기</button>
      <button className="quiet" disabled={busy} onClick={dismiss}>닫기</button>
    </div>
  </Dialog>;
}
