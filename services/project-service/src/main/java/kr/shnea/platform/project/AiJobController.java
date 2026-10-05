package kr.shnea.platform.project;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class AiJobController {
    private final ProjectService projects;
    private final AiJobs jobs;
    AiJobController(ProjectService projects, AiJobs jobs) { this.projects = projects; this.jobs = jobs; }

    @PostMapping(value="/api/v1/ai/jobs", consumes="application/json")
    ResponseEntity<?> submit(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) throws IOException {
        var context = projects.context(key, "ai:execute");
        return reply(jobs.submit(context, AiController.read(request, AiGateway.JOB_LIMIT)));
    }
    @GetMapping("/api/v1/ai/jobs")
    ResponseEntity<?> list(@RequestHeader(value="X-Platform-Key", required=false) String key,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="50") int limit, HttpServletRequest request) {
        var context = projects.context(key, "ai:jobs:read"); query(request, Set.of("status", "limit"));
        return reply(jobs.list(context, status, limit));
    }
    @GetMapping("/api/v1/ai/jobs/{id}")
    ResponseEntity<?> get(@RequestHeader(value="X-Platform-Key", required=false) String key, @PathVariable UUID id) {
        return reply(jobs.get(projects.context(key, "ai:jobs:read"), id));
    }
    @PostMapping("/api/v1/ai/jobs/{id}/cancel")
    ResponseEntity<?> cancel(@RequestHeader(value="X-Platform-Key", required=false) String key, @PathVariable UUID id) {
        return reply(jobs.cancel(projects.context(key, "ai:cancel"), id));
    }
    @GetMapping("/api/v1/ai/usage")
    ResponseEntity<?> usage(@RequestHeader(value="X-Platform-Key", required=false) String key,
            @RequestParam(name="task_type", required=false) String task, @RequestParam(defaultValue="50") int limit, HttpServletRequest request) {
        var context = projects.context(key, "ai:usage"); query(request, Set.of("task_type", "limit"));
        return reply(jobs.usage(context, task, limit));
    }
    private static void query(HttpServletRequest request, Set<String> names) {
        request.getParameterMap().forEach((name, values) -> {
            if (!names.contains(name) || values.length != 1) throw ApiCode.AI_INVALID_REQUEST.failure();
        });
    }
    private static ResponseEntity<?> reply(Object body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
