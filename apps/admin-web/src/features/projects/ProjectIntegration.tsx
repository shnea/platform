import {useEffect, useState, type FormEvent} from 'react';
import {api} from '../../shared/auth';
import {Icon} from '../../shared/Icon';
import {connectionEntries, connectionEnv, connectionUrl, defaultLogout} from './integration-config';
import './integration.css';

type Member = {id: string; username: string; email: string | null; enabled: boolean};
type MemberPage = {items: Member[]; hasMore: boolean};
type Props = {
  project: {id: string; name: string; code: string};
  environment: {id: string; code: string; kind: string; state: string; issuer: string; redirectUris: string[]};
  disabled: boolean; issueDisabled: boolean; onIssue: (receive: (key: string) => void) => void;
};

export function ProjectIntegration({project, environment, disabled, issueDisabled, onIssue}: Props) {
  const [apiKey, setApiKey] = useState('');
  const [reveal, setReveal] = useState(false);
  const [callback, setCallback] = useState(environment.redirectUris[0] ?? '');
  const [logout, setLogout] = useState(defaultLogout(environment.redirectUris[0] ?? ''));
  const [member, setMember] = useState<Member | null>(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState({search: '', offset: 0, reload: 0});
  const [members, setMembers] = useState<MemberPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const ready = environment.state === 'READY';
  useEffect(() => {
    if (!ready) return;
    let live = true;
    setLoading(true); setError(''); setMembers(null);
    const params = new URLSearchParams({search: query.search, offset: String(query.offset), limit: '20'});
    api<MemberPage>(`/environments/${environment.id}/users?${params}`)
      .then(data => { if (live) setMembers(data); })
      .catch(e => { if (live) setError(e instanceof Error ? e.message : '회원을 불러오지 못했습니다.'); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [environment.id, query, ready]);
  const values = {projectId: project.id, environmentId: environment.id, apiKey, issuer: environment.issuer,
    redirectUri: callback, logoutUri: logout, adminSubject: member?.id ?? ''};
  let preview = '', invalid = '';
  try {
    if (!connectionUrl(callback) || !connectionUrl(logout)) throw new Error('복귀 주소는 사용자 정보·# 조각이 없는 올바른 HTTP(S) URL을 입력하세요.');
    connectionEnv(values);
    preview = connectionEnv({...values, apiKey: apiKey && !reveal ? '•••••••• (입력한 API 키)' : apiKey});
  } catch (e) { invalid = e instanceof Error ? e.message : '입력값을 확인하세요.'; }
  async function copy(value: string, label: string) {
    try { await navigator.clipboard.writeText(value); setNotice(`${label} 복사했습니다.`); }
    catch { setReveal(false); setNotice('자동 복사를 사용할 수 없습니다. 아래 미리보기를 선택해 직접 복사하세요. API 키는 원문 표시를 켜면 확인할 수 있습니다.'); }
  }
  function find(event: FormEvent) {
    event.preventDefault(); setQuery({search: search.trim(), offset: 0, reload: query.reload + 1});
  }
  const candidates = members?.items ?? [];
  const entries = connectionEntries(values);
  return <section className="project-integration" aria-labelledby="integration-title">
    <div className="section-line"><h3 id="integration-title">서비스 연결</h3><span className="state">{environment.kind}</span></div>
    <p className="muted">{project.name} · {environment.code} 환경의 연결 값을 서버 환경변수로 복사하세요. 프로젝트 코드는 <strong>{project.code}</strong>이며 아래 UUID와 다릅니다.</p>
    <dl className="connection-values">
      {entries.filter(([name]) => ['PLATFORM_PROJECT_ID', 'PLATFORM_ENVIRONMENT_ID', 'PLATFORM_OIDC_ISSUER', 'PLATFORM_OIDC_CLIENT_ID'].includes(name)).map(([name, value]) =>
        <div key={name}><dt>{name}</dt><dd><code>{value}</code><button type="button" className="secondary" onClick={() => void copy(value, name + '를')}
          aria-label={`${name} 복사`} title={`${name} 복사`}><Icon name="copy"/></button></dd></div>)}
    </dl>
    <fieldset disabled={disabled}>
      <legend>연결할 서비스 설정</legend>
      <label>서버 API 키 · PLATFORM_API_KEY
        <input type={reveal ? 'text' : 'password'} value={apiKey} autoComplete="off" spellCheck={false}
          placeholder="현재 환경에서 발급한 API 키 원문" onChange={e => {setApiKey(e.target.value.trim()); setNotice('');}} />
      </label>
      <div className="actions">
        <button type="button" className="secondary" disabled={issueDisabled} onClick={() => onIssue(key => {setApiKey(key); setReveal(false); setNotice('새 API 키를 연결 설정에 넣었습니다. 화면을 떠나기 전에 복사하세요.');})}><Icon name="key-round"/>새 API 키 발급</button>
        <label className="checkbox"><input type="checkbox" checked={reveal} onChange={e => setReveal(e.target.checked)}/>API 키 원문 표시</label>
        {apiKey && <button type="button" className="quiet" onClick={() => {setApiKey(''); setReveal(false);}}>입력 지우기</button>}
      </div>
      <p className="small muted">기존 키 원문은 다시 조회할 수 없습니다. 새 키는 필요한 권한을 선택해 발급합니다. 입력한 키는 이 화면 메모리에만 두며 환경·탭 이동과 새로고침 시 지워집니다.</p>
      <label>로그인 복귀 주소 · PLATFORM_OIDC_REDIRECT_URI
        <input type="url" value={callback} list="integration-callbacks" placeholder="https://서비스주소/auth/callback" onChange={e => {setCallback(e.target.value); setNotice('');}} />
      </label>
      <datalist id="integration-callbacks">{environment.redirectUris.map(uri => <option key={uri} value={uri}/>)}</datalist>
      {callback && !environment.redirectUris.includes(callback) && <p className="warning">등록된 콜백 주소가 아닙니다. 인증 설정 → 로그인 주소에서 먼저 등록하세요.</p>}
      <label>로그아웃 복귀 주소 · OIDC_POST_LOGOUT_REDIRECT_URI
        <input type="url" value={logout} placeholder="https://서비스주소/" onChange={e => {setLogout(e.target.value); setNotice('');}} />
      </label>
      <p className="small muted">여기에 입력한 주소는 환경변수에만 반영됩니다. 로그아웃은 등록한 로그인 콜백과 해당 사이트의 홈 주소(/)를 허용합니다. 기존 환경은 인증 설정에서 다시 반영하세요.</p>
      {logout && !environment.redirectUris.some(uri => logout === uri || logout === defaultLogout(uri)) && <p className="warning">현재 로그인 주소에서 허용되는 로그아웃 주소가 아닙니다. 등록한 사이트의 홈 주소(/)를 사용하세요.</p>}
    </fieldset>
    <section className="connection-admin" aria-labelledby="connection-admin-title">
      <h4 id="connection-admin-title">서비스 관리자 계정 <span className="small muted">선택 사항</span></h4>
      <p className="small muted">연결할 서비스가 issuer와 회원 ID(sub)로 관리자를 지정할 때 사용합니다. 플랫폼 관리자 계정이 아니라 현재 환경의 회원을 선택하세요. 선택만으로 권한이 변경되지는 않습니다.</p>
      {!ready ? <p className="warning">환경 설정을 반영한 뒤 회원을 선택할 수 있습니다.</p> : <>
        <form className="member-search" onSubmit={find}><label>관리자로 사용할 회원 검색<input type="search" value={search} maxLength={200} placeholder="아이디 또는 이메일" onChange={e => setSearch(e.target.value)}/></label>
          <button type="submit" disabled={loading || disabled}><Icon name="search"/>검색</button></form>
        {loading && <p role="status">회원을 불러오는 중…</p>}
        {error && <p className="alert" role="alert">{error}</p>}
        <label>관리자 회원<select value={member?.id ?? ''} disabled={loading || disabled} onChange={e => {setMember(candidates.find(item => item.id === e.target.value) ?? null); setNotice('');}}>
          <option value="">지정하지 않음</option>
          {member && !candidates.some(item => item.id === member.id) && <option value={member.id}>{member.username} · 선택됨</option>}
          {candidates.map(item => <option key={item.id} value={item.id} disabled={!item.enabled}>{item.username}{item.email ? ` · ${item.email}` : ''}{!item.enabled ? ' · 비활성' : ''}</option>)}
        </select></label>
        {members && !members.items.length && <p className="small muted">검색 결과가 없습니다. 이 환경에 가입한 회원인지 확인하세요.</p>}
        <div className="pagination" aria-label="관리자 회원 검색 페이지"><button type="button" className="secondary" disabled={loading || disabled || !query.offset} onClick={() => setQuery({...query, offset: Math.max(0, query.offset - 20)})}>이전</button>
          <span className="small">{query.offset / 20 + 1} 페이지</span><button type="button" className="secondary" disabled={loading || disabled || !members?.hasMore} onClick={() => setQuery({...query, offset: query.offset + 20})}>다음</button></div>
      </>}
    </section>
    <div className="section-line"><h4>환경변수 미리보기</h4><button type="button" disabled={disabled || !!invalid}
      onClick={() => void copy(connectionEnv(values), apiKey ? 'API 키를 포함한 환경변수를' : '환경변수 양식을')}><Icon name="copy"/>{apiKey ? 'API 키 포함 .env 복사' : '.env 양식 복사'}</button></div>
    {invalid && <p className="alert" role="alert">{invalid}</p>}
    <label className="connection-preview">서버 .env 설정<textarea readOnly rows={9} value={preview} spellCheck={false} onFocus={e => e.target.select()}/></label>
    <p className="small muted">빈 값은 아직 지정하지 않은 항목입니다. API 키가 포함된 내용은 서버에만 보관하고 Git에 올리지 마세요.</p>
    {notice && <p className="notice" role="status">{notice}</p>}
  </section>;
}
