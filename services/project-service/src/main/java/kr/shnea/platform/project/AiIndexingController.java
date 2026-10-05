package kr.shnea.platform.project;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class AiIndexingController {
    private final ProjectService projects;
    private final AiIndexing indexing;
    AiIndexingController(ProjectService projects, AiIndexing indexing) { this.projects = projects; this.indexing = indexing; }

    @PostMapping(value="/api/v1/ai/indexing", consumes="application/json")
    ResponseEntity<?> submit(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) throws IOException {
        var result = indexing.submit(projects.context(key, "ai:index:write"), AiController.read(request, AiGateway.INDEX_LIMIT));
        return reply(result, Set.of("pending", "running").contains(result.path("status").asText()) ? 202 : 200);
    }
    @GetMapping("/api/v1/ai/indexing")
    ResponseEntity<?> list(@RequestHeader(value="X-Platform-Key", required=false) String key,
            @RequestParam(required=false) String collection, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request, Set.of("collection", "status", "limit"));
        return reply(indexing.list(projects.context(key, "ai:index:read"), collection, status, limit), 200);
    }
    @GetMapping("/api/v1/ai/indexing/collections")
    ResponseEntity<?> collections(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) {
        query(request, Set.of());
        return reply(indexing.collections(projects.context(key, "ai:index:read")), 200);
    }
    @PostMapping(value="/api/v1/ai/indexing/search", consumes="application/json")
    ResponseEntity<?> search(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) throws IOException {
        return reply(indexing.search(projects.context(key, "ai:index:search"), AiController.read(request, AiGateway.INDEX_LIMIT)), 200);
    }
    @GetMapping("/api/v1/ai/indexing/{id}")
    ResponseEntity<?> get(@RequestHeader(value="X-Platform-Key", required=false) String key, @PathVariable UUID id) {
        return reply(indexing.get(projects.context(key, "ai:index:read"), id), 200);
    }
    @PostMapping("/api/v1/ai/indexing/{id}/cancel")
    ResponseEntity<?> cancel(@RequestHeader(value="X-Platform-Key", required=false) String key, @PathVariable UUID id) {
        return reply(indexing.cancel(projects.context(key, "ai:index:write"), id), 200);
    }
    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1) throw ApiCode.AI_INVALID_REQUEST.failure();
        });
    }
    private static ResponseEntity<?> reply(Object body, int status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
