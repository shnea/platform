import {Icon} from '../shared/Icon';
import { createRoot } from "react-dom/client";
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { api, auth, initialize, mode } from "../shared/auth";
import "../styles/style.css";
import shneaMark from "../assets/brand/shnea-mark.svg";
import { MockLoginPanel } from "../features/testing/MockLoginPanel";
import { SocialProviderPanel } from "../features/accounts/SocialProviderPanel";
import { AuthenticationPolicyPanel } from "../features/accounts/AuthenticationPolicyPanel";
import { MemberPanel } from "../features/accounts/MemberPanel";
import { AccountSecurity, resumeAccountSecurity } from "../features/accounts/AccountSecurity";
import { JobWorkspace } from "../features/jobs/JobWorkspace";
import { LogPanel } from "../features/logs/LogPanel";
import { MonitoringWorkspace } from "../features/monitoring/MonitoringWorkspace";
import { ServiceMonitoring } from "../features/monitoring/ServiceMonitoring";
import { AlertWorkspace } from "../features/alerts/AlertWorkspace";
import { Dialog } from "../shared/Dialog";
import { SectionTabs } from "../shared/SectionTabs";
import { ProjectOverview } from "../features/projects/ProjectOverview";
import { ProjectFilesSettings } from "../features/projects/ProjectFilesSettings";
import { ProjectIntegration } from "../features/projects/ProjectIntegration";
import { DeveloperCenter } from "../features/developer/DeveloperCenter";
import { EditorEntry } from "../features/editor/EditorEntry";
import { FileWorkspace } from "../features/files/FileWorkspace";
import { NoedaeriWorkspace } from "../features/noedaeri/NoedaeriWorkspace";
import "../styles/console.css";

type View = "projects" | "files" | "noedaeri" | "jobs" | "logs" | "monitoring" | "alerts" | "audit" | "developer" | "editor";
type ProjectSection = "overview" | "integration" | "auth" | "members" | "keys" | "test" | "settings";
const viewIcons = {projects:'folder',files:'file',noedaeri:'activity',editor:'pencil',jobs:'list-checks',logs:'search',monitoring:'activity',alerts:'bell',developer:'code-xml',audit:'shield-check'} as const;
const views: { value: View; label: string; description: string }[] = [
  { value: "projects", label: "프로젝트", description: "프로젝트를 선택해 환경과 서비스 접근을 관리하세요." },
  { value: "files", label: "파일", description: "파일을 올리고 썸네일·미리보기·URL과 보존 정책을 관리하세요." },
  { value: "noedaeri", label: "뇌대리", description: "현재 환경의 이미지·영상·AI·번역·검색 연동을 직접 검수하세요." },
  { value: "editor", label: "에디터", description: "문서를 직접 편집하고 읽기 화면과 연동 데이터를 확인하세요." },
  { value: "jobs", label: "비동기 작업", description: "프로젝트와 환경을 선택해 작업 상태와 실행 이력을 확인하세요." },
  { value: "logs", label: "로그", description: "프로젝트 서버가 전송한 로그를 검색하고 같은 요청의 흐름을 확인하세요." },
  { value: "monitoring", label: "모니터링", description: "플랫폼 서비스 상태와 프로젝트별 작업 현황을 확인하세요." },
  { value: "alerts", label: "운영 알림", description: "작업의 최종 실패 알림을 확인하고 처리 기록을 남기세요." },
  { value: "developer", label: "개발자 센터", description: "파일 API 명세와 서버 연동 예제로 프로젝트를 연결하세요." },
  { value: "audit", label: "감사 이력", description: "관리 작업과 인증 활동을 확인하세요. 최근 100건을 표시합니다." },
];
const projectSections: { value: ProjectSection; label: string }[] = [
  { value: "overview", label: "개요" }, { value: "integration", label: "서비스 연결" }, { value: "auth", label: "인증 설정" },
  { value: "members", label: "회원" }, { value: "keys", label: "API 키" },
  { value: "test", label: "개발 테스트" }, { value: "settings", label: "프로젝트 설정" },
];
const authSections = [
  { value: "login", label: "로그인 주소" }, { value: "policy", label: "가입·복구 정책" },
  { value: "social", label: "소셜 로그인" },
] as const;

type Project = {
  id: string;
  code: string;
  name: string;
  status: "ACTIVE" | "SUSPENDED";
  filesEnabled: boolean;
  revision: number;
};
type Environment = {
  id: string;
  code: string;
  kind: string;
  registrationAllowed: boolean;
  redirectUris: string[];
  issuer: string;
  state: string;
  revision: number;
};
type Key = {
  id: string;
  created_at: string;
  expires_at: string | null;
  revoked_at: string | null;
  scopes: string[];
};
type Scope = { code: string; label: string; description: string };
type Event = {
  id: number;
  action: string;
  target_id: string;
  environment_id: string | null;
  session_id: string | null;
  created_at: string;
};
const keyExpired = (key: Key) =>
  key.expires_at !== null && new Date(key.expires_at).getTime() <= Date.now();
type Modal =
  | { type: "project" }
  | { type: "edit" | "status"; project: Project }
  | { type: "environment"; env?: Environment }
  | { type: "issue"; environmentId: string; onIssued?: (key: string) => void }
  | { type: "revoke"; key: Key }
  | { type: "secret"; secret: string };
const date = (value: string) =>
  new Date(value).toLocaleString("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
  });
const stateName: Record<string, string> = {
  READY: "반영 완료",
  PENDING: "반영 대기",
  FAILED: "반영 실패",
  ACTIVE: "사용 중",
  SUSPENDED: "중지됨",
};
function State({ value }: { value: string }) {
  return (
    <span className={"state " + value.toLowerCase()}>
      <span aria-hidden="true" />
      {stateName[value] ?? value}
    </span>
  );
}
function App() {
  const [accountSecurity, setAccountSecurity] = useState(resumeAccountSecurity);
  const [ready, setReady] = useState(false),
    [bootError, setBootError] = useState("");
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem("platform-theme") === "light"
        ? "light"
        : "dark";
    } catch {
      return "dark";
    }
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("platform-theme", theme);
    } catch {
      /* Browser storage is optional. */
    }
  }, [theme]);
  useEffect(() => {
    initialize()
      .then(() => setReady(true))
      .catch(() =>
        setBootError(
          "로그인 서버에 연결하지 못했습니다. 서버 상태와 접속 주소를 확인해 주세요.",
        ),
      );
  }, []);
  return (
    <>
      <a className="skip" href="#main">
        본문으로 이동
      </a>
      <header>
        <a className="brand" href="/" aria-label="SHNEA Platform 홈">
          <img className="brand-mark" src={shneaMark} width="40" height="40" alt="" />
          <span className="brand-lockup" aria-hidden="true">
            <span className="brand-wordmark" />
            <span className="brand-descriptor">Platform</span>
          </span>
        </a>
        <div className="header-actions">
          <button
            className="quiet"
            aria-label={theme === "dark" ? "밝은 화면" : "어두운 화면"} title={theme === "dark" ? "밝은 화면" : "어두운 화면"} data-tooltip={theme === "dark" ? "밝은 화면" : "어두운 화면"} data-icon-only="true" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          ><Icon name={theme === 'dark' ? 'sun' : 'moon'}/>
          </button>
          {ready && auth.authenticated && (
            <button className="quiet" onClick={() => setAccountSecurity(true)} aria-label="내 계정" title="내 계정" data-tooltip="내 계정" data-icon-only="true"><Icon name="user-round"/></button>
          )}
          {ready && auth.authenticated && (
            <button
              className="quiet"
              onClick={() =>
                void auth.logout({ redirectUri: location.origin + "/" })
              }
             aria-label="로그아웃" title="로그아웃" data-tooltip="로그아웃" data-icon-only="true"><Icon name="log-out"/></button>
          )}
        </div>
      </header>
      {ready && auth.authenticated && accountSecurity && <AccountSecurity close={() => setAccountSecurity(false)} />}
      {bootError ? (
        <main id="main" className="welcome">
          <h1>연결을 확인해 주세요</h1>
          <p role="alert">{bootError}</p>
          <button onClick={() => location.reload()} aria-label="다시 시도" title="다시 시도" data-tooltip="다시 시도" data-icon-only="true"><Icon name="refresh-cw"/></button>
        </main>
      ) : !ready ? (
        <main id="main" className="welcome" aria-busy="true">
          <p role="status">관리자 환경을 불러오는 중입니다…</p>
        </main>
      ) : !auth.authenticated ? (
        <main id="main" className="welcome welcome-home">
          <div className="welcome-copy">
          <div className="welcome-rule" />
          <p className="welcome-eyebrow">SHNEA PLATFORM / 공통 서비스 관리</p>
          <h1>
            프로젝트의 시작을
            <br />
            한곳에서.
          </h1>
          <p>
            프로젝트와 로그인 환경을 만들고,
            <br />
            서비스 연결과 접근 상태를 관리하세요.
          </p>
          <button
            onClick={() =>
              void auth.login({ redirectUri: location.origin + "/" })
            }
          ><Icon name="log-in"/>
            관리자 로그인
          </button>
          <p className="small muted">
            Keycloak 관리자 계정으로 안전하게 연결합니다.
          </p>
          <a className="welcome-demo" href="/demo.html">로그인 없이 에디터·미디어 체험하기 <span aria-hidden="true">↗</span></a>
          </div>
          <div className="welcome-art" aria-hidden="true">
            <div className="welcome-art-heading"><span>하나의 작업 공간</span><span>01 / 04</span></div>
            <div className="welcome-orbit"><span className="welcome-orbit-core">S.</span></div>
            <div className="welcome-art-caption"><span>연결은 단순하게.<br/>관리는 한곳에서.</span><span>↗</span></div>
          </div>
          <div className="welcome-services">
            <article><span>01</span><Icon name="folder"/><h2>프로젝트 · 인증</h2><p>프로젝트별 환경과 접근을 관리합니다.</p></article>
            <article><span>02</span><Icon name="file"/><h2>파일 · 에디터</h2><p>미디어와 문서 작업을 연결합니다.</p></article>
            <article><span>03</span><Icon name="activity"/><h2>AI · 뇌대리</h2><p>AI와 미디어 처리를 실행합니다.</p></article>
            <article><span>04</span><Icon name="list-checks"/><h2>로그 · 운영</h2><p>서비스 상태와 실행 이력을 확인합니다.</p></article>
          </div>
        </main>
      ) : (
        <Workspace />
      )}
    </>
  );
}
function Workspace() {
  const editorDraft=useRef<unknown>(undefined);
  const [projects, setProjects] = useState<Project[]>([]),
    [offset, setOffset] = useState(0),
    [selected, setSelected] = useState<string | null>(null);
  const [envs, setEnvs] = useState<Environment[]>([]),
    [envId, setEnvId] = useState<string | null>(null),
    [keys, setKeys] = useState<Key[]>([]),
    [events, setEvents] = useState<Event[]>([]);
  const [tab, setTab] = useState<View>("projects"),
    [modal, setModal] = useState<Modal | null>(null);
  const [section, setSection] = useState<ProjectSection>("overview");
  const [monitoringScope, setMonitoringScope] = useState<"services" | "projects">("services");
  const serviceView = tab === "editor" || tab === "developer" || tab === "monitoring" && monitoringScope === "services";
  const [authSection, setAuthSection] = useState<"login" | "policy" | "social">("login");
  const [menuOpen, setMenuOpen] = useState(false);
  const pageTitle = useRef<HTMLHeadingElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(true),
    [detailLoading, setDetailLoading] = useState(false),
    [keysLoading, setKeysLoading] = useState(false);
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [refresh, setRefresh] = useState(0);
  const [keyHasExpiry, setKeyHasExpiry] = useState(false);
  const [jobInitialState, setJobInitialState] = useState("");
  const [memberRefresh, setMemberRefresh] = useState(0);
  const [scopeOptions, setScopeOptions] = useState<Scope[]>([]);
  const previousProject = useRef<string | null>(null);
  const project = projects.find((p) => p.id === selected),
    env = envs.find((e) => e.id === envId);
  const view = views.find(item => item.value === tab)!;
  useEffect(()=>{document.title=`${view.label} · SHNEA Platform`;},[view.label]);
  const activeSection = section === "test" && (mode !== "dev" || env?.kind !== "DEV") ? "overview" : section;
  function moveTo(next: View) {
    if (busy) return;
    setJobInitialState("");
    setTab(next); setMenuOpen(false); setError(""); setNotice("");
    requestAnimationFrame(() => { pageTitle.current?.focus(); window.scrollTo(0, 0); });
  }
  function chooseProject(id: string | null) {
    setSelected(id); setEnvs([]); setEnvId(null); setKeys([]);
    setSection("overview"); setAuthSection("login"); setError(""); setNotice("");
    setJobInitialState("");
    requestAnimationFrame(() => pageTitle.current?.focus());
  }
  useEffect(() => {
    let live = true;
    setLoading(true);
    setError("");
    if (serviceView) { setLoading(false); return; }
    const load =
      tab !== "audit"
        ? api<Project[]>(`/projects?limit=20&offset=${offset}`).then((rows) => {
            if (live) setProjects(rows);
          })
        : api<Event[]>("/audit-events?limit=100").then((rows) => {
            if (live) setEvents(rows);
          });
    load
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [offset, refresh, tab, serviceView]);
  useEffect(() => {
    let live = true;
    const sameProject = previousProject.current === selected;
    previousProject.current = selected;
    setEnvs([]);
    if (!sameProject) setEnvId(null);
    setKeys([]);
    if (!selected) {
      setDetailLoading(false);
      return;
    }
    setDetailLoading(true);
    api<Environment[]>(`/projects/${selected}/environments`)
      .then((rows) => {
        if (live) {
          setEnvs(rows);
          setEnvId((current) =>
            sameProject && rows.some((row) => row.id === current)
              ? current
              : (rows[0]?.id ?? null),
          );
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setDetailLoading(false);
      });
    return () => {
      live = false;
    };
  }, [selected, refresh]);
  useEffect(() => {
    let live = true;
    setKeys([]);
    setScopeOptions([]);
    if (!envId || tab !== "projects" || !["keys", "integration"].includes(activeSection)) {
      setKeysLoading(false);
      return;
    }
    setKeysLoading(true);
    Promise.all([
      api<Key[]>(`/environments/${envId}/credentials`),
      api<Scope[]>(`/environments/${envId}/credential-scopes`),
    ])
      .then(([rows, scopes]) => {
        if (live) {
          setKeys(rows);
          setScopeOptions(scopes);
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setKeysLoading(false);
      });
    return () => {
      live = false;
    };
  }, [envId, refresh, tab, activeSection]);
  function open(value: Modal) {
    setKeyHasExpiry(false);
    setError("");
    setNotice("");
    setModal(value);
  }
  function reload() {
    setRefresh((value) => value + 1);
  }
  async function action(work: () => Promise<unknown>, success: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await work();
      setNotice(success);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "연결을 확인한 뒤 다시 시도해 주세요.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    if (modal?.type === "issue") {
      await action(async () => {
        const scopes = data.getAll("scopes").map(String);
        if (!scopes.length)
          throw new Error("사용할 권한을 하나 이상 선택해 주세요.");
        const expiry = keyHasExpiry
          ? new Date(String(data.get("expiresAt")))
          : null;
        if (
          expiry &&
          (!Number.isFinite(expiry.getTime()) || expiry.getTime() <= Date.now())
        )
          throw new Error("만료일은 현재보다 이후로 선택해 주세요.");
        const result = await api<{ apiKey: string }>(
          `/environments/${modal.environmentId}/credentials`,
          "POST",
          {
            expiresAt: expiry?.toISOString() ?? null,
            scopes,
          },
        );
        if (modal.onIssued) {
          modal.onIssued(result.apiKey);
          setModal(null);
          return;
        }
        open({ type: "secret", secret: result.apiKey });
        setKeys(
          await api<Key[]>(`/environments/${modal.environmentId}/credentials`),
        );
      }, "");
      return;
    }
    await action(async () => {
      if (modal?.type === "project") {
        await api("/projects", "POST", {
          name: data.get("name"),
          code: data.get("code"),
          filesEnabled: data.get("filesEnabled") === "on",
        });
        setOffset(0);
      }
      if (modal?.type === "edit" || modal?.type === "status") {
        const p = modal.project;
        await api(`/projects/${p.id}`, "PUT", {
          name: modal.type === "edit" ? data.get("name") : p.name,
          status:
            modal.type === "status"
              ? p.status === "ACTIVE"
                ? "SUSPENDED"
                : "ACTIVE"
              : p.status,
          revision: p.revision,
        });
      }
      if (modal?.type === "environment" && project) {
        const fields = {
          registrationAllowed: data.get("registration") === "on",
          redirectUris: String(data.get("redirects"))
            .split("\n")
            .map((v) => v.trim())
            .filter(Boolean),
        };
        const saved = await api<Environment>(
          modal.env
            ? `/environments/${modal.env.id}`
            : `/projects/${project.id}/environments`,
          modal.env ? "PUT" : "POST",
          modal.env
            ? { ...fields, revision: modal.env.revision }
            : { ...fields, code: data.get("code"), kind: data.get("kind") },
        );
        setEnvId(saved.id);
      }
      if (modal?.type === "revoke")
        await api(`/credentials/${modal.key.id}`, "DELETE");
      setModal(null);
      reload();
    }, "요청을 저장했습니다. 환경별 반영 상태를 확인해 주세요.");
  }
  const modalTitle =
    modal?.type === "project"
      ? "프로젝트 만들기"
      : modal?.type === "edit"
        ? "프로젝트 이름 변경"
        : modal?.type === "environment"
          ? modal.env
            ? "로그인 설정 변경"
            : "환경 만들기"
          : modal?.type === "status"
            ? modal.project.status === "ACTIVE"
              ? "프로젝트 중지"
              : "프로젝트 재개"
            : modal?.type === "secret"
              ? "API 키가 발급되었습니다"
              : modal?.type === "issue"
                ? "서버 API 키 발급"
                : "API 키 폐기";
  return (
    <div className="shell">
      <aside className={menuOpen ? "menu-open" : ""}>
        <button ref={menuButton} className="secondary mobile-menu" aria-expanded={menuOpen} aria-controls="workspace-menu"
          disabled={busy} onClick={() => setMenuOpen(value => !value)}><Icon name={menuOpen ? 'x' : 'menu'}/>{view.label} · {menuOpen ? "메뉴 닫기" : "메뉴 열기"}</button>
        <div id="workspace-menu" className="workspace-menu" onKeyDown={event => {
          if (event.key === "Escape") { setMenuOpen(false); menuButton.current?.focus(); }
        }}>
          <p className="nav-label">WORKSPACE <span>관리 메뉴</span></p>
          <nav aria-label="관리 메뉴">
            {views.map(item => <button key={item.value} className={tab === item.value ? "nav active" : "nav"}
              disabled={busy} aria-current={tab === item.value ? "page" : undefined}
              onClick={() => moveTo(item.value)}><Icon name={viewIcons[item.value]}/>{item.label}</button>)}
          </nav>
        </div>
        <div className="workspace-note">
          <span className="mode">
            {mode === "dev" ? "개발 환경" : "운영 환경"}
          </span>
          <p>
            프로젝트마다 계정과
            <br />
            접근 설정을 분리합니다.
          </p>
        </div>
      </aside>
      <main id="main" data-view={tab}>
        <div className="page-heading">
          <div>
            <p className="breadcrumb">
              워크스페이스 / {view.label}{project && tab !== "audit" && !serviceView ? ` / ${project.code}` : ""}
            </p>
            <h1 ref={pageTitle} tabIndex={-1}>
              {tab === "projects" && project ? project.name : view.label}
            </h1>
            <p className="muted">
              {tab === "projects" && project ? "환경을 선택한 뒤 필요한 기능 탭으로 이동하세요." : view.description}
            </p>
          </div>
          <div className="actions">
            {!serviceView && <button
              className="secondary"
              disabled={busy || loading}
              onClick={reload}
             aria-label="새로고침" title="새로고침" data-tooltip="새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button>}
            {tab === "projects" && !project && (
              <button disabled={busy} onClick={() => open({ type: "project" })}><Icon name="plus"/>
                프로젝트 만들기
              </button>
            )}
          </div>
        </div>
        {error && !modal && (
          <p className="alert" role="alert">
            {error}
          </p>
        )}
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {tab === "monitoring" && <SectionTabs id="monitoring-scope" label="모니터링 범위" value={monitoringScope} onChange={setMonitoringScope} disabled={busy}
          items={[{value:"services",label:"서비스 상태"},{value:"projects",label:"프로젝트 작업"}]} />}
        <div id={tab === "monitoring" ? "monitoring-scope-panel" : undefined} role={tab === "monitoring" ? "tabpanel" : undefined}
          aria-labelledby={tab === "monitoring" ? `monitoring-scope-${monitoringScope}` : undefined}>
        {tab === "editor" ? <EditorEntry draft={editorDraft}/> : tab === "developer" ? <DeveloperCenter /> : serviceView ? <ServiceMonitoring /> : tab === "audit" ? (
          loading ? (
            <p role="status">이력을 불러오는 중…</p>
          ) : (
            <div className="table-scroll">
              <table>
                <caption className="sr-only">최근 감사 이력</caption>
                <thead>
                  <tr>
                    <th>작업</th>
                    <th>대상 ID</th>
                    <th>시간</th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((event) => (
                    <tr key={event.id}>
                      <td>{({
                        "mock.user.reset.started": "테스트 계정 삭제 시작",
                        "mock.user.deleted": "테스트 계정 삭제 완료",
                        "mock.user.reset.failed": "테스트 계정 삭제 실패",
                        "mock.reset.completed": "테스트 계정 초기화 완료",
                        "mock.reset.partial": "테스트 계정 초기화 일부 실패",
                      } as Record<string, string>)[event.action] || event.action}</td>
                      <td className="identifier">{event.target_id}
                        {event.environment_id && <div className="muted">환경: {event.environment_id}</div>}
                        {event.session_id && <div className="muted">세션: {event.session_id}</div>}
                      </td>
                      <td>{date(event.created_at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!events.length && (
                <p className="empty">아직 기록된 작업이 없습니다.</p>
              )}
            </div>
          )
        ) : !project ? (
          <>
            <div className="section-line">
              <h2>{tab === "projects" ? "프로젝트 목록" : "조회할 프로젝트 선택"}</h2>
              <span className="muted small">페이지 {offset / 20 + 1}</span>
            </div>
            {loading ? (
              <p className="empty" role="status">
                프로젝트를 불러오는 중…
              </p>
            ) : projects.length ? (
              <div className="project-list">
                {projects.map((p) => (
                  <button
                    className="project-row"
                    key={p.id}
                    disabled={busy}
                    onClick={() => chooseProject(p.id)}
                  >
                    <span className="project-initial" aria-hidden="true">
                      {p.name.slice(0, 1)}
                    </span>
                    <span className="project-label">
                      <strong>{p.name}</strong>
                      <span>{p.code}</span>
                    </span>
                    <State value={p.status} />
                    <span aria-hidden="true" className="row-arrow">
                      →
                    </span>
                  </button>
                ))}
              </div>
            ) : (
              <div className="empty">
                <h3>연결할 프로젝트를 만들어 보세요</h3>
                <p>
                  프로젝트를 만든 다음 개발·운영 환경과 로그인 주소를
                  등록합니다.
                </p>
              </div>
            )}
            <div className="pagination">
              <button
                className="secondary"
                disabled={busy || loading || offset === 0}
                onClick={() => setOffset(offset - 20)}
              ><Icon name="chevron-left"/>
                이전
              </button>
              <button
                className="secondary"
                disabled={busy || loading || projects.length < 20}
                onClick={() => setOffset(offset + 20)}
              ><Icon name="chevron-right"/>
                다음
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="project-toolbar">
              <button className="quiet" disabled={busy} onClick={() => chooseProject(null)}><Icon name="folders"/>
                다른 프로젝트 선택
              </button>
              <div className="actions scope-caption">{tab !== "projects" && <strong>{project.name}</strong>}<State value={project.status} />
                {tab !== "projects" && <button className="secondary" disabled={busy} onClick={() => moveTo("projects")}><Icon name="folder"/>프로젝트로 이동</button>}
              </div>
            </div>
            {tab === "projects" && <SectionTabs id="project-section" label="프로젝트 기능" value={activeSection} disabled={busy}
              items={projectSections.filter(item => item.value !== "test" || mode === "dev" && env?.kind === "DEV")}
              onChange={value => { setSection(value); setError(""); setNotice(""); }} />}
            <div className="workspace-content" id={tab === "projects" ? "project-section-panel" : undefined}
              role={tab === "projects" ? "tabpanel" : undefined} aria-labelledby={tab === "projects" ? `project-section-${activeSection}` : undefined} tabIndex={0}>
            {tab === "projects" && activeSection === "settings" ? <section>
              <div className="section-line"><h2>프로젝트 설정</h2></div>
              <dl><div><dt>프로젝트 코드</dt><dd className="identifier">{project.code}</dd></div>
                <div><dt>프로젝트 이름</dt><dd>{project.name}</dd></div>
                <div><dt>사용 상태</dt><dd><State value={project.status} /></dd></div></dl>
              <p className="muted">프로젝트 이름과 사용 상태는 모든 환경에 적용됩니다.</p>
              <div className="actions">
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() => open({ type: "edit", project })}
                ><Icon name="pencil"/>
                  이름 변경
                </button>
                <button
                  className={
                    project.status === "ACTIVE"
                      ? "secondary danger"
                      : "secondary"
                  }
                  disabled={busy}
                  onClick={() => open({ type: "status", project })}
                ><Icon name={project.status === 'ACTIVE' ? 'pause' : 'play'}/>
                  {project.status === "ACTIVE" ? "중지" : "재개"}
                </button>
              </div>
              <ProjectFilesSettings key={`${project.id}:${project.revision}`} project={project} onChanged={reload} onBusyChange={setBusy}/>
            </section> : <>
            {project.status === "SUSPENDED" && (
              <details className="scope-warning"><summary>중지된 프로젝트입니다</summary><p>
                프로젝트가 중지되어 API 키 사용이 차단됩니다. 로그인 차단 반영
                여부는 아래 환경별 상태를 확인하세요. 이미 발급된 토큰은
                만료까지 유효할 수 있습니다.
              </p></details>
            )}
            <h2 className="sr-only">조회 환경</h2>
            {detailLoading ? (
              <p className="empty" role="status">
                환경을 불러오는 중…
              </p>
            ) : !envs.length ? (
              <div className="empty">
                <h3>아직 등록한 환경이 없습니다</h3>
                <p>
                  DEV 또는 PROD 환경을 만들면 독립된 로그인 영역이 준비됩니다.
                </p>
                {tab === "projects" && <button className="secondary" disabled={busy || project.status !== "ACTIVE"}
                  onClick={() => open({ type: "environment" })}><Icon name="plus"/>환경 만들기</button>}
              </div>
            ) : (
              <>
                <div className="environment-context">
                  <label>조회 환경<select value={envId ?? ""} disabled={busy} onChange={event => {
                    setEnvId(event.target.value); setError(""); setNotice("");
                    setJobInitialState("");
                    if (section === "test") setSection("overview");
                  }}>{envs.map(item => <option key={item.id} value={item.id}>{item.code} ({item.kind})</option>)}</select></label>
                  {env && <State value={env.state} />}
                  {tab === "projects" && activeSection === "overview" && <button className="secondary"
                    disabled={busy || project.status !== "ACTIVE"} onClick={() => open({ type: "environment" })}><Icon name="plus"/>환경 만들기</button>}
                </div>
                {env && (
                  <>
                    {tab === "projects" && activeSection === "integration" && <ProjectIntegration key={env.id}
                      project={project} environment={env} disabled={busy}
                      issueDisabled={keysLoading || !scopeOptions.length || env.state !== "READY" || project.status !== "ACTIVE"}
                      onIssue={onIssued => open({type: "issue", environmentId: env.id, onIssued})} />}
                    {tab === "projects" && activeSection === "overview" && <ProjectOverview key={`overview:${env.id}:${refresh}`} environmentId={env.id}
                      ready={env.state === "READY"} disabled={busy} open={moveTo} />}
                    {tab === "files" && !project.filesEnabled && <p className="warning">파일 서비스 사용이 꺼져 있습니다. <button className="secondary" onClick={()=>{moveTo("projects");setSection("settings");}}><Icon name="settings"/>프로젝트 설정으로</button></p>}
                    {tab === "files" && project.filesEnabled && <FileWorkspace key={`files:${env.id}:${refresh}`} environmentId={env.id}
                      environmentLabel={`${project.name} / ${env.code} (${env.kind})`} available={env.state === "READY" && project.status === "ACTIVE"}
                      onBusyChange={setBusy} />}
                    {tab === "noedaeri" && <NoedaeriWorkspace key={`noedaeri:${env.id}`} environmentId={env.id} environmentLabel={`${project.name} / ${env.code} (${env.kind})`} available={env.state === "READY" && project.status === "ACTIVE"} filesEnabled={project.filesEnabled} onBusyChange={setBusy} />}
                    {tab === "jobs" && <>
                    <JobWorkspace key={`jobs:${env.id}`} environmentId={env.id} initialState={jobInitialState}
                      environmentLabel={`${project.name} / ${env.code} (${env.kind})`}
                      ready={env.state === "READY"} disabled={busy} onBusyChange={setBusy}
                      onSettled={() => {
                        void api<Environment[]>(`/projects/${project.id}/environments`).then(rows => {
                          setEnvs(current => current.some(item => item.id === env.id) ? rows : current);
                        }).catch(e => setError(e.message));
                      }} />
                    </>}
                    {tab === "logs" && <LogPanel key={`logs:${env.id}`} environmentId={env.id}/>}
                    {tab === "monitoring" && <MonitoringWorkspace key={`monitoring:${env.id}`} environmentId={env.id} onBusyChange={setBusy}
                      environmentLabel={`${project.name} / ${env.code} (${env.kind})`} disabled={busy}
                      openJobs={state => { moveTo("jobs"); setJobInitialState(state); }} openAlerts={() => moveTo("alerts")} />}
                    {tab === "alerts" && <>
                    <AlertWorkspace key={`alerts:${env.id}`} environmentId={env.id}
                      environmentLabel={`${project.name} / ${env.code} (${env.kind})`} disabled={busy} onBusyChange={setBusy} />
                    </>}
                    {tab === "projects" && activeSection === "auth" && <>
                      <SectionTabs id="auth-section" label="인증 설정 항목" items={authSections} value={authSection} disabled={busy}
                        onChange={setAuthSection} />
                      <div id="auth-section-panel" role="tabpanel" aria-labelledby={`auth-section-${authSection}`} tabIndex={0}>
                        {authSection === "login" && <>
                    <section className="settings">
                      <div className="section-line">
                        <h3>로그인 설정</h3>
                        <State value={env.state} />
                      </div>
                      {env.state !== "READY" && (
                        <div className="warning">
                          <p>
                            설정이 아직 Keycloak에 반영되지 않아 API 키 사용이
                            차단됩니다. 연결 상태를 확인한 뒤 다시 반영해
                            주세요.
                          </p>
                          <button
                            disabled={busy}
                            onClick={() =>
                              void action(async () => {
                                await api(
                                  `/environments/${env.id}/provision`,
                                  "POST",
                                );
                                reload();
                              }, "반영 요청을 처리했습니다. 환경 상태를 확인해 주세요.")
                            }
                          ><Icon name="refresh-cw"/>
                            다시 반영
                          </button>
                        </div>
                      )}
                      <dl>
                        <div>
                          <dt>일반 회원가입</dt>
                          <dd>
                            {env.registrationAllowed ? "허용" : "허용하지 않음"}
                          </dd>
                        </div>
                        <div>
                          <dt>콜백 URL</dt>
                          <dd>
                            {env.redirectUris.map((uri) => (
                              <div key={uri} className="url">
                                {uri}
                              </div>
                            ))}
                          </dd>
                        </div>
                        <div>
                          <dt>인증 서버 주소</dt>
                          <dd className="url">{env.issuer}</dd>
                        </div>
                      </dl>
                      <button
                        className="secondary"
                        disabled={busy}
                        onClick={() => open({ type: "environment", env })}
                      ><Icon name="settings"/>
                        설정 변경
                      </button>
                    </section>
                        </>}
                        {authSection === "policy" && <>
                    <AuthenticationPolicyPanel
                      key={`policy:${env.id}:${env.state}`}
                      environmentId={env.id}
                      ready={env.state === "READY"} onBusyChange={setBusy}
                    />
                        </>}
                        {authSection === "social" && <>
                    <SocialProviderPanel
                      key={`social:${env.id}:${env.state}`}
                      environmentId={env.id}
                      ready={env.state === "READY"} onBusyChange={setBusy}
                    />
                        </>}
                      </div>
                    </>}
                    {tab === "projects" && activeSection === "members" && <>
                    <MemberPanel
                      key={`members:${env.id}:${env.state}:${project.status}:${memberRefresh}`}
                      environmentId={env.id}
                      ready={env.state === "READY"}
                      suspended={project.status !== "ACTIVE"}
                    />
                    </>}
                    {tab === "projects" && activeSection === "test" && mode === "dev" && env.kind === "DEV" && (
                      <MockLoginPanel
                        key={`${env.id}:${env.state}:${project.status}`}
                        environmentId={env.id}
                        environmentLabel={`${project.name} / ${env.code} (DEV)`}
                        onBusyChange={setBusy}
                        onReset={() => setMemberRefresh(value => value + 1)}
                        disabled={
                          project.status !== "ACTIVE" || env.state !== "READY"
                        }
                      />
                    )}
                    {tab === "projects" && activeSection === "keys" && <>
                    <section className="keys">
                      <div className="section-line">
                        <div>
                          <h3>서버 API 키</h3>
                          <p className="muted small">
                            새 키는 기본적으로 만료되지 않습니다. 키 원문은
                            발급할 때만 표시됩니다. 최근 100개를 표시합니다.
                          </p>
                        </div>
                        <button
                          disabled={
                            busy ||
                            keysLoading ||
                            scopeOptions.length === 0 ||
                            env.state !== "READY" ||
                            project.status !== "ACTIVE"
                          }
                          onClick={() =>
                            open({ type: "issue", environmentId: env.id })
                          }
                        ><Icon name="key-round"/>
                          키 발급
                        </button>
                      </div>
                      {keysLoading ? (
                        <p role="status">키 목록을 불러오는 중…</p>
                      ) : keys.length ? (
                        <ul className="key-list">
                          {keys.map((key) => (
                            <li key={key.id}>
                              <div>
                                <span className="identifier">{key.id}</span>
                                <p className="small muted">
                                  {key.revoked_at
                                    ? `폐기 · ${date(key.revoked_at)}`
                                    : key.expires_at === null
                                      ? "만료 없음"
                                      : `만료 · ${date(key.expires_at)}`}
                                </p>
                                <p className="small muted">
                                  권한 ·{" "}
                                  {key.scopes
                                    .map(
                                      (scope) =>
                                        scopeOptions.find(
                                          (option) => option.code === scope,
                                        )?.label ?? scope,
                                    )
                                    .join(", ")}
                                </p>
                              </div>
                              <button
                                className="quiet danger"
                                disabled={
                                  busy || !!key.revoked_at || keyExpired(key)
                                }
                                onClick={() => open({ type: "revoke", key })}
                              ><Icon name="ban"/>
                                {key.revoked_at
                                  ? "폐기됨"
                                  : keyExpired(key)
                                    ? "만료됨"
                                    : "폐기"}
                              </button>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <p className="empty">
                          발급된 키가 없습니다. 서비스를 연결할 때 발급하세요.
                        </p>
                      )}
                    </section>
                    </>}
                  </>
                )}
              </>
            )}
            </>}
            </div>
          </>
        )}
        </div>
      </main>
      {modal && (
        <Dialog
          title={modalTitle}
          busy={busy}
          close={() => {
            setModal(null);
            setError("");
          }}
        >
          {error && (
            <p className="alert" role="alert">
              {error}
            </p>
          )}
          {modal.type === "secret" ? (
            <>
              <p>
                이 창을 닫으면 원문을 다시 볼 수 없습니다. 서버의 비밀값
                저장소에 보관하세요.
              </p>
              <label>
                발급된 API 키
                <textarea
                  className="secret"
                  readOnly
                  value={modal.secret}
                  rows={4}
                />
              </label>
              <p className="small muted">브라우저 코드나 Git에 넣지 마세요.</p>
              <div className="actions">
                <button
                  onClick={() =>
                    void action(async () => {
                      await navigator.clipboard.writeText(modal.secret);
                    }, "API 키를 복사했습니다.")
                  }
                 aria-label="복사" title="복사" data-tooltip="복사" data-icon-only="true"><Icon name="copy"/></button>
                <button className="secondary" onClick={() => setModal(null)}><Icon name="check"/>
                  확인하고 닫기
                </button>
              </div>
              {notice && <p role="status">{notice}</p>}
            </>
          ) : (
            <form onSubmit={submit}>
              <fieldset disabled={busy}>
                {modal.type === "issue" && (
                  <>
                    <fieldset
                      className="scope-options"
                      aria-describedby="scope-help"
                    >
                      <legend>사용 권한</legend>
                      <p id="scope-help" className="small muted">
                        필요한 권한만 선택하세요. 권한을 바꾸려면 새 키를
                        발급하고 기존 키를 폐기합니다.
                      </p>
                      {scopeOptions.map((scope) => (
                        <label
                          className="checkbox scope-option"
                          key={scope.code}
                        >
                          <input
                            type="checkbox"
                            name="scopes"
                            value={scope.code}
                            defaultChecked={scope.code === "integration:read"}
                            aria-describedby={`scope-${scope.code}`}
                          />
                          <span>
                            {scope.label}
                            <span className="hint" id={`scope-${scope.code}`}>
                              {scope.description}
                            </span>
                          </span>
                        </label>
                      ))}
                    </fieldset>
                    <label>
                      만료 설정
                      <select
                        value={keyHasExpiry ? "date" : "never"}
                        onChange={(e) =>
                          setKeyHasExpiry(e.target.value === "date")
                        }
                      >
                        <option value="never">만료 없음 (기본)</option>
                        <option value="date">만료일 지정</option>
                      </select>
                    </label>
                    {keyHasExpiry && (
                      <label>
                        만료일시
                        <input
                          name="expiresAt"
                          type="datetime-local"
                          required
                        />
                        <span className="hint">
                          현재 기기 시간대(
                          {Intl.DateTimeFormat().resolvedOptions().timeZone})
                          기준입니다.
                        </span>
                      </label>
                    )}
                    <p className="small muted">
                      만료일을 지정하면 해당 시점부터 키를 사용할 수 없습니다.
                      키 원문은 발급 직후에만 표시됩니다.
                    </p>
                  </>
                )}
                {(modal.type === "project" || modal.type === "edit") && (
                  <label>
                    프로젝트 이름
                    <input
                      name="name"
                      required
                      maxLength={120}
                      defaultValue={
                        modal.type === "edit" ? modal.project.name : ""
                      }
                      placeholder="예: 개인 블로그"
                      autoFocus
                    />
                  </label>
                )}
                {modal.type === "project" && (
                  <label>
                    프로젝트 코드
                    <input
                      name="code"
                      required
                      pattern="[a-z][a-z0-9\-]{1,39}"
                      minLength={2}
                      maxLength={40}
                      placeholder="예: my-blog"
                    />
                    <span className="hint">
                      영문 소문자로 시작하는 2~40자. 숫자와 -를 사용할 수
                      있습니다. 생성 후 변경할 수 없습니다.
                    </span>
                  </label>
                )}
                {modal.type === "project" && <div><label className="checkbox"><input type="checkbox" name="filesEnabled" aria-describedby="new-project-files-hint"/>파일 서비스 사용</label><p className="hint" id="new-project-files-hint">업로드·미리보기·파일 공유를 사용합니다. 나중에 프로젝트 설정에서 변경할 수 있습니다.</p></div>}
                {modal.type === "environment" && (
                  <>
                    {!modal.env && (
                      <div className="form-grid">
                        <label>
                          환경 코드
                          <input
                            name="code"
                            required
                            pattern="[a-z][a-z0-9\-]{1,39}"
                            minLength={2}
                            maxLength={40}
                            placeholder="예: development"
                            autoFocus
                          />
                          <span className="hint">
                            소문자로 시작하는 2~40자, 숫자·- 허용
                          </span>
                        </label>
                        <label>
                          환경 종류
                          <select name="kind">
                            <option value="DEV">DEV · 개발</option>
                            <option value="PROD">PROD · 운영</option>
                          </select>
                        </label>
                      </div>
                    )}
                    <label>
                      로그인 콜백 URL
                      <textarea
                        name="redirects"
                        required
                        rows={4}
                        defaultValue={modal.env?.redirectUris.join("\n")}
                        placeholder="https://my-service.example/callback"
                      />
                      <span className="hint">
                        한 줄에 하나씩, 최대 10개. 정확한 HTTPS 주소를
                        입력하세요. DEV는 localhost의 HTTP도 허용합니다.
                      </span>
                    </label>
                    <label className="checkbox">
                      <input
                        name="registration"
                        type="checkbox"
                        defaultChecked={modal.env?.registrationAllowed ?? false}
                      />
                      이 환경에서 일반 회원가입 허용
                    </label>
                    <p className="small muted">
                      프로젝트·환경마다 계정이 분리됩니다. 코드와 환경 종류는
                      생성 후 변경할 수 없습니다.
                    </p>
                  </>
                )}
                {modal.type === "status" && (
                  <>
                    <p>
                      <strong>{modal.project.name}</strong> 프로젝트를{" "}
                      {modal.project.status === "ACTIVE" ? "중지" : "재개"}
                      합니다.
                    </p>
                    <p>
                      {modal.project.status === "ACTIVE"
                        ? "API 키 사용을 차단하고 각 로그인 영역을 비활성화합니다. 기존 로그인 세션도 종료합니다."
                        : "각 로그인 영역을 활성화하고, 반영이 완료된 환경의 유효한 API 키를 다시 사용할 수 있게 합니다."}
                    </p>
                    <p className="warning">
                      외부 서비스가 이미 발급된 토큰을 자체 검증하면 만료 전까지
                      사용할 수 있습니다. 현재 기본 토큰 수명은 5분입니다.
                      실패한 환경은 별도로 다시 반영해야 합니다.
                    </p>
                  </>
                )}
                {modal.type === "revoke" && (
                  <>
                    <p>
                      이 키를 사용하는 서비스의 API 호출이 차단됩니다. 폐기한
                      키는 복구할 수 없습니다.
                    </p>
                    <p className="identifier">{modal.key.id}</p>
                  </>
                )}
                <div className="form-actions">
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setModal(null);
                      setError("");
                    }}
                  ><Icon name="x"/>
                    취소
                  </button>
                  <button
                    className={
                      modal.type === "revoke" ||
                      (modal.type === "status" &&
                        modal.project.status === "ACTIVE")
                        ? "destructive"
                        : ""
                    }
                  ><Icon name="check"/>
                    {busy
                      ? "처리 중…"
                      : modal.type === "status"
                        ? modal.project.status === "ACTIVE"
                          ? "중지하기"
                          : "재개하기"
                        : modal.type === "revoke"
                          ? "폐기하기"
                          : modal.type === "issue"
                            ? "발급하기"
                            : "저장"}
                  </button>
                </div>
              </fieldset>
            </form>
          )}
        </Dialog>
      )}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
