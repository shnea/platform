package kr.shnea.platform.project;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class AdminAiController {
    private final ProjectService projects;
    private final AiGateway ai;
    private final AiJobs jobs;
    private final AiIndexing indexing;
    AdminAiController(ProjectService projects, AiGateway ai, AiJobs jobs, AiIndexing indexing) {
        this.projects=projects; this.ai=ai; this.jobs=jobs; this.indexing=indexing;
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/raya/route", consumes="application/json")
    ResponseEntity<?> route(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        projects.administratorAi(environmentId);
        return reply(ai.route(AiController.read(request,AiGateway.ROUTE_LIMIT)),200);
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/embeddings", consumes="application/json")
    ResponseEntity<?> embeddings(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        projects.administratorAi(environmentId);
        return reply(ai.embeddings(AiController.read(request,AiGateway.EMBEDDING_LIMIT)),200);
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/jobs", consumes="application/json")
    ResponseEntity<?> submit(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        var context=projects.administratorAi(environmentId);
        var result=jobs.submit(context,AiController.read(request,AiGateway.JOB_LIMIT));
        projects.auditAi(environmentId,request.getUserPrincipal().getName(),"noedaeri.ai.submitted");
        return reply(result,active(result.path("status").asText())?202:200);
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/translations", consumes="application/json")
    ResponseEntity<?> translate(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        var context=projects.administratorAi(environmentId);
        var result=jobs.translate(context,AiController.read(request,AiGateway.ROUTE_LIMIT));
        projects.auditAi(environmentId,request.getUserPrincipal().getName(),"noedaeri.translation.submitted");
        return reply(result,202);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/jobs")
    ResponseEntity<?> list(@PathVariable UUID environmentId, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="50") int limit, HttpServletRequest request) {
        query(request,Set.of("status","limit"));
        return reply(jobs.list(projects.administratorAi(environmentId),status,limit),200);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/jobs/{id}")
    ResponseEntity<?> get(@PathVariable UUID environmentId, @PathVariable UUID id) {
        return reply(jobs.get(projects.administratorAi(environmentId),id),200);
    }
    @PostMapping("/api/v1/admin/environments/{environmentId}/ai/jobs/{id}/cancel")
    ResponseEntity<?> cancel(@PathVariable UUID environmentId, @PathVariable UUID id, HttpServletRequest request) {
        var result=jobs.cancel(projects.administratorAi(environmentId),id);
        projects.auditAi(environmentId,request.getUserPrincipal().getName(),"noedaeri.ai.cancelled");
        return reply(result,200);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/usage")
    ResponseEntity<?> usage(@PathVariable UUID environmentId, @RequestParam(name="task_type",required=false) String task,
            @RequestParam(defaultValue="50") int limit, HttpServletRequest request) {
        query(request,Set.of("task_type","limit"));
        return reply(jobs.usage(projects.administratorAi(environmentId),task,limit),200);
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/indexing", consumes="application/json")
    ResponseEntity<?> index(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        var context=projects.administratorAi(environmentId);
        byte[] body=AiController.read(request,AiGateway.INDEX_LIMIT);
        var input=ai.parse(body,AiGateway.INDEX_LIMIT);
        if(Set.of("replace_all","delete").contains(input.path("mode").asText())
                &&!input.path("collection").asText("portfolio").equals(request.getHeader("X-Confirm-Collection")))
            throw ApiCode.AI_INVALID_REQUEST.failure();
        var result=indexing.submit(context,body);
        projects.auditAi(environmentId,request.getUserPrincipal().getName(),"noedaeri.indexing.submitted");
        return reply(result,active(result.path("status").asText())?202:200);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/indexing")
    ResponseEntity<?> indexes(@PathVariable UUID environmentId, @RequestParam(required=false) String collection,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        query(request,Set.of("collection","status","limit"));
        return reply(indexing.list(projects.administratorAi(environmentId),collection,status,limit),200);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/indexing/{id}")
    ResponseEntity<?> indexJob(@PathVariable UUID environmentId, @PathVariable UUID id) {
        return reply(indexing.get(projects.administratorAi(environmentId),id),200);
    }
    @PostMapping("/api/v1/admin/environments/{environmentId}/ai/indexing/{id}/cancel")
    ResponseEntity<?> cancelIndex(@PathVariable UUID environmentId, @PathVariable UUID id, HttpServletRequest request) {
        var result=indexing.cancel(projects.administratorAi(environmentId),id);
        projects.auditAi(environmentId,request.getUserPrincipal().getName(),"noedaeri.indexing.cancelled");
        return reply(result,200);
    }
    @GetMapping("/api/v1/admin/environments/{environmentId}/ai/indexing/collections")
    ResponseEntity<?> collections(@PathVariable UUID environmentId, HttpServletRequest request) {
        query(request,Set.of());
        return reply(indexing.collections(projects.administratorAi(environmentId)),200);
    }
    @PostMapping(value="/api/v1/admin/environments/{environmentId}/ai/indexing/search", consumes="application/json")
    ResponseEntity<?> search(@PathVariable UUID environmentId, HttpServletRequest request) throws IOException {
        var context=projects.administratorAi(environmentId);
        return reply(indexing.search(context,AiController.read(request,AiGateway.INDEX_LIMIT)),200);
    }
    private static boolean active(String state) { return Set.of("pending","running").contains(state); }
    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((name,values)->{if(!allowed.contains(name)||values.length!=1)throw ApiCode.AI_INVALID_REQUEST.failure();});
    }
    private static ResponseEntity<?> reply(Object body,int status) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(body); }
}
