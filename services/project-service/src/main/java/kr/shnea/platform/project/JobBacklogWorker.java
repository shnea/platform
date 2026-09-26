package kr.shnea.platform.project;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="platform.events.enabled",havingValue="true")
class JobBacklogWorker {
    private final JobBacklog monitor;
    JobBacklogWorker(JobBacklog monitor) {this.monitor=monitor;}
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    void tick() {
        try { for(int i=0;i<100 && monitor.checkOne();i++) { /* Bounded batch; per-environment checks are at least 30 seconds apart. */ } }
        catch(Exception e) { org.slf4j.LoggerFactory.getLogger(JobBacklogWorker.class).warn("job_backlog_check_failed class={}",e.getClass().getName()); }
    }
}
