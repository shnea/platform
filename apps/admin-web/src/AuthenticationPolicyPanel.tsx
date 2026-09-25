import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "./auth";
import { EmailInbox } from "./EmailInbox";

type Policy = {
  loginWithEmail: boolean;
  verifyEmail: boolean;
  resetPasswordAllowed: boolean;
  passwordMinLength: number;
  passwordPolicyEditable: boolean;
  emailActionsAvailable: boolean;
  emailDelivery: "MOCK" | "NCP" | "UNAVAILABLE";
  revision: string;
};

export function AuthenticationPolicyPanel({
  environmentId,
  ready,
}: {
  environmentId: string;
  ready: boolean;
}) {
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [reload, setReload] = useState(0);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (!ready) return;
    let active = true;
    setLoading(true);
    setPolicy(null);
    setError("");
    api<Policy>(`/environments/${environmentId}/authentication-policy`)
      .then((result) => {
        if (active) setPolicy(result);
      })
      .catch((e) => {
        if (active)
          setError(
            e instanceof Error ? e.message : "정책을 불러오지 못했습니다.",
          );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [environmentId, ready, reload]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!policy) return;
    const data = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await api<Policy>(
        `/environments/${environmentId}/authentication-policy`,
        "PUT",
        {
          loginWithEmail: data.get("loginWithEmail") === "on",
          verifyEmail: data.get("verifyEmail") === "on",
          resetPasswordAllowed: data.get("resetPasswordAllowed") === "on",
          passwordMinLength: Number(data.get("passwordMinLength")),
          revision: policy.revision,
        },
      );
      if (live.current) {
        setPolicy(result);
        setMessage("가입·복구 정책을 저장했습니다.");
      }
    } catch (e) {
      if (live.current)
        setError(
          e instanceof Error ? e.message : "정책을 저장하지 못했습니다.",
        );
    } finally {
      if (live.current) setBusy(false);
    }
  }

  return (
    <section className="policy-panel" aria-labelledby="policy-title">
      <div className="section-line">
        <h3 id="policy-title">가입·계정 복구</h3>
        <button
          type="button"
          className="secondary"
          disabled={!ready || loading || busy}
          onClick={() => {
            setMessage("");
            setReload((value) => value + 1);
          }}
        >
          정책 새로고침
        </button>
      </div>
      <p className="small muted">
        일반 회원가입 허용은 위쪽 로그인 설정에서 관리합니다. 아래 정책은 현재
        환경의 계정에만 적용됩니다.
      </p>
      {!ready && (
        <p className="warning">
          로그인 설정을 먼저 반영한 뒤 정책을 관리할 수 있습니다.
        </p>
      )}
      {ready && loading && <p role="status">가입·복구 정책을 불러오는 중…</p>}
      {error && (
        <p role="alert" className="alert">
          {error} 저장 결과가 불확실하면 정책을 새로고침해 확인하세요.
        </p>
      )}
      {ready && policy && (
        <>
          {!policy.emailActionsAvailable && (
            <p className="warning">
              이메일 전달 경로가 준비되지 않았습니다. 개발 모드의 DEV 환경은
              모의 수신함을, 운영 모드의 PROD 환경은 NCP 발송 설정을 확인해 주세요.
            </p>
          )}
          {policy.emailDelivery === "MOCK" && <p className="small muted">이 환경의 인증·복구 메일은 아래 개발용 수신함에만 저장하며 외부로 발송하지 않습니다.</p>}
          {policy.emailDelivery === "NCP" && <p className="small muted">이 환경의 인증·복구 메일은 NCP로 발송합니다. 설정 준비 상태이며 실제 수신 성공을 뜻하지 않습니다.</p>}
          {!policy.passwordPolicyEditable && (
            <p className="warning">
              Keycloak에서 설정한 별도 비밀번호 규칙이 있어 이 화면에서는 수정할
              수 없습니다. 기존 규칙을 먼저 확인해 주세요.
            </p>
          )}
          {policy.passwordMinLength === 0 && (
            <p className="small muted">
              현재 비밀번호 최소 길이는 지정되어 있지 않습니다. 아래 값을
              저장하면 새 비밀번호부터 적용합니다.
            </p>
          )}
          {policy.passwordPolicyEditable && (
            <form
              key={policy.revision}
              onSubmit={save}
              onChange={() => setMessage("")}
            >
              <fieldset disabled={busy || !policy.passwordPolicyEditable}>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    name="loginWithEmail"
                    defaultChecked={policy.loginWithEmail}
                  />
                  아이디 외 이메일로도 로그인 허용
                </label>
                <EmailOption
                  name="verifyEmail"
                  label="이메일 인증 필수"
                  checked={policy.verifyEmail}
                  available={policy.emailActionsAvailable}
                />
                <p className="hint">
                  켜면 인증되지 않은 기존 계정도 다음 로그인에서 이메일 인증을
                  요구받을 수 있습니다.
                </p>
                <EmailOption
                  name="resetPasswordAllowed"
                  label="이메일로 비밀번호 재설정 허용"
                  checked={policy.resetPasswordAllowed}
                  available={policy.emailActionsAvailable}
                />
                <p className="hint">
                  로그인 화면의 비밀번호 찾기를 제공합니다. 소셜 계정의
                  비밀번호는 해당 소셜 서비스에서 복구합니다.
                </p>
                <label>
                  비밀번호 최소 길이
                  <input
                    type="number"
                    name="passwordMinLength"
                    required
                    min={12}
                    max={128}
                    step={1}
                    defaultValue={Math.max(12, policy.passwordMinLength)}
                    aria-describedby="password-policy-hint"
                  />
                </label>
                <p className="hint" id="password-policy-hint">
                  12~128자에서 선택합니다. 최대 길이는 128자이며 새
                  가입·비밀번호 변경과 재설정에 적용합니다. 기존 비밀번호를
                  강제로 변경하거나 세션을 종료하지 않습니다.
                </p>
                <button type="submit">
                  {busy ? "저장 중…" : "가입·복구 정책 저장"}
                </button>
              </fieldset>
            </form>
          )}
        </>
      )}
      {message && <p role="status">{message}</p>}
      {ready && policy?.emailDelivery === "MOCK" && <EmailInbox key={environmentId} environmentId={environmentId} />}
    </section>
  );
}

function EmailOption({
  name,
  label,
  checked,
  available,
}: {
  name: string;
  label: string;
  checked: boolean;
  available: boolean;
}) {
  const [enabled, setEnabled] = useState(checked);
  return (
    <label className="checkbox">
      <input
        type="checkbox"
        name={name}
        checked={enabled}
        disabled={!available && !enabled}
        onChange={(event) => setEnabled(event.target.checked)}
      />
      {label}
    </label>
  );
}
