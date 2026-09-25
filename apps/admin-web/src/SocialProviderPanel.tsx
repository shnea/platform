import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "./auth";

type Provider = {
  code: string;
  label: string;
  alias: string;
  configured: boolean;
  enabled: boolean;
  clientId: string;
  secretConfigured: boolean;
  revision: string;
  callbackUrl: string;
  activationAllowed: boolean;
};
const guides: Record<
  string,
  { url: string; text: string; clientLabel: string }
> = {
  kakao: {
    url: "https://developers.kakao.com/docs/ko/kakaologin/prerequisite",
    text: "카카오 로그인의 OpenID Connect를 켜고 REST API 키와 활성화한 Client Secret을 입력합니다.",
    clientLabel: "REST API 키",
  },
  naver: {
    url: "https://developers.naver.com/docs/login/devguide/devguide.md",
    text: "네이버 로그인 앱의 Client ID와 Client Secret을 입력합니다. 제공 정보와 검수 상태는 네이버 개발자 센터에서 관리합니다.",
    clientLabel: "Client ID",
  },
  google: {
    url: "https://developers.google.com/identity/openid-connect/openid-connect",
    text: "웹 애플리케이션 유형의 OAuth 클라이언트 ID와 보안 비밀번호를 입력합니다. 승인된 리디렉션 URI에 아래 주소를 추가합니다.",
    clientLabel: "클라이언트 ID",
  },
};

export function SocialProviderPanel({
  environmentId,
  ready,
}: {
  environmentId: string;
  ready: boolean;
}) {
  const [providers, setProviders] = useState<Provider[] | null>(null);
  const [code, setCode] = useState("kakao");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
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
        환경마다 별도로 설정합니다. 설정 저장은 실제 로그인 성공을 보장하지
        않습니다.
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
  const [clientId, setClientId] = useState(provider.clientId);
  const [secret, setSecret] = useState("");
  const [enabled, setEnabled] = useState(provider.enabled);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const guide = guides[provider.code];
  const requiresSecret =
    !provider.secretConfigured || clientId.trim() !== provider.clientId;
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    onBusy(true);
    setError("");
    const clientSecret = secret;
    setSecret("");
    try {
      const result = await api<Provider>(
        `/environments/${environmentId}/social-providers/${provider.code}`,
        "PUT",
        {
          clientId: clientId.trim(),
          clientSecret: clientSecret || null,
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
    <div>
      <p className="small muted">
        {guide.text}{" "}
        <a href={guide.url} target="_blank" rel="noreferrer">
          {provider.label} 설정 안내 ↗
        </a>
      </p>
      <dl>
        <div>
          <dt>소셜 앱에 등록할 콜백 URL</dt>
          <dd className="url social-callback">{provider.callbackUrl}</dd>
        </div>
      </dl>
      <p className="hint">
        위 주소를 소셜 앱의 콜백 목록에 추가하세요. 기존 서비스의 콜백 주소는
        유지합니다. 위쪽 로그인 설정의 서비스 콜백과는 별개입니다.
      </p>
      {!provider.activationAllowed && (
        <p className="warning">
          개발 모드·DEV 환경은 Mock 로그인을 사용합니다. 실제 소셜 로그인은 운영
          모드의 PROD 환경에서 켤 수 있습니다.
        </p>
      )}
      <form onSubmit={save} onChange={onEdit}>
        <fieldset disabled={busy}>
          <label>
            {guide.clientLabel}
            <input
              name="social-client-id"
              autoComplete="off"
              required
              maxLength={512}
              value={clientId}
              onChange={(event) => setClientId(event.target.value)}
            />
          </label>
          <label>
            Client Secret
            <input
              type="password"
              name="social-client-secret"
              autoComplete="new-password"
              required={requiresSecret}
              maxLength={4096}
              value={secret}
              onChange={(event) => setSecret(event.target.value)}
              aria-describedby="social-secret-hint"
            />
          </label>
          <p id="social-secret-hint" className="hint">
            {requiresSecret
              ? "처음 저장하거나 앱 키를 바꾸면 해당 앱의 비밀키를 입력해야 합니다."
              : "비밀키가 등록되어 있습니다. 비워 두면 유지하고, 새 값을 입력하면 교체합니다."}{" "}
            저장 후 비밀키는 조회할 수 없습니다.
          </p>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={enabled}
              disabled={!provider.activationAllowed && !enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            실제 로그인에서 사용
          </label>
          <button type="submit">{busy ? "저장 중…" : "소셜 설정 저장"}</button>
        </fieldset>
      </form>
      {error && (
        <p role="alert" className="alert">
          {error} 저장 결과가 불확실하면 설정을 새로고침해 확인하세요. 비밀키
          입력은 비웠습니다.
        </p>
      )}
    </div>
  );
}
