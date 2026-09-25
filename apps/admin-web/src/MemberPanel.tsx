import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "./auth";
import { Dialog } from "./Dialog";

type Member = {
  id: string; username: string; email: string | null; firstName: string | null;
  lastName: string | null; enabled: boolean; emailVerified: boolean; createdTimestamp: number | null;
};
type Page = { items: Member[]; hasMore: boolean };
type Detail = { user: Member; providers: string[] };
type Session = { id: string; ipAddress: string; start: number; lastAccess: number; clients: string[] };
type Action = { type: "state" } | { type: "sessions"; sessionId?: string };
const date = (value: number | null) => value == null ? "정보 없음" : new Date(value).toLocaleString("ko-KR");
const errorText = (e: unknown) => e instanceof Error ? e.message : "처리하지 못했습니다. 새로고침 후 확인해 주세요.";
const providers: Record<string, string> = {
  google: "구글", kakao: "카카오", naver: "네이버",
  "platform-google": "구글", "platform-kakao": "카카오", "platform-naver": "네이버",
};

export function MemberPanel({ environmentId, ready, suspended }: {
  environmentId: string; ready: boolean; suspended: boolean;
}) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState({ search: "", offset: 0, reload: 0 });
  const [page, setPage] = useState<Page | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const listHeading = useRef<HTMLHeadingElement>(null);
  const path = `/environments/${environmentId}/users`;
  useEffect(() => {
    if (!ready) return;
    let active = true;
    setLoading(true); setError(""); setPage(null);
    const params = new URLSearchParams({ search: query.search, offset: String(query.offset), limit: "20" });
    api<Page>(`${path}?${params}`).then(result => { if (active) setPage(result); })
      .catch(e => { if (active) setError(errorText(e)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [path, query, ready]);
  function find(event: FormEvent) {
    event.preventDefault();
    setQuery({ search: search.trim(), offset: 0, reload: query.reload + 1 });
  }
  return <section className="member-panel" aria-labelledby="member-title">
    <div className="section-line">
      <h3 id="member-title" ref={listHeading} tabIndex={-1}>회원·세션 관리</h3>
      {!selected && <button className="secondary" disabled={!ready || loading}
        onClick={() => setQuery({ ...query, reload: query.reload + 1 })}>회원 새로고침</button>}
    </div>
    <p className="small muted">현재 환경의 회원을 조회하고 로그인 허용 상태와 세션을 관리합니다.</p>
    {!ready ? <p className="warning">로그인 설정을 먼저 반영한 뒤 회원을 관리할 수 있습니다.</p> : selected ?
      <MemberDetail key={selected} path={`${path}/${selected}`} suspended={suspended} back={() => {
        setSelected(null); setQuery({ ...query, reload: query.reload + 1 });
        listHeading.current?.focus();
      }} /> : <>
        <form className="member-search" onSubmit={find}>
          <label>회원 검색<input type="search" value={search} maxLength={200}
            placeholder="아이디, 이메일 또는 이름" onChange={e => setSearch(e.target.value)} /></label>
          <button type="submit" disabled={loading}>검색</button>
        </form>
        {loading && <p role="status">회원을 불러오는 중…</p>}
        {error && <p className="alert" role="alert">{error}</p>}
        {page && (page.items.length ? <div className="project-list">
          {page.items.map(user => <button key={user.id} className="project-row" onClick={() => setSelected(user.id)}>
            <span className="project-label"><strong>{user.username}</strong><span>{user.email || "이메일 없음"}</span></span>
            <span className={`state ${user.enabled ? "active" : "suspended"}`}>{user.enabled ? "활성" : "비활성"}</span>
            <span className="small">상세 보기</span>
          </button>)}
        </div> : <p className="empty">{query.search ? "검색 결과가 없습니다. 검색어를 바꿔 보세요." : "이 환경에 등록된 회원이 없습니다."}</p>)}
        <div className="pagination" aria-label="회원 목록 페이지">
          <button className="secondary" disabled={loading || query.offset === 0}
            onClick={() => setQuery({ ...query, offset: Math.max(0, query.offset - 20) })}>이전</button>
          <span className="small">페이지 {query.offset / 20 + 1}</span>
          <button className="secondary" disabled={loading || !page?.hasMore}
            onClick={() => setQuery({ ...query, offset: query.offset + 20 })}>다음</button>
        </div>
      </>}
  </section>;
}

function MemberDetail({ path, suspended, back }: { path: string; suspended: boolean; back: () => void }) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [action, setAction] = useState<Action | null>(null);
  const [actionError, setActionError] = useState("");
  const live = useRef(true);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    live.current = true;
    heading.current?.focus();
    return () => { live.current = false; };
  }, []);
  useEffect(() => {
    let active = true;
    if (reload > 0) heading.current?.focus();
    setLoading(true); setError(""); setDetail(null); setSessions([]);
    Promise.all([api<Detail>(path), api<Session[]>(`${path}/sessions`)]).then(([user, rows]) => {
      if (active) { setDetail(user); setSessions(rows); }
    }).catch(e => { if (active) setError(errorText(e)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [path, reload]);
  async function execute() {
    if (!detail || !action || busy || actionError) return;
    setBusy(true); setMessage("");
    try {
      if (action.type === "state") {
        await api(`${path}/state`, "PUT", { enabled: !detail.user.enabled, expectedEnabled: detail.user.enabled });
      } else {
        await api(`${path}/sessions${action.sessionId ? `/${action.sessionId}` : ""}`, "DELETE");
      }
      if (live.current) {
        setMessage(action.type === "state" ? "회원 상태를 변경했습니다." : "세션 종료 요청을 처리했습니다.");
        setAction(null); setReload(value => value + 1);
      }
    } catch (e) {
      if (live.current) setActionError(`${errorText(e)} 일부 작업이 반영되었을 수 있습니다. 닫은 뒤 회원·세션을 새로고침해 확인하세요.`);
    } finally { if (live.current) setBusy(false); }
  }
  function confirm(next: Action) { setActionError(""); setAction(next); }
  const user = detail?.user;
  const title = action?.type === "state" ? (user?.enabled ? "회원 비활성화" : "회원 활성화") :
    action?.sessionId ? "선택한 세션 종료" : "전체 세션 종료";
  return <div className="member-detail">
    <div className="section-line">
      <h4 ref={heading} tabIndex={-1}>회원 상세</h4>
      <div className="actions">
        <button className="quiet" disabled={busy} onClick={back}>목록으로</button>
        <button className="secondary" disabled={busy || loading} onClick={() => {
          setMessage(""); setReload(value => value + 1);
        }}>회원·세션 새로고침</button>
      </div>
    </div>
    {loading && <p role="status">회원 정보와 세션을 불러오는 중…</p>}
    {error && <p role="alert" className="alert">{error}</p>}
    {message && <p role="status" className="notice">{message}</p>}
    {user && <>
      <dl>
        <div><dt>아이디</dt><dd>{user.username}</dd></div>
        <div><dt>회원 ID</dt><dd className="identifier">{user.id}</dd></div>
        <div><dt>이름</dt><dd>{[user.firstName, user.lastName].filter(Boolean).join(" ") || "등록되지 않음"}</dd></div>
        <div><dt>이메일</dt><dd>{user.email || "등록되지 않음"}{user.email && <span className="small muted"> · {user.emailVerified ? "인증됨" : "미인증"}</span>}</dd></div>
        <div><dt>연결된 소셜 계정</dt><dd>{detail.providers.map(code => providers[code] || code).join(", ") || "없음"}</dd></div>
        <div><dt>가입일</dt><dd>{date(user.createdTimestamp)}</dd></div>
        <div><dt>로그인 허용 상태</dt><dd className={`state ${user.enabled ? "active" : "suspended"}`}>{user.enabled ? "활성" : "비활성"}</dd></div>
      </dl>
      <button className={user.enabled ? "secondary danger" : "secondary"} disabled={busy || (!user.enabled && suspended)}
        onClick={() => confirm({ type: "state" })}>{user.enabled ? "회원 비활성화" : "회원 활성화"}</button>
      {suspended && <p className="small muted">프로젝트가 중지되어 회원 재활성화는 제한됩니다. 조회·비활성화·세션 종료는 가능합니다.</p>}
      <div className="section-line"><h4>로그인 세션 {sessions.length}개</h4>
        <button className="secondary danger" disabled={busy} onClick={() => confirm({ type: "sessions" })}>전체 세션 종료</button>
      </div>
      <p className="small muted">목록에는 온라인 세션만 표시합니다. 전체 종료는 오프라인 세션도 포함합니다. 연동 서비스가 자체 검증하는 기존 접근 토큰은 만료 전까지 유효할 수 있습니다.</p>
      {sessions.length ? <ul className="key-list member-sessions">{sessions.map(session => <li key={session.id}>
        <div><strong>{session.clients.join(", ") || "연결 앱 정보 없음"}</strong>
          <p className="identifier">{session.id}</p><p className="small muted">IP {session.ipAddress || "정보 없음"}</p>
          <p className="small">로그인 {date(session.start)}<br />마지막 접근 {date(session.lastAccess)}</p></div>
        <button className="secondary danger" disabled={busy} aria-label={`세션 종료 ${session.id}`}
          onClick={() => confirm({ type: "sessions", sessionId: session.id })}>종료</button>
      </li>)}</ul> : <p className="empty">현재 온라인 로그인 세션이 없습니다.</p>}
    </>}
    {action && user && <Dialog title={title} busy={busy} close={() => {
      setAction(null);
      if (actionError) setReload(value => value + 1);
    }}>
      <p className="identifier">{user.username}</p>
      {action.type === "state" ? <p>{user.enabled ? "이 회원의 새 로그인을 차단하고 기존 세션을 모두 종료합니다. 회원 정보는 보존됩니다." : "이 회원이 다시 로그인할 수 있도록 허용합니다. 종료된 세션은 복원되지 않습니다."}</p> :
        <p>{action.sessionId ? "선택한 로그인 세션을 종료합니다." : "이 회원의 온라인·오프라인 세션을 모두 종료합니다."} 회원이 다시 로그인하는 것은 허용됩니다.</p>}
      {action.type === "sessions" && action.sessionId && <p className="identifier">{action.sessionId}</p>}
      <p className="small muted">이미 발급된 접근 토큰은 연동 서비스의 검증 방식에 따라 만료 전까지 유효할 수 있습니다.</p>
      {actionError && <p className="alert" role="alert">{actionError}</p>}
      <div className="form-actions"><button className="secondary" disabled={busy} onClick={() => {
        setAction(null); if (actionError) setReload(value => value + 1);
      }}>{actionError ? "닫고 새로고침" : "취소"}</button>
        <button className={action.type === "state" && !user.enabled ? "" : "destructive"}
          disabled={busy || !!actionError} onClick={() => void execute()}>{busy ? "처리 중…" : title}</button></div>
    </Dialog>}
  </div>;
}
