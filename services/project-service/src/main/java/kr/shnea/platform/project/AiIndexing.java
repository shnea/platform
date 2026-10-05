package kr.shnea.platform.project;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class AiIndexing {
    private static final Set<String> MODES = Set.of("upsert", "replace_all", "delete");
    private static final Set<String> STATES = Set.of("pending", "running", "succeeded", "failed", "cancelled");
    private final AiGateway ai;
    private final JsonMapper json = new JsonMapper();

    AiIndexing(AiGateway ai) { this.ai = ai; }

    JsonNode submit(ProjectService.Context context, byte[] body) {
        JsonNode input = ai.parse(body, AiGateway.INDEX_LIMIT);
        AiGateway.fields(input, Set.of("request_id", "project", "environment", "collection", "mode", "sync", "documents", "delete_ids"));
        String requestId = AiGateway.text(input.get("request_id"), 128, true);
        if (requestId.codePoints().anyMatch(Character::isISOControl)) invalidRequest();
        String collection = collection(input.path("collection").asText("portfolio"));
        String mode = AiGateway.text(input.get("mode"), 20, true);
        if (!MODES.contains(mode) || input.has("sync") && !input.get("sync").isBoolean()) invalidRequest();
        checkScopeInput(context, input);
        JsonNode documents = input.path("documents"), deleteIds = input.path("delete_ids");
        if (!documents.isArray() || documents.size() > 100 || !deleteIds.isMissingNode() && (!deleteIds.isArray() || deleteIds.size() > 100)) invalidRequest();
        if (mode.equals("delete") && (documents.size() != 0 || !deleteIds.isArray() || deleteIds.isEmpty())
                || mode.equals("replace_all") && !deleteIds.isMissingNode()) invalidRequest();
        Set<String> ids = new HashSet<>();
        for (JsonNode document : documents) {
            if (!document.isObject()) invalidRequest();
            AiGateway.fields(document, Set.of("id", "title", "content", "metadata"));
            String id = AiGateway.text(document.get("id"), 256, true);
            if (!ids.add(id)) invalidRequest();
            String title = document.has("title") ? AiGateway.text(document.get("title"), 512, false) : "";
            String content = AiGateway.text(document.get("content"), 16000, true);
            if ((title + content).codePointCount(0, (title + content).length()) > 16000
                    || document.has("metadata") && !document.get("metadata").isObject()) invalidRequest();
        }
        if (deleteIds.isArray()) for (JsonNode item : deleteIds) {
            String id = AiGateway.text(item, 256, true);
            if (!ids.add(id)) invalidRequest();
        }
        ObjectNode request = json.createObjectNode();
        request.put("request_id", requestId);
        request.put("project", context.projectId().toString());
        request.put("environment", context.environmentId().toString());
        request.put("collection", collection);
        request.put("mode", mode);
        request.put("sync", input.path("sync").asBoolean(true));
        request.set("documents", documents);
        if (!mode.equals("replace_all")) request.set("delete_ids", deleteIds.isArray() ? deleteIds : json.createArrayNode());
        if (json.writeValueAsBytes(request).length > AiGateway.INDEX_LIMIT) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        JsonNode result = job(context, ai.exchange("POST", "/api/v1/ai/indexing", request, AiGateway.JOB_LIMIT));
        if (!requestId.equals(result.path("request_id").asText()) || !collection.equals(result.path("collection").asText())
                || !mode.equals(result.path("mode").asText())) invalidResponse();
        return result;
    }

    JsonNode get(ProjectService.Context context, UUID id) {
        JsonNode result = job(context, ai.exchange("GET", "/api/v1/ai/indexing/" + id, null, AiGateway.JOB_LIMIT));
        if (!id.toString().equals(result.path("id").asText())) invalidResponse();
        return result;
    }

    JsonNode list(ProjectService.Context context, String collection, String status, int limit) {
        if (limit < 1 || limit > 100 || status != null && !STATES.contains(status)) invalidRequest();
        String path = scope(context) + "&limit=" + limit + (collection == null ? "" : "&collection=" + collection(collection))
            + (status == null ? "" : "&status=" + status);
        JsonNode raw = ai.exchange("GET", "/api/v1/ai/indexing" + path, null, AiGateway.JOB_LIMIT);
        if (!raw.isArray() || raw.size() > limit) invalidResponse();
        var result = json.createArrayNode();
        for (JsonNode item : raw) {
            JsonNode checked = job(context, item);
            if (collection != null && !collection.equals(checked.path("collection").asText())
                    || status != null && !status.equals(checked.path("status").asText())) invalidResponse();
            result.add(checked);
        }
        return result;
    }

    JsonNode cancel(ProjectService.Context context, UUID id) {
        JsonNode existing = get(context, id);
        if (!"pending".equals(existing.path("status").asText())) throw ApiCode.AI_REQUEST_CONFLICT.failure();
        return job(context, ai.exchange("POST", "/api/v1/ai/indexing/" + id + "/cancel", null, AiGateway.JOB_LIMIT));
    }

    JsonNode collections(ProjectService.Context context) {
        JsonNode raw = ai.exchange("GET", "/api/v1/ai/indexing/collections" + scope(context), null, AiGateway.JOB_LIMIT);
        JsonNode items = raw.isArray() ? raw : raw.path("collections");
        if (!items.isArray()) invalidResponse();
        var result = json.createArrayNode();
        for (JsonNode item : items) {
            checkScopeResponse(context, item);
            collection(item.path("collection").asText(""));
            if (!AiGateway.nonnegativeInteger(item.path("document_count"))) invalidResponse();
            result.add(ai.select(item, Set.of("project", "environment", "collection", "model", "dimensions", "document_count", "total_tokens", "last_updated_at")));
        }
        return result;
    }

    JsonNode search(ProjectService.Context context, byte[] body) {
        JsonNode input = ai.parse(body, AiGateway.INDEX_LIMIT);
        AiGateway.fields(input, Set.of("project", "environment", "collection", "query", "limit", "min_similarity"));
        checkScopeInput(context, input);
        String name = collection(input.path("collection").asText("portfolio"));
        AiGateway.text(input.get("query"), 10000, true);
        int limit = input.path("limit").asInt(5);
        if (input.has("limit") && (!input.get("limit").isIntegralNumber() || limit < 1 || limit > 50)) invalidRequest();
        double similarity = input.path("min_similarity").asDouble(0);
        if (input.has("min_similarity") && (!input.get("min_similarity").isNumber() || !Double.isFinite(similarity) || similarity < -1 || similarity > 1)) invalidRequest();
        ObjectNode request = json.createObjectNode();
        request.put("project", context.projectId().toString()); request.put("environment", context.environmentId().toString());
        request.put("collection", name); request.set("query", input.get("query"));
        request.put("limit", limit); request.put("min_similarity", similarity);
        JsonNode raw = ai.exchange("POST", "/api/v1/ai/indexing/search", request, AiGateway.JOB_LIMIT);
        checkScopeResponse(context, raw);
        if (!name.equals(raw.path("collection").asText()) || !raw.path("results").isArray() || raw.path("results").size() > limit) invalidResponse();
        return ai.select(raw, Set.of("query", "project", "environment", "collection", "total_candidates", "matched_count", "results"));
    }

    private JsonNode job(ProjectService.Context context, JsonNode raw) {
        checkScopeResponse(context, raw);
        try { UUID.fromString(raw.path("id").asText()); } catch (IllegalArgumentException error) { invalidResponse(); }
        if (!STATES.contains(raw.path("status").asText()) || !MODES.contains(raw.path("mode").asText())) invalidResponse();
        collection(raw.path("collection").asText(""));
        ObjectNode result = (ObjectNode) ai.select(raw, Set.of("id", "request_id", "project", "environment", "collection", "mode", "status",
            "document_count", "indexed_count", "deleted_count", "total_tokens", "result_state", "reused", "created_at", "updated_at", "finished_at", "expires_at"));
        result.put("error_code", raw.path("error_code").isString() ? raw.path("error_code").asText() : null);
        result.put("error_message", raw.path("error_code").isString() ? "색인 작업의 상태와 오류 코드를 확인해 주세요." : null);
        if (raw.path("result").isObject() && !"expired".equals(raw.path("result_state").asText()))
            result.set("result", ai.select(raw.path("result"), Set.of("indexed_count", "deleted_count", "model", "dimensions", "total_tokens", "usage_estimated")));
        else result.putNull("result");
        return result;
    }

    private static void checkScopeInput(ProjectService.Context context, JsonNode input) {
        if (input.has("project") && !context.projectId().toString().equals(AiGateway.text(input.get("project"), 64, true))
                || input.has("environment") && !context.environmentId().toString().equals(AiGateway.text(input.get("environment"), 64, true))) invalidRequest();
    }
    private static void checkScopeResponse(ProjectService.Context context, JsonNode raw) {
        if (!raw.isObject() || !context.projectId().toString().equals(raw.path("project").asText())
                || !context.environmentId().toString().equals(raw.path("environment").asText())) throw ApiCode.AI_JOB_NOT_FOUND.failure();
    }
    private static String collection(String name) {
        if (!name.matches("[a-z][a-z0-9_-]{0,63}")) invalidRequest();
        return name;
    }
    private static String scope(ProjectService.Context context) {
        return "?project=" + context.projectId() + "&environment=" + context.environmentId();
    }
    private static void invalidRequest() { throw ApiCode.AI_INVALID_REQUEST.failure(); }
    private static void invalidResponse() { throw ApiCode.AI_INVALID_RESPONSE.failure(); }
}
