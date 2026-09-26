export const alertTitles: Record<string, string> = {
  JOB_FAILED: "환경 반영 작업 최종 실패", JOB_RECOVERED: "환경 반영 복구", JOB_BACKLOGGED: "작업 대기 적체",
  JOB_BACKLOG_RECOVERED: "작업 대기 적체 해소", JOB_BACKLOG_CLOSED: "작업 적체 감시 종료",
};
export const alertDescriptions: Record<string, string> = {
  JOB_RECOVERED: "이전 실패 이후의 환경 반영 작업이 성공했습니다. 원래 작업의 실패 이력은 유지합니다.",
  JOB_BACKLOGGED: "대기 시간 기준을 연속 초과했습니다. 작업 상태와 실행 워커를 확인해 주세요.",
  JOB_BACKLOG_RECOVERED: "이 작업이 대기 적체 기준에서 벗어난 것을 연속 확인했습니다. 작업 성공이나 전체 서비스 복구를 뜻하지 않습니다.",
  JOB_BACKLOG_CLOSED: "감시 설정을 바꾸거나 꺼서 이전 적체 감시를 종료했습니다. 실제 적체 해소를 뜻하지 않으며 복구 이메일을 보내지 않습니다.",
};
