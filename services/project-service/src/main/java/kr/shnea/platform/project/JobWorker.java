package kr.shnea.platform.project;

import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="platform.jobs.enabled", havingValue="true", matchIfMissing=true)
class JobWorker {
    private final ProvisionJobs jobs;
    JobWorker(ProvisionJobs jobs) { this.jobs = jobs; }

    // ponytail: one operation per process; scale only when measured queue latency requires it.
    @Scheduled(fixedDelay=2000, initialDelay=5000)
    void tick() {
        var previous = MDC.getCopyOfContextMap();
        try {
            var claim = jobs.claim();
            if (claim == null) return;
            MDC.put("requestId", claim.job().requestId());
            MDC.put("jobId", claim.job().id().toString());
            try { jobs.execute(claim); }
            catch (Exception error) {
                LoggerFactory.getLogger(JobWorker.class).warn("job_execution_failed class={}", error.getClass().getName());
                jobs.failed(claim);
            }
        } catch (Exception error) {
            // A database outage leaves the durable claim for lease recovery; never log SQL/credentials.
            LoggerFactory.getLogger(JobWorker.class).warn("job_worker_unavailable class={}", error.getClass().getName());
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
