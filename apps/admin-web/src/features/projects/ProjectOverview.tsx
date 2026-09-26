import { useEffect, useState } from "react";
import { api } from "../../shared/auth";

const states: Record<string, string> = {
  QUEUED: "대기", RUNNING: "실행 중", RETRY_WAIT: "재시도 대기",
  SUCCEEDED: "완료", FAILED: "최종 실패", CANCELLED: "취소됨",
};
export function ProjectOverview({ environmentId, ready, disabled, open }: {
  environmentId: string; ready: boolean; disabled: boolean; open: (view: "files" | "jobs" | "alerts") => void;
}) {
  const [summary, setSummary] = useState<{ job: string | null; unread: boolean } | null>(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let live = true;
    setSummary(null); setError("");
    Promise.all([
      api<{ state: string }[]>(`/jobs?environmentId=${environmentId}&limit=1`),
      api<{ id: string }[]>(`/environments/${environmentId}/operational-alerts?acknowledged=false&limit=1`),
    ]).then(([jobs, alerts]) => {
      if (live) setSummary({ job: jobs[0]?.state ?? null, unread: alerts.length > 0 });
    }).catch(e => { if (live) setError(e instanceof Error ? e.message : "운영 현황을 불러오지 못했습니다."); });
    return () => { live = false; };
  }, [environmentId, reload]);
  return <section aria-labelledby="overview-title">
    <div className="section-line"><h3 id="overview-title">환경 개요</h3></div>
    <p className={ready ? "muted" : "warning"}>{ready
      ? "환경 설정이 반영되었습니다. 위 탭에서 인증 설정, 회원, API 키를 관리하세요."
      : "환경 설정을 반영해야 합니다. 인증 설정을 확인하거나 비동기 작업에서 진행 상태를 확인하세요."}</p>
    {error ? <div className="alert" role="alert">{error} <button className="secondary" disabled={disabled} onClick={() => setReload(n => n + 1)}>현황 다시 조회</button></div>
      : !summary ? <p role="status">운영 현황을 불러오는 중…</p> : null}
    <div className="overview-links">
      <button className="overview-link" disabled={disabled} onClick={() => open("files")}>
        <span><strong>파일</strong><span className="small muted">파일 업로드·재개와 공개 범위 관리</span></span><span>파일 보기</span>
      </button>
      <button className="overview-link" disabled={disabled} onClick={() => open("jobs")}>
        <span><strong>비동기 작업</strong><span className="small muted">{summary
          ? summary.job ? `최근 작업 · ${states[summary.job] ?? summary.job}` : "등록된 작업이 없습니다."
          : "작업 상태와 실패·재시도 이력"}</span></span><span>작업 보기</span>
      </button>
      <button className="overview-link" disabled={disabled} onClick={() => open("alerts")}>
        <span><strong>운영 알림</strong><span className="small muted">{summary
          ? summary.unread ? "미확인 알림이 있습니다." : "미확인 알림이 없습니다."
          : "최종 실패 알림과 확인 기록"}</span></span><span>알림 보기</span>
      </button>
    </div>
  </section>;
}
