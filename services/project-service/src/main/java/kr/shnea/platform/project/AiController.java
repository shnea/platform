package kr.shnea.platform.project;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class AiController {
    private final ProjectService projects;
    private final AiGateway ai;
    AiController(ProjectService projects, AiGateway ai) { this.projects = projects; this.ai = ai; }

    @GetMapping("/api/v1/ai/services")
    ResponseEntity<?> services(@RequestHeader(value="X-Platform-Key", required=false) String key) {
        projects.context(key, "ai:read");
        return reply(ai.services());
    }
    @GetMapping("/api/v1/admin/ai/services")
    ResponseEntity<?> adminServices() { return reply(ai.services()); }

    @PostMapping(value="/api/v1/ai/raya/route", consumes="application/json")
    ResponseEntity<?> route(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) throws IOException {
        projects.context(key, "ai:route");
        return reply(ai.route(read(request, AiGateway.ROUTE_LIMIT)));
    }
    @PostMapping(value="/api/v1/ai/embeddings", consumes="application/json")
    ResponseEntity<?> embeddings(@RequestHeader(value="X-Platform-Key", required=false) String key, HttpServletRequest request) throws IOException {
        projects.context(key, "ai:embed");
        return reply(ai.embeddings(read(request, AiGateway.EMBEDDING_LIMIT)));
    }
    private static byte[] read(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        return body;
    }
    private static ResponseEntity<?> reply(Object body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
