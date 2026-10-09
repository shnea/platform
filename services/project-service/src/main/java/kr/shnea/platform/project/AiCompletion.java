package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
class AiCompletion {
    private static final int LIMIT = 128 * 1024;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ProjectService projects;
    private final String secret;
    private final JsonMapper json = JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    AiCompletion(JdbcTemplate db, TransactionTemplate tx, ProjectService projects,
            @Value("${platform.noedaeri.webhook-secret:}") String secret) {
        this.db = db; this.tx = tx; this.projects = projects; this.secret = secret;
    }

    @PostMapping(value="/api/webhooks/noedaeri/ai", consumes="application/json")
    ResponseEntity<?> receive(HttpServletRequest request) throws Exception {
        if (secret == null || secret.isBlank()) throw ApiCode.AI_DELIVERY_NOT_CONFIGURED.failure();
        byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        verify(bytes, request.getHeader("X-Noedaeri-Timestamp"), request.getHeader("X-Noedaeri-Signature"), secret, Instant.now().getEpochSecond());
        JsonNode event;
        try { event = json.readTree(bytes); }
        catch (RuntimeException error) { throw ApiCode.AI_EVENT_INVALID.failure(); }
        if (event == null || !event.isObject()) invalid();
        AiGateway.fields(event, Set.of("version", "source", "event_id", "type", "job_id", "request_id", "project", "environment",
            "occurred_at", "job", "job_path", "result_path", "receipt_path"));
        UUID eventId = uuid(event.path("event_id")), jobId = uuid(event.path("job_id"));
        UUID project = uuid(event.path("project")), environment = uuid(event.path("environment"));
        String requestId = AiGateway.text(event.path("request_id"), 128, true);
        if (requestId.codePoints().anyMatch(Character::isISOControl)) invalid();
        JsonNode job = event.path("job");
        AiGateway.fields(job, Set.of("task_type", "status", "error_code", "expires_at"));
        String task = job.path("task_type").asText(), status = job.path("status").asText();
        if (!event.path("version").isIntegralNumber() || !event.path("version").canConvertToInt() || event.path("version").asInt() != 1
                || !event.path("source").asText().equals("ai") || !AiGateway.TASKS.contains(task)
                || !Set.of("succeeded", "failed", "cancelled").contains(status)
                || !event.path("type").asText().equals("ai.job." + status)
                || !eventId.toString().equals(request.getHeader("X-Noedaeri-Event-ID"))) invalid();
        String path = "/api/v1/ai/jobs/" + jobId;
        if (!event.path("job_path").asText().equals(path)) invalid();
        if (status.equals("succeeded")) {
            if (!event.path("result_path").asText().equals(path) || !event.path("receipt_path").asText().equals(path + "/receipt")) invalid();
        } else if (event.hasNonNull("result_path") || event.hasNonNull("receipt_path")) invalid();
        Instant occurred = timestamp(event.path("occurred_at"), false), expires = timestamp(job.path("expires_at"), true);
        String error = null;
        if (job.hasNonNull("error_code")) {
            if (!job.path("error_code").isString() || !job.path("error_code").asText().matches("[a-z0-9_.]{1,80}")) invalid();
            error = job.path("error_code").asText();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String errorCode = error;
        Map<String,Object> reply = tx.execute(transaction -> {
            var ledger = db.queryForList("SELECT l.remote_id,l.task_type,l.notify FROM ai_request_ledger l JOIN environments e ON e.id=l.environment_id "
                + "WHERE l.environment_id=? AND l.request_id=? AND e.project_id=? FOR UPDATE OF l", environment, requestId, project);
            if (ledger.isEmpty()) throw ApiCode.AI_JOB_NOT_FOUND.failure();
            var row = ledger.getFirst();
            if (!Boolean.TRUE.equals(row.get("notify")) || !task.equals(row.get("task_type"))
                    || row.get("remote_id") != null && !jobId.equals(row.get("remote_id"))) invalid();
            var existing = db.queryForList("SELECT event_id,body_hash FROM ai_completion_inbox WHERE event_id=? OR job_id=?", eventId, jobId);
            if (!existing.isEmpty()) {
                if (existing.size() != 1 || !eventId.equals(existing.getFirst().get("event_id")) || !hash.equals(existing.getFirst().get("body_hash")))
                    throw ApiCode.AI_EVENT_CONFLICT.failure();
                return Map.of("accepted", true, "duplicate", true);
            }
            db.update("UPDATE ai_request_ledger SET remote_id=? WHERE environment_id=? AND request_id=?", jobId, environment, requestId);
            db.update("INSERT INTO ai_completion_inbox(event_id,job_id,environment_id,request_id,task_type,status,body_hash,occurred_at,expires_at,error_code) "
                + "VALUES (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)", eventId, jobId, environment, requestId, task, status, hash,
                occurred.toString(), expires == null ? null : expires.toString(), errorCode);
            return Map.of("accepted", true, "duplicate", false);
        });
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reply);
    }

    @GetMapping("/api/v1/ai/events")
    ResponseEntity<?> events(@RequestHeader(value="X-Platform-Key", required=false) String key,
            @RequestParam(defaultValue="50") int limit, HttpServletRequest request) {
        var context = projects.context(key, "ai:jobs:read");
        request.getParameterMap().forEach((name, values) -> {
            if (!name.equals("limit") || values.length != 1) throw ApiCode.AI_INVALID_REQUEST.failure();
        });
        if (limit < 1 || limit > 100) throw ApiCode.AI_INVALID_REQUEST.failure();
        var result = json.createArrayNode();
        for (var row : db.queryForList("SELECT * FROM ai_completion_inbox WHERE environment_id=? ORDER BY created_at DESC,event_id LIMIT ?", context.environmentId(), limit)) {
            var item = result.addObject().put("version", 1).put("source", "ai").put("event_id", row.get("event_id").toString())
                .put("job_id", row.get("job_id").toString()).put("request_id", row.get("request_id").toString())
                .put("project", context.projectId().toString()).put("environment", context.environmentId().toString())
                .put("task_type", row.get("task_type").toString()).put("status", row.get("status").toString())
                .put("type", "ai.job." + row.get("status"));
            for (String field : List.of("occurred_at", "expires_at", "created_at", "received_at")) {
                var value = (java.sql.Timestamp) row.get(field); item.put(field, value == null ? null : value.toInstant().toString());
            }
            item.put("error_code", (String) row.get("error_code"));
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }

    static void verify(byte[] body, String timestamp, String signature, String secret, long now) throws Exception {
        if (secret == null || secret.isBlank() || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || signature == null || !signature.matches("sha256=[0-9a-f]{64}") || Math.abs(now - Long.parseLong(timestamp)) > 300)
            throw ApiCode.AI_EVENT_SIGNATURE_INVALID.failure();
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7)))) throw ApiCode.AI_EVENT_SIGNATURE_INVALID.failure();
    }
    private static UUID uuid(JsonNode node) {
        try {
            if (!node.isString()) invalid();
            UUID value = UUID.fromString(node.asText()); if (!value.toString().equals(node.asText())) invalid(); return value;
        } catch (IllegalArgumentException error) { throw ApiCode.AI_EVENT_INVALID.failure(); }
    }
    private static Instant timestamp(JsonNode node, boolean nullable) {
        if (nullable && (node.isNull() || node.isMissingNode())) return null;
        try { if (!node.isString() || node.asText().length() > 64) invalid(); return Instant.parse(node.asText()); }
        catch (java.time.format.DateTimeParseException error) { throw ApiCode.AI_EVENT_INVALID.failure(); }
    }
    private static void invalid() { throw ApiCode.AI_EVENT_INVALID.failure(); }
}
