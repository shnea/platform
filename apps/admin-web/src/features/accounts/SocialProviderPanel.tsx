import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../../shared/auth";

type Provider = {
  code: string;
  label: string;
  alias: string;
  configured: boolean;
  enabled: boolean;
  credentialsConfigured: boolean;
  revision: string;
  callbackUrl: string;
  activationAllowed: boolean;
  sharedCallback: boolean;
  sharedCallbackUrl: string;
};
const guides: Record<
  string,
  { url: string; consoleUrl: string; text: string; callbackLabel: string; clientLabel: string }
> = {
  kakao: {
    url: "https://developers.kakao.com/docs/ko/kakaologin/prerequisite",
    consoleUrl: "https://developers.kakao.com/console/app",
    text: "서비스에 해당하는 앱에서 카카오 로그인과 OpenID Connect를 켭니다. REST API 키와 활성화된 Client Secret을 준비합니다.",
    callbackLabel: "리다이렉트 URI",
    clientLabel: "REST API 키",
  },
  naver: {
    url: "https://developers.naver.com/docs/login/devguide/devguide.md",
    consoleUrl: "https://developers.naver.com/apps/#/list",
    text: "네이버 로그인 애플리케이션에서 서비스 URL·제공 정보·검수 상태를 확인하고 Client ID와 Client Secret을 준비합니다.",
    callbackLabel: "Callback URL",
    clientLabel: "Client ID",
  },
  google: {
    url: "https://developers.google.com/identity/openid-connect/openid-connect",
    consoleUrl: "https://console.cloud.google.com/auth/clients",
    text: "Google Auth Platform에서 동의 화면·대상 사용자를 설정하고 웹 애플리케이션 유형의 OAuth 클라이언트를 준비합니다.",
    callbackLabel: "승인된 리디렉션 URI",
    clientLabel: "클라이언트 ID",
  },
};

export function SocialProviderPanel({
  environmentId,
  ready,
  onBusyChange,
}: {
  environmentId: string;
  ready: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const [providers, setProviders] = useState<Provider[] | null>(null);
  const [code, setCode] = useState("kakao");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  useEffect(() => { onBusyChange(saving); }, [saving, onBusyChange]);
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
    setProviders(null);
    setError("");
    api<Provider[]>(`/environments/${environmentId}/social-providers`)
      .then((result) => {
        if (active) setProviders(result);
      })
      .catch((e) => {
        if (active)
          setError(
            e instanceof Error ? e.message : "설정을 불러오지 못했습니다.",
          );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [environmentId, ready, reload]);
  const provider = providers?.find((item) => item.code === code);
  return (
    <section className="social-panel" aria-labelledby="social-title">
      <div className="section-line">
        <h3 id="social-title">소셜 로그인</h3>
        <button
          type="button"
          className="secondary"
          disabled={!ready || loading || saving}
          onClick={() => {
            setMessage("");
            setReload((value) => value + 1);
          }}
        >
          설정 새로고침
        </button>
      </div>
      <p className="small muted">
        제공자별 공통 콜백 하나로 모든 프로젝트를 연결합니다. 같은 소셜 앱에
        주소를 한 번 등록하면 프로젝트가 늘어나도 콜백을 추가하지 않습니다.
        앱 키는 운영자가 한 번 등록하고, 회원과 로그인 세션은 프로젝트·환경별로 분리합니다.
      </p>
      {!ready ? (
        <p className="warning">
          로그인 설정을 먼저 반영한 뒤 소셜 설정을 관리할 수 있습니다.
        </p>
      ) : (
        <>
          {loading && <p role="status">소셜 설정을 불러오는 중…</p>}
          {error && (
            <p className="alert" role="alert">
              {error}
            </p>
          )}
          {providers && (
            <label>
              설정할 로그인 제공자
              <select
                value={code}
                disabled={saving}
                onChange={(event) => {
                  setCode(event.target.value);
                  setMessage("");
                }}
              >
                {providers.map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.label} ·{" "}
                    {item.enabled
                      ? "사용 중"
                      : item.configured
                        ? "사용 안 함"
                        : "미설정"}
                  </option>
                ))}
              </select>
            </label>
          )}
          {provider && (
            <ProviderForm
              key={`${provider.code}:${provider.revision}`}
              provider={provider}
              environmentId={environmentId}
              onBusy={setSaving}
              onEdit={() => setMessage("")}
              onSaved={(result) => {
                if (!live.current) return;
                setProviders(
                  (items) =>
                    items?.map((item) =>
                      item.code === result.code ? result : item,
                    ) ?? null,
                );
                setMessage(
                  "설정을 저장했습니다. 실제 로그인 연동은 별도 확인이 필요합니다.",
                );
              }}
            />
          )}
          {message && (
            <p role="status" className="small">
              {message}
            </p>
          )}
        </>
      )}
    </section>
  );
}

function ProviderForm({
  provider,
  environmentId,
  onBusy,
  onEdit,
  onSaved,
}: {
  provider: Provider;
  environmentId: string;
  onBusy: (busy: boolean) => void;
  onEdit: () => void;
  onSaved: (provider: Provider) => void;
}) {
  const [enabled, setEnabled] = useState(provider.enabled);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copyMessage, setCopyMessage] = useState("");
  const callbackInput = useRef<HTMLTextAreaElement>(null);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const guide = guides[provider.code];
  async function copyCallback() {
    setCopyMessage("");
    try {
      await navigator.clipboard.writeText(provider.sharedCallbackUrl);
      if (live.current) setCopyMessage("콜백 URL을 복사했습니다.");
    } catch {
      if (!live.current) return;
      callbackInput.current?.focus();
      callbackInput.current?.select();
      setCopyMessage("자동 복사를 사용할 수 없습니다. 선택된 주소를 직접 복사하세요.");
    }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    onBusy(true);
    setError("");
    try {
      const result = await api<Provider>(
        `/environments/${environmentId}/social-providers/${provider.code}`,
        "PUT",
        {
          enabled,
          revision: provider.revision,
        },
      );
      if (live.current) onSaved(result);
    } catch (e) {
      if (live.current)
        setError(e instanceof Error ? e.message : "저장하지 못했습니다.");
    } finally {
      if (live.current) {
        setBusy(false);
        onBusy(false);
      }
    }
  }
  return (
    <div className="social-provider-form">
      <ol className="social-steps small">
        <li>{guide.text}</li>
        <li>아래 주소를 복사해 외부 콘솔의 <strong>{guide.callbackLabel}</strong>에 그대로 등록합니다.</li>
        <li>공통 키를 등록한 뒤, 이 프로젝트의 사용 여부를 선택하고 소셜 설정을 저장합니다.</li>
      </ol>
      <div className="actions small social-guide-links">
        <a href={guide.consoleUrl} target="_blank" rel="noreferrer">{provider.label} 개발자 콘솔 (새 탭)</a>
        <a href={guide.url} target="_blank" rel="noreferrer">{provider.label} 공식 설정 안내 (새 탭)</a>
      </div>
      <label>
        소셜 앱에 등록할 공통 콜백 URL
        <textarea
          ref={callbackInput}
          className="social-callback"
          readOnly
          rows={3}
          value={provider.sharedCallbackUrl}
          onFocus={(event) => event.currentTarget.select()}
          aria-describedby="social-callback-hint"
        />
      </label>
      <button type="button" className="secondary" onClick={() => void copyCallback()}>콜백 URL 복사</button>
      <p role="status" className="small">{copyMessage}</p>
      <p id="social-callback-hint" className="hint">
        모든 프로젝트가 함께 사용하는 제공자별 주소입니다. 외부 콘솔에 자동 등록되지는 않습니다.
        주소 중간을 *로 바꾸지 마세요. 위쪽 로그인 설정의 서비스 콜백과는 별개입니다.
      </p>
      <p className="hint">
        인증 도메인이 바뀌면 새 주소를 등록해야 합니다. 다른 소셜 앱을 사용하면 그 앱에도 같은 주소를 등록하세요.
        기존 앱 재사용은 제공자의 서비스 범위·정책을 확인하고, 기존 콜백은 유지하세요.
        카카오는 서비스별 앱을 사용합니다.
      </p>
      {provider.configured && !provider.sharedCallback && (
        <p className="warning">
          이 설정은 아직 기존 개별 콜백을 사용합니다. 외부 콘솔에 위 공통 주소를
          먼저 등록하세요. 소셜 설정을 저장하면 공통 콜백으로 전환됩니다.
          기존 주소: <span className="url">{provider.callbackUrl}</span>
        </p>
      )}
      {!provider.activationAllowed && (
        <p className="warning">
          개발 모드·DEV 환경은 Mock 로그인을 사용합니다. 실제 소셜 로그인은 운영
          모드의 PROD 환경에서 켤 수 있습니다.
        </p>
      )}
      <form onSubmit={save} onChange={onEdit}>
        <fieldset disabled={busy}>
          <p className={provider.credentialsConfigured ? "small" : "warning"}>
            {provider.credentialsConfigured
              ? "공통 소셜 키가 등록되어 있습니다. 프로젝트에서는 사용 여부만 설정하세요."
              : "공통 소셜 키가 미등록 상태입니다. 운영자가 공통 키를 등록한 뒤 설정을 새로고침하세요."}
          </p>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={enabled}
              disabled={(!provider.activationAllowed || !provider.credentialsConfigured) && !enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            실제 로그인에서 사용
          </label>
          <button type="submit">{busy ? "저장 중…" : "소셜 설정 저장"}</button>
        </fieldset>
      </form>
      {error && (
        <p role="alert" className="alert">
          {error} 저장 결과가 불확실하면 설정을 새로고침해 확인하세요.
        </p>
      )}
    </div>
  );
}
