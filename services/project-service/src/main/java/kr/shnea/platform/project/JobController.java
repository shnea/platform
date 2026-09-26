package kr.shnea.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import kr.shnea.platform.http.RequestTrace;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class JobController {
    private final ProvisionJobs jobs;
    JobController(ProvisionJobs jobs) { this.jobs = jobs; }
    @PostMapping("/api/v1/admin/environments/{id}/provision-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    ProvisionJobs.Job enqueue(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor, HttpServletRequest request) {
        return jobs.enqueue(id, actor.getSubject(), RequestTrace.id(request));
    }
    @GetMapping("/api/v1/admin/jobs")
    List<ProvisionJobs.Job> jobs(@RequestParam(required=false) UUID environmentId, @RequestParam(required=false) String state,
            @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset,
            @RequestParam(required=false) Instant createdFrom, @RequestParam(required=false) Instant createdTo) {
        return jobs.list(environmentId, state, limit, offset, createdFrom, createdTo);
    }
    @GetMapping("/api/v1/admin/jobs/{id}")
    ProvisionJobs.Detail detail(@PathVariable UUID id) { return jobs.detail(id); }
    @PostMapping("/api/v1/admin/jobs/{id}/cancel")
    ProvisionJobs.Job cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) { return jobs.cancel(id, actor.getSubject()); }
    @PostMapping("/api/v1/admin/jobs/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    ProvisionJobs.Job retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor, HttpServletRequest request) {
        return jobs.retry(id, actor.getSubject(), RequestTrace.id(request));
    }
}
