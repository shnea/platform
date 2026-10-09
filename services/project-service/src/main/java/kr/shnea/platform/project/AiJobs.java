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
    private static final Set<String> STATES = Set.of("pending", "running", "succeeded", "failed", "cancelled");
    private static final Set<String> LANGUAGES = Set.of("ko", "en", "ja", "zh", "es", "fr", "de");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AiGateway ai;
    private final JsonMapper json = new JsonMapper();
    AiJobs(JdbcTemplate db, TransactionTemplate tx, AiGateway ai) { this.db = db; this.tx = tx; this.ai = ai; }

    JsonNode submit(ProjectService.Context context, byte[] bytes) {
        return submit(context, prepare(context, bytes), false);
    }

    JsonNode translate(ProjectService.Context context, byte[] bytes) {
        JsonNode input = ai.parse(bytes, AiGateway.ROUTE_LIMIT);
        AiGateway.fields(input, Set.of("request_id", "text", "source_language", "target_language", "project", "environment", "notify"));
        ObjectNode request = json.createObjectNode().put("task_type", "text.translate").put("sync", false);
        for (String field : List.of("request_id", "project", "environment", "notify"))
            if (input.has(field)) request.set(field, input.get(field));
        request.set("prompt", input.path("text"));
        ObjectNode options = request.putObject("input");
        for (String field : List.of("source_language", "target_language"))
            if (input.has(field)) options.set(field, input.get(field));
        return submit(context, prepare(context, json.writeValueAsBytes(request)), true);
    }

    private JsonNode submit(ProjectService.Context context, ObjectNode request, boolean translation) {
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
            db.update("INSERT INTO ai_request_ledger(environment_id,request_id,fingerprint,task_type,notify) VALUES (?,?,?,?,?)",
                context.environmentId(), requestId, fingerprint, request.path("task_type").asText(), request.path("notify").asBoolean(false));
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
        try {
            if (translation) {
                ObjectNode upstream = (ObjectNode) ai.select(request, Set.of("request_id", "project", "environment", "notify"));
                upstream.set("text", request.get("prompt"));
                upstream.set("source_language", request.path("input").get("source_language"));
                upstream.set("target_language", request.path("input").get("target_language"));
                raw = ai.exchange("POST", "/api/v1/translations", upstream, AiGateway.JOB_LIMIT);
            } else raw = ai.exchange("POST", "/api/v1/ai/jobs", request, AiGateway.JOB_LIMIT);
        }
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
        AiGateway.fields(input, Set.of("request_id", "task_type", "prompt", "input", "sync", "project", "environment", "notify"));
        String id = AiGateway.text(input.get("request_id"), 128, true);
        if (id.codePoints().anyMatch(Character::isISOControl)) throw ApiCode.AI_INVALID_REQUEST.failure();
        String task = AiGateway.text(input.get("task_type"), 64, true);
        if (!AiGateway.TASKS.contains(task)) throw ApiCode.AI_INVALID_REQUEST.failure();
        JsonNode taskInput = input.path("input");
        String prompt;
        if (task.equals("article.draft")) {
            JsonNode taskContext = taskInput.path("context");
            String topic = AiGateway.text(taskContext.path("topic"), 2000, true);
            prompt = input.has("prompt") ? AiGateway.text(input.get("prompt"), 200000, true) : "실험글 초안 주제: " + topic;
        } else {
            prompt = AiGateway.text(input.get("prompt"), 200000, true);
        }
        for (String field : List.of("project", "environment")) {
            String expected = field.equals("project") ? context.projectId().toString() : context.environmentId().toString();
            if (input.has(field) && !expected.equals(AiGateway.text(input.get(field), 64, true))) throw ApiCode.AI_INVALID_REQUEST.failure();
        }
        if (input.has("sync") && !input.get("sync").isBoolean()) throw ApiCode.AI_INVALID_REQUEST.failure();
        if (input.has("notify") && !input.get("notify").isBoolean()) throw ApiCode.AI_INVALID_REQUEST.failure();
        if (input.path("notify").asBoolean(false) && !ai.completionReceiverConfigured()) throw ApiCode.AI_DELIVERY_NOT_CONFIGURED.beforeDispatch();
        if (input.has("input") && !input.get("input").isObject()) throw ApiCode.AI_INVALID_REQUEST.failure();
        ObjectNode options = input.has("input") ? ((ObjectNode) input.get("input")).deepCopy() : json.createObjectNode();
        // n8n spreads input into routing data. Never permit forged scope or provider/cache claims.
        for (String name : List.of("project", "environment", "owner_id", "request_id", "task_type", "prompt", "body", "routing", "provider_plan", "cache"))
            if (options.has(name)) throw ApiCode.AI_INVALID_REQUEST.failure();
        if (task.equals("text.translate")) {
            AiGateway.fields(options, Set.of("source_language", "target_language"));
            prompt = AiGateway.text(input.get("prompt"), 4000, true);
            if (prompt.codePoints().anyMatch(Character::isISOControl)) throw ApiCode.AI_INVALID_REQUEST.failure();
            String source = options.has("source_language") ? AiGateway.text(options.get("source_language"), 8, true) : "auto";
            String target = AiGateway.text(options.get("target_language"), 8, true);
            if ((!source.equals("auto") && !LANGUAGES.contains(source)) || !LANGUAGES.contains(target)) throw ApiCode.AI_INVALID_REQUEST.failure();
            options.put("source_language", source); options.put("target_language", target);
        } else {
            String logical = options.has("collection") ? AiGateway.text(options.get("collection"), 64, true) : task.split("\\.")[0];
            options.put("collection", collection(context, logical));
        }
        ObjectNode request = json.createObjectNode();
        request.put("request_id", id); request.put("task_type", task); request.put("prompt", prompt);
        request.put("project", context.projectId().toString()); request.put("environment", context.environmentId().toString());
        request.set("input", options); request.put("sync", input.path("sync").asBoolean(true));
        if (input.path("notify").asBoolean(false)) request.put("notify", true);
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

    JsonNode receipt(ProjectService.Context context, UUID id, byte[] bytes) {
        JsonNode input = ai.parse(bytes, AiGateway.ROUTE_LIMIT);
        AiGateway.fields(input, Set.of("event_id")); validUuid(input.path("event_id"));
        JsonNode current = get(context, id);
        if (!current.path("status").asText().equals("succeeded") || !current.path("notify").asBoolean(false)
                || !input.path("event_id").equals(current.path("terminal_event_id"))) throw ApiCode.AI_REQUEST_CONFLICT.failure();
        if (current.path("result_expired").asBoolean() && !current.path("result_received").asBoolean()) throw ApiCode.AI_RESULT_EXPIRED.failure();
        JsonNode reply = ai.exchange("POST", "/api/v1/ai/jobs/" + id + "/receipt", input, AiGateway.ROUTE_LIMIT);
        if (!reply.path("accepted").isBoolean() || !reply.path("accepted").asBoolean()) invalid();
        validText(reply.path("cleanup"), 64);
        db.update("UPDATE ai_completion_inbox SET received_at=coalesce(received_at,now()) WHERE environment_id=? AND job_id=? AND event_id=?",
            context.environmentId(), id, UUID.fromString(input.path("event_id").asText()));
        return ai.select(reply, Set.of("accepted", "cleanup"));
    }

    byte[] translationText(ProjectService.Context context, UUID id) {
        JsonNode current = get(context, id);
        if (!current.path("task_type").asText().equals("text.translate")) throw ApiCode.AI_JOB_NOT_FOUND.failure();
        if (current.path("result_received").asBoolean()) throw ApiCode.AI_RESULT_RECEIVED.failure();
        if (current.path("result_expired").asBoolean()) throw ApiCode.AI_RESULT_EXPIRED.failure();
        if (!current.path("status").asText().equals("succeeded") || !current.path("result").isObject()) throw ApiCode.AI_REQUEST_CONFLICT.failure();
        return current.path("result").path("translated_text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        if (raw.has("notify")) { if (!raw.path("notify").isBoolean()) invalid(); result.set("notify", raw.get("notify")); }
        if (raw.hasNonNull("terminal_event_id")) validUuid(raw.path("terminal_event_id"));
        timestamp(raw.path("received_at"), true);
        for (String field : List.of("terminal_event_id", "received_at")) if (raw.has(field)) result.set(field, raw.get(field));
        if (raw.hasNonNull("delivery")) {
            JsonNode delivery = raw.path("delivery"); validText(delivery.path("state"), 64);
            if (!delivery.path("configured").isBoolean() || !AiGateway.nonnegativeInteger(delivery.path("attempts"))) invalid();
            JsonNode httpStatus = delivery.path("last_http_status");
            if (!httpStatus.isNull() && !httpStatus.isMissingNode() && (!httpStatus.isIntegralNumber() || !httpStatus.canConvertToInt()
                    || httpStatus.asInt() < 100 || httpStatus.asInt() > 599)) invalid();
            timestamp(delivery.path("next_attempt_at"), true);
            result.set("delivery", ai.select(delivery, Set.of("state", "configured", "attempts", "last_http_status", "next_attempt_at")));
        }
        if (raw.has("reused")) { if (!raw.path("reused").isBoolean()) invalid(); result.set("reused", raw.get("reused")); }
        String error = raw.path("error_code").asText("");
        result.put("error_code", error.matches("[a-z0-9_.]{1,80}") ? error : null);
        result.put("error_message", error.isBlank() ? null : "AI 작업이 완료되지 않았습니다. 상태와 오류 코드를 확인해 주세요.");
        boolean expired = !raw.path("expires_at").isNull() && !raw.path("expires_at").isMissingNode()
            && !Instant.parse(raw.path("expires_at").asText()).isAfter(Instant.now());
        boolean received = raw.hasNonNull("received_at");
        result.put("result_expired", expired); result.put("result_received", received);
        if (expired || received) result.putNull("result");
        if (raw.path("task_type").asText().equals("text.translate") && raw.path("status").asText().equals("succeeded") && result.path("result").isObject()) {
            JsonNode translated = result.path("result");
            if (!translated.path("type").asText().equals("text_translate")) invalid();
            validText(translated.path("translated_text"), 200000);
            if (!LANGUAGES.contains(translated.path("target_language").asText())
                    || (!translated.path("source_language").asText().equals("auto") && !LANGUAGES.contains(translated.path("source_language").asText()))) invalid();
            result.set("result", ai.select(translated, Set.of("type", "translated_text", "source_language", "target_language", "provider", "model", "usage")));
        }
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
        if (db.update("UPDATE ai_request_ledger SET remote_id=? WHERE environment_id=? AND request_id=? AND (remote_id IS NULL OR remote_id=?)", id, context.environmentId(), request, id) != 1)
            invalid();
    }
    private static void invalid() { throw ApiCode.AI_INVALID_RESPONSE.failure(); }
}
