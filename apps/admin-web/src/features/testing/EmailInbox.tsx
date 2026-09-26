import {Icon} from '../../shared/Icon';
import { useEffect, useState } from "react";
import { api } from "../../shared/auth";

type Mail = { id: string; recipient: string; subject: string; textBody: string; createdAt: string };

export function EmailInbox({ environmentId }: { environmentId: string }) {
  const [mails, setMails] = useState<Mail[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true;
    setMails([]); setLoading(true); setError("");
    api<Mail[]>(`/environments/${environmentId}/email-inbox`)
      .then(result => { if (active) setMails(result); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : "메일을 불러오지 못했습니다."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [environmentId, reload]);
  return <div className="email-inbox" aria-labelledby="email-inbox-title">
    <div className="section-line">
      <h4 id="email-inbox-title">개발용 이메일 수신함</h4>
      <button type="button" className="secondary" disabled={loading} onClick={() => setReload(value => value + 1)} aria-label="메일 새로고침" title="메일 새로고침" data-tooltip="메일 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button>
    </div>
    <p className="small muted">외부로 발송하지 않은 인증·복구 메일입니다. 최근 1시간의 메일을 최대 100건 표시합니다. 인증 링크는 테스트 계정에 접근할 수 있으므로 공유하지 마세요.</p>
    {loading && <p role="status">메일을 불러오는 중…</p>}
    {error && <p className="alert" role="alert">{error} 메일 새로고침으로 다시 확인하세요.</p>}
    {!loading && !error && mails.length === 0 && <p>아직 받은 메일이 없습니다. 이메일 인증을 켠 뒤 가입하거나, 로그인 화면에서 비밀번호 찾기를 실행하세요.</p>}
    {mails.map(mail => <details key={mail.id}>
      <summary><strong>{mail.subject}</strong><span className="small muted">{mail.recipient} · {new Date(mail.createdAt).toLocaleString("ko-KR")}</span></summary>
      <p className="small muted">본문의 인증 주소를 새 탭에 붙여넣어 진행하세요. 만료된 링크는 로그인 화면에서 다시 요청해야 합니다.</p>
      <pre className="email-body">{mail.textBody}</pre>
    </details>)}
  </div>;
}
