import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../../shared/auth";
import { MockResetPanel } from "./MockResetPanel";

type Report = {
  httpStatus: number;
  result: {
    scenario: string;
    provider: string;
    userId?: string;
    issuer?: string;
    expiresIn?: number;
    error?: string;
  };
};
const outcomes: Record<string, string> = {
  success: "로그인 성공",
  cancelled: "로그인 취소",
  access_denied: "동의 거부",
  provider_unavailable: "제공자 장애",
};

export function MockLoginPanel({
  environmentId,
  disabled,
  environmentLabel,
  onReset,
  onBusyChange,
}: {
  environmentId: string;
  disabled: boolean;
  environmentLabel: string;
  onReset: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [resetBusy, setResetBusy] = useState(false);
  useEffect(() => { onBusyChange(busy || resetBusy); }, [busy, resetBusy, onBusyChange]);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true);
    setReport(null);
    setError("");
    try {
      const result = await api<Report>(
        `/environments/${environmentId}/mock-login`,
        "POST",
        {
          provider: data.get("provider"),
          subject: data.get("subject"),
          scenario: data.get("scenario"),
        },
      );
      if (live.current) setReport(result);
    } catch (e) {
      if (live.current)
        setError(
          e instanceof Error
            ? e.message
            : "실행하지 못했습니다. 다시 시도해 주세요.",
        );
    } finally {
      if (live.current) setBusy(false);
    }
  }
  return (
    <section className="mock-panel" aria-labelledby="mock-title">
      <div className="section-line">
        <h3 id="mock-title">개발 로그인 테스트</h3>
        <span className="mode">Mock</span>
      </div>
      <p className="small muted">
        현재 선택한 환경에서만 실행합니다. 외부 소셜 서비스에는 요청하지
        않습니다. 성공하면 Keycloak에 테스트 사용자와 로그인 세션이
        만들어집니다.
      </p>
      {disabled && (
        <p className="warning">
          프로젝트가 사용 중이고 환경이 반영 완료 상태일 때 실행할 수 있습니다.
        </p>
      )}
      <form
        onSubmit={run}
        onChange={() => {
          setReport(null);
          setError("");
        }}
      >
        <fieldset disabled={busy || resetBusy || disabled}>
          <div className="form-grid">
            <label>
              로그인 제공자
              <select name="provider" defaultValue="kakao">
                <option value="kakao">카카오</option>
                <option value="naver">네이버</option>
                <option value="google">구글</option>
              </select>
            </label>
            <label>
              테스트 사용자 ID
              <input
                name="subject"
                defaultValue="test-user"
                required
                pattern="[a-zA-Z0-9_\-]{1,80}"
                maxLength={80}
                aria-describedby="mock-user-hint"
              />
            </label>
          </div>
          <p id="mock-user-hint" className="hint">
            영문·숫자·밑줄·하이픈 1~80자. 같은 환경·제공자·ID는 같은 테스트
            사용자로 로그인합니다.
          </p>
          <label>
            테스트 시나리오
            <select name="scenario" defaultValue="success">
              {Object.entries(outcomes).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <button type="submit">{busy ? "실행 중…" : "테스트 실행"}</button>
        </fieldset>
      </form>
      {error && (
        <p role="alert" className="alert">
          테스트를 실행하지 못했습니다. {error}
        </p>
      )}
      {report && (
        <div className="mock-result" role="status" aria-live="polite">
          <h3>
            {outcomes[report.result.scenario]} · HTTP {report.httpStatus}
          </h3>
          <p className="small muted">
            연동용 Mock API가 반환하는 결과입니다. 실제 소셜 연동 검증 결과는
            아닙니다.
          </p>
          {report.result.error ? (
            <p>
              선택한 실패를 재현했습니다. 테스트 사용자 변경과 토큰 발급은 하지
              않았습니다.
            </p>
          ) : (
            <dl>
              <div>
                <dt>테스트 사용자 식별자</dt>
                <dd className="identifier">{report.result.userId}</dd>
              </div>
              <div>
                <dt>인증 발급자</dt>
                <dd className="url">{report.result.issuer}</dd>
              </div>
              <div>
                <dt>발급 토큰 유효기간</dt>
                <dd>{report.result.expiresIn}초</dd>
              </div>
            </dl>
          )}
          <p className="hint">
            화면은 관리자 권한으로 검사하며 서버 API 키와 이용자 토큰을
            표시하거나 저장하지 않습니다.
          </p>
          <button
            className="secondary"
            type="button"
            onClick={() => setReport(null)}
          >
            결과 지우기
          </button>
        </div>
      )}
      <MockResetPanel environmentId={environmentId} environmentLabel={environmentLabel}
        disabled={disabled || busy} onBusyChange={setResetBusy} onReset={() => { setReport(null); onReset(); }} />
    </section>
  );
}
