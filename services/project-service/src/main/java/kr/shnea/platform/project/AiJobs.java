package kr.shnea.platform.project;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Project-scoped bridge. Only identity/fingerprint metadata is stored locally. */
@Service
class AiJobs {
    private static final Set<String> STATES = Set.of("running", "succeeded", "failed", "cancelled");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AiGateway ai;
    private final JsonMapper json = new JsonMapper();
    AiJobs(JdbcTemplate db, TransactionTemplate tx, AiGateway ai) { this.db = db; this.tx = tx; this.ai = ai; }

    JsonNode submit(ProjectService.Context context, byte[] bytes) {
        ObjectNode request = prepare(context, bytes);
        String requestId = request.path("request_id").asText();
        ObjectNode semantic = request.deepCopy(); semantic.remove("sync");
        String fingerprint = ProjectService.hash(json.writeValueAsString(canonical(semantic)));
        boolean first = Boolean.TRUE.equals(tx.execute(status -> {
            // Serialize identity reservation without holding a transaction during network/model calls.
            db.queryForObject("SELECT id FROM environments WHERE id=? FOR UPDATE", UUID.class, context.environmentId());
            var existing = ledger(context.environmentId(), requestId);
            if (existing != null) {
                if (!fingerprint.equals(existing.get("fingerprint"))) throw ApiCode.AI_REQUEST_CONFLICT.failure();
                return false;
            }
            if (db.queryForObject("SELECT count(*) FROM ai_request_ledger WHERE environment_id=?", Long.class, context.environmentId()) >= 10000)
                throw ApiCode.AI_REQUEST_CAPACITY.failure();
            db.update("INSERT INTO ai_request_ledger(environment_id,request_id,fingerprint) VALUES (?,?,?)", context.environmentId(), requestId, fingerprint);
            return true;
        }));
        if (!first) {
            var saved = ledger(context.environmentId(), requestId);
            if (saved == null) throw ApiCode.AI_BUSY.failure(); // A concurrent local pre-dispatch rejection released the reservation.
            UUID remote = (UUID) saved.get("remote_id");
            if (remote == null) {
                // Recover a lost POST response through bounded lookup, never another execution POST.
                for (JsonNode item : list(context, null, 100)) {
                    if (requestId.equals(item.path("request_id").asText())) {
                        remote = UUID.fromString(item.path("id").asText()); remember(context, requestId, remote); break;
                    }
                }
            }
            if (remote == null) throw ApiCode.AI_REQUEST_UNCONFIRMED.failure();
            ObjectNode result = (ObjectNode) get(context, remote); result.put("reused", true); return result;
        }
        // Once reserved, any timeout/error is uncertain; callers inspect status rather than rerun.
        JsonNode raw;
        try { raw = ai.exchange("POST", "/api/v1/ai/jobs", request, AiGateway.JOB_LIMIT); }
        catch (ApiCode.Failure failure) {
            // Only positively known local rejection may release the ID; upstream errors/timeouts remain uncertain.
            if (!failure.dispatched) db.update("DELETE FROM ai_request_ledger WHERE environment_id=? AND request_id=? AND remote_id IS NULL AND fingerprint=?", context.environmentId(), requestId, fingerprint);
            throw failure;
        }
        ObjectNode result = job(context, raw);
        if (!requestId.equals(result.path("request_id").asText()) || !request.path("task_type").equals(result.path("task_type"))) invalid();
        remember(context, requestId, UUID.fromString(result.path("id").asText()));
        return result;
    }

    ObjectNode prepare(ProjectService.Context context, byte[] bytes) {
        JsonNode input = ai.parse(bytes, AiGateway.JOB_LIMIT);
        AiGateway.fields(input, Set.of("request_id", "task_type", "prompt", "input", "sync", "project", "environment"));
        String id = AiGateway.text(input.get("request_id"), 128, true);
        if (id.codePoints().anyMatch(Character::isISOControl)) throw ApiCode.AI_INVALID_REQUEST.failure();
        String task = AiGateway.text(input.get("task_type"), 64, true);
        if (!AiGateway.TASKS.contains(task)) throw ApiCode.AI_INVALID_REQUEST.failure();
        AiGateway.text(input.get("prompt"), 200000, true);
        for (String field : List.of("project", "environment")) {
            String expected = field.equals("project") ? context.projectId().toString() : context.environmentId().toString();
            if (input.has(field) && !expected.equals(AiGateway.text(input.get(field), 64, true))) throw ApiCode.AI_INVALID_REQUEST.failure();
        }
        if (input.has("sync") && !input.get("sync").isBoolean()) throw ApiCode.AI_INVALID_REQUEST.failure();
        if (input.has("input") && !input.get("input").isObject()) throw ApiCode.AI_INVALID_REQUEST.failure();
        ObjectNode options = input.has("input") ? ((ObjectNode) input.get("input")).deepCopy() : json.createObjectNode();
        // n8n spreads input into routing data. Never permit forged scope or provider/cache claims.
        for (String name : List.of("project", "environment", "owner_id", "request_id", "task_type", "prompt", "body", "routing", "provider_plan", "cache"))
            if (options.has(name)) throw ApiCode.AI_INVALID_REQUEST.failure();
        String logical = options.has("collection") ? AiGateway.text(options.get("collection"), 64, true) : task.split("\\.")[0];
        options.put("collection", collection(context, logical));
        ObjectNode request = json.createObjectNode();
        request.put("request_id", id); request.put("task_type", task); request.set("prompt", input.get("prompt"));
        request.put("project", context.projectId().toString()); request.put("environment", context.environmentId().toString());
        request.set("input", options); request.put("sync", input.path("sync").asBoolean(true));
        if (json.writeValueAsBytes(request).length > AiGateway.JOB_LIMIT) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        return request;
    }

    static String collection(ProjectService.Context context, String logical) {
        if (!logical.matches("[a-z][a-z0-9_-]{0,63}")) throw ApiCode.AI_INVALID_REQUEST.failure();
        return "platform_" + context.projectId().toString().replace("-", "") + "_" + context.environmentId().toString().replace("-", "") + "_" + logical;
    }

    JsonNode get(ProjectService.Context context, UUID id) {
        ObjectNode result = job(context, ai.exchange("GET", "/api/v1/ai/jobs/" + id, null, AiGateway.JOB_LIMIT));
        if (!id.toString().equals(result.path("id").asText())) invalid();
        return result;
    }

    JsonNode list(ProjectService.Context context, String state, int limit) {
        if (limit < 1 || limit > 100 || state != null && !STATES.contains(state)) throw ApiCode.AI_INVALID_REQUEST.failure();
        JsonNode reply = ai.exchange("GET", "/api/v1/ai/jobs" + scope(context) + "&limit=" + limit
            + (state == null ? "" : "&status=" + state), null, 16 * 1024 * 1024);
        if (!reply.isArray() || reply.size() > limit) invalid();
        var result = json.createArrayNode();
        for (JsonNode item : reply) {
            if (state != null && !state.equals(item.path("status").asText())) invalid();
            result.add(job(context, item));
        }
        return result;
    }

    JsonNode cancel(ProjectService.Context context, UUID id) {
        // The shared upstream key can cancel any owner's task; scope must be checked before mutation.
        get(context, id);
        JsonNode reply = ai.exchange("POST", "/api/v1/ai/jobs/" + id + "/cancel", null, 64 * 1024);
        if (!reply.path("cancelled").isBoolean() || !reply.path("cancelled").asBoolean()) invalid();
        return json.createObjectNode().put("id", id.toString()).put("cancellation_requested", true).putNull("execution_stopped");
    }

    JsonNode usage(ProjectService.Context context, String task, int limit) {
        if (limit < 1 || limit > 200 || task != null && !AiGateway.TASKS.contains(task)) throw ApiCode.AI_INVALID_REQUEST.failure();
        JsonNode reply = ai.exchange("GET", "/api/v1/ai/usage" + scope(context) + "&limit=" + limit
            + (task == null ? "" : "&task_type=" + task), null, 1024 * 1024);
        if (!reply.path("summary").isArray() || !reply.path("records").isArray() || reply.path("records").size() > limit) invalid();
        ObjectNode result = json.createObjectNode();
        var summaries = result.putArray("summary"); var records = result.putArray("records");
        for (JsonNode item : reply.path("summary")) {
            usageIdentity(item, task);
            for (String name : List.of("call_count", "total_prompt_tokens", "total_completion_tokens", "total_tokens"))
                if (!AiGateway.nonnegativeInteger(item.path(name))) invalid();
            summaries.add(ai.select(item, Set.of("provider", "model", "task_type", "call_count", "total_prompt_tokens", "total_completion_tokens", "total_tokens")));
        }
        for (JsonNode item : reply.path("records")) {
            requireScope(context, item); usageIdentity(item, task);
            for (String name : List.of("prompt_tokens", "completion_tokens", "total_tokens"))
                if (!AiGateway.nonnegativeInteger(item.path(name))) invalid();
            validUuid(item.path("id"));
            if (!item.path("job_id").isNull()) validUuid(item.path("job_id"));
            validText(item.path("request_id"), 128); timestamp(item.path("created_at"), false);
            records.add(ai.select(item, Set.of("id", "job_id", "project", "environment", "request_id", "task_type", "provider", "model",
                "prompt_tokens", "completion_tokens", "total_tokens", "model_tier", "created_at")));
        }
        result.put("measurement", "upstream_reported_unverified");
        return result;
    }

    private ObjectNode job(ProjectService.Context context, JsonNode raw) {
        requireScope(context, raw);
        validUuid(raw.path("id")); validText(raw.path("request_id"), 128);
        if (!AiGateway.TASKS.contains(raw.path("task_type").asText()) || !STATES.contains(raw.path("status").asText())) invalid();
        timestamp(raw.path("created_at"), false);
        for (String field : List.of("updated_at", "finished_at", "expires_at")) timestamp(raw.path(field), true);
        if (!raw.path("result").isNull() && !raw.path("result").isObject()) invalid();
        ObjectNode result = (ObjectNode) ai.select(raw, Set.of("id", "request_id", "task_type", "project", "environment", "status", "result",
            "created_at", "updated_at", "finished_at", "expires_at"));
        if (raw.has("reused")) { if (!raw.path("reused").isBoolean()) invalid(); result.set("reused", raw.get("reused")); }
        String error = raw.path("error_code").asText("");
        result.put("error_code", error.matches("[a-z0-9_.]{1,80}") ? error : null);
        result.put("error_message", error.isBlank() ? null : "AI 작업이 완료되지 않았습니다. 상태와 오류 코드를 확인해 주세요.");
        boolean expired = !raw.path("expires_at").isNull() && !raw.path("expires_at").isMissingNode()
            && !Instant.parse(raw.path("expires_at").asText()).isAfter(Instant.now());
        result.put("result_expired", expired); if (expired) result.putNull("result");
        result.put("usage_measurement", "upstream_reported_unverified");
        return result;
    }

    private static String scope(ProjectService.Context context) { return "?project=" + context.projectId() + "&environment=" + context.environmentId(); }
    private static void requireScope(ProjectService.Context context, JsonNode item) {
        if (!item.isObject()) invalid();
        if (!context.projectId().toString().equals(item.path("project").asText()) || !context.environmentId().toString().equals(item.path("environment").asText()))
            throw ApiCode.AI_JOB_NOT_FOUND.failure();
    }
    private static void usageIdentity(JsonNode item, String task) {
        validText(item.path("provider"), 64); validText(item.path("model"), 128);
        if (!AiGateway.TASKS.contains(item.path("task_type").asText()) || task != null && !task.equals(item.path("task_type").asText())) invalid();
    }
    private static void validText(JsonNode node, int max) {
        if (!node.isString() || node.asText().isBlank() || node.asText().codePointCount(0, node.asText().length()) > max) invalid();
    }
    private static void validUuid(JsonNode node) {
        try { if (!node.isString() || !UUID.fromString(node.asText()).toString().equals(node.asText())) invalid(); }
        catch (IllegalArgumentException error) { invalid(); }
    }
    private static void timestamp(JsonNode node, boolean nullable) {
        if (nullable && (node.isNull() || node.isMissingNode())) return;
        try { if (!node.isString() || node.asText().length() > 64) invalid(); Instant.parse(node.asText()); }
        catch (java.time.format.DateTimeParseException error) { invalid(); }
    }
    private Object canonical(JsonNode node) {
        if (node.isObject()) { var map = new TreeMap<String,Object>(); for (String key : node.propertyNames()) map.put(key, canonical(node.get(key))); return map; }
        if (node.isArray()) { var list = new ArrayList<Object>(); for (JsonNode item : node) list.add(canonical(item)); return list; }
        return node;
    }
    private Map<String,Object> ledger(UUID environment, String request) {
        var rows = db.queryForList("SELECT fingerprint,remote_id FROM ai_request_ledger WHERE environment_id=? AND request_id=?", environment, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void remember(ProjectService.Context context, String request, UUID id) {
        db.update("UPDATE ai_request_ledger SET remote_id=? WHERE environment_id=? AND request_id=? AND (remote_id IS NULL OR remote_id=?)", id, context.environmentId(), request, id);
    }
    private static void invalid() { throw ApiCode.AI_INVALID_RESPONSE.failure(); }
}
