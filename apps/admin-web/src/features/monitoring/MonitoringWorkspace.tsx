import { useState } from "react";
import { SectionTabs } from "../../shared/SectionTabs";
import { JobMonitoring } from "./JobMonitoring";
import { JobBacklogSettings } from "../jobs/JobBacklogSettings";

const tabs = [{ value: "status", label: "작업 현황" }, { value: "backlog", label: "적체 경보 설정" }] as const;
export function MonitoringWorkspace(props: {
  environmentId: string; environmentLabel: string; disabled: boolean;
  onBusyChange: (busy: boolean) => void; openJobs: (state: string) => void; openAlerts: () => void;
}) {
  const [tab, setTab] = useState<typeof tabs[number]["value"]>("status");
  return <><SectionTabs id="monitoring-section" label="모니터링 항목" items={tabs} value={tab} onChange={setTab} disabled={props.disabled} />
    <div id="monitoring-section-panel" role="tabpanel" aria-labelledby={`monitoring-section-${tab}`}>
      {tab === "status" ? <JobMonitoring {...props} /> : <JobBacklogSettings {...props} />}
    </div></>;
}
