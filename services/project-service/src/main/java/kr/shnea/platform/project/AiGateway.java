package kr.shnea.platform.project;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stateless server adapter. Never persist or log prompts, vectors, provider bodies or keys. */
@Service
class AiGateway {
    static final String EMBEDDING_MODEL = "models/gemini-embedding-001";
    static final int ROUTE_LIMIT = 64 * 1024, EMBEDDING_LIMIT = 1024 * 1024;
    private final URI base;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(2);
    private final JsonMapper json = JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @org.springframework.beans.factory.annotation.Autowired
    AiGateway(@Value("${platform.noedaeri.url:}") String url,
              @Value("${platform.noedaeri.key:}") String key) {
        this(url.isBlank() ? null : URI.create(url), key);
        if (base != null && !"https".equals(base.getScheme())) throw new IllegalArgumentException("AI origin requires HTTPS");
    }

    // Local test transport; runtime construction additionally requires HTTPS.
    AiGateway(URI base, String key) {
        if (base != null && (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !Set.of("", "/").contains(base.getRawPath())
                || !Set.of("https", "http").contains(base.getScheme())))
            throw new IllegalArgumentException("Invalid AI origin");
        this.base = base; this.key = key;
    }

    boolean configured() { return base != null && key != null && !key.isBlank(); }

    Map<String, Object> services() {
        return Map.of("configured", configured(), "features", List.of(
            Map.of("id", "raya.route", "status", "implemented", "path", "/api/v1/ai/raya/route", "scope", "ai:route"),
            Map.of("id", "embeddings", "status", "implemented", "path", "/api/v1/ai/embeddings", "scope", "ai:embed",
                "model", EMBEDDING_MODEL, "defaultDimensions", 768, "maxDimensions", 3072, "maxBatch", 100),
            Map.of("id", "n8n.execute", "status", "awaiting_upstream_api", "taskTypes", List.of(
                "blog.tags", "blog.summary", "portfolio.search", "ui.render", "comment.generate",
                "document.analyze", "code.analyze", "chat.general")),
            Map.of("id", "usage", "status", "awaiting_upstream_api"),
            Map.of("id", "portfolio.index", "status", "workflow_example_only")));
    }

    JsonNode parse(byte[] bytes, int limit) {
        if (bytes.length > limit) throw ApiCode.PAYLOAD_TOO_LARGE.failure();
        try {
            JsonNode input = json.readTree(bytes);
            if (input == null || !input.isObject()) throw ApiCode.AI_INVALID_REQUEST.failure();
            return input;
        } catch (ApiCode.Failure failure) { throw failure; }
        catch (RuntimeException error) { throw ApiCode.AI_INVALID_REQUEST.failure(); }
    }

    JsonNode route(byte[] bytes) {
        JsonNode input = parse(bytes, ROUTE_LIMIT);
        fields(input, Set.of("task_type", "prompt", "instruction", "has_images"));
        String task = text(input.get("task_type"), 80, true);
        if (!task.matches("[a-z][a-z0-9_.-]*")) throw ApiCode.AI_INVALID_REQUEST.failure();
        text(input.get("prompt"), 16000, true);
        if (input.has("instruction")) text(input.get("instruction"), 8000, false);
        if (input.has("has_images") && !input.get("has_images").isBoolean()) throw ApiCode.AI_INVALID_REQUEST.failure();
        JsonNode reply = call("/api/v1/ai/raya/route", input, 64 * 1024);
        if (!reply.path("task_type").asText().equals(task) || !Set.of("L1", "L2", "L3").contains(reply.path("model_tier").asText())
                || !reply.path("input_truncated").isBoolean() || !reply.path("cold_start").isBoolean()
                || !nonnegativeInteger(reply.path("input_tokens"))) invalidResponse();
        for (String name : List.of("L1", "L2", "L3")) probability(reply.path("probabilities").path(name));
        probability(reply.path("confidence"));
        for (String name : List.of("inference_ms", "elapsed_ms")) {
            JsonNode value = reply.path(name);
            if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) invalidResponse();
        }
        for (String name : List.of("model", "revision", "device", "runtime"))
            if (!reply.path(name).isString() || reply.path(name).asText().isBlank() || reply.path(name).asText().length() > 200) invalidResponse();
        var result = (tools.jackson.databind.node.ObjectNode) select(reply, Set.of("task_type", "model_tier", "confidence", "input_tokens", "input_truncated",
            "inference_ms", "elapsed_ms", "cold_start", "model", "revision", "device", "runtime"));
        result.set("probabilities", select(reply.path("probabilities"), Set.of("L1", "L2", "L3")));
        return result;
    }

    JsonNode embeddings(byte[] bytes) {
        JsonNode input = parse(bytes, EMBEDDING_LIMIT);
        fields(input, Set.of("input", "model", "dimensions"));
        JsonNode texts = input.get("input");
        if (texts == null) throw ApiCode.AI_INVALID_REQUEST.failure();
        int count;
        if (texts.isString()) { text(texts, 16000, true); count = 1; }
        else if (texts.isArray() && texts.size() >= 1 && texts.size() <= 100) {
            for (JsonNode item : texts) text(item, 16000, true);
            count = texts.size();
        } else throw ApiCode.AI_INVALID_REQUEST.failure();
        if (input.has("model") && !EMBEDDING_MODEL.equals(text(input.get("model"), 100, true))) throw ApiCode.AI_INVALID_REQUEST.failure();
        int dimensions = 768;
        if (input.has("dimensions")) {
            JsonNode value = input.get("dimensions");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 3072)
                throw ApiCode.AI_INVALID_REQUEST.failure();
            dimensions = value.asInt();
        }
        var request = json.createObjectNode(); request.set("input", texts);
        request.put("model", EMBEDDING_MODEL); request.put("dimensions", dimensions);
        JsonNode reply = call("/api/v1/ai/embeddings", request, 16 * 1024 * 1024);
        if (!reply.path("model").asText().equals(EMBEDDING_MODEL) || !reply.path("dimensions").isIntegralNumber()
                || !reply.path("dimensions").canConvertToInt() || reply.path("dimensions").asInt() != dimensions
                || !reply.path("data").isArray() || reply.path("data").size() != count || !reply.path("usage").isObject()) invalidResponse();
        boolean[] seen = new boolean[count];
        for (JsonNode item : reply.path("data")) {
            JsonNode index = item.path("index"), vector = item.path("embedding");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0 || index.asInt() >= count
                    || seen[index.asInt()] || !vector.isArray() || vector.size() != dimensions) invalidResponse();
            seen[index.asInt()] = true;
            for (JsonNode coordinate : vector) if (!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble())) invalidResponse();
        }
        for (String name : reply.path("usage").propertyNames()) if (!nonnegativeInteger(reply.path("usage").get(name))) invalidResponse();
        var result = json.createObjectNode(); result.put("model", EMBEDDING_MODEL); result.put("dimensions", dimensions);
        var data = result.putArray("data");
        for (JsonNode item : reply.path("data")) data.add(select(item, Set.of("index", "embedding")));
        result.set("usage", select(reply.path("usage"), Set.of("prompt_tokens", "total_tokens")));
        return result;
    }

    private JsonNode call(String path, JsonNode input, int limit) {
        if (!configured()) throw ApiCode.AI_NOT_CONFIGURED.failure();
        if (!slots.tryAcquire()) throw ApiCode.AI_BUSY.failure();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(100))
                .header("X-Noedaeri-API-Key", key).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(input))).build();
            future = http.sendAsync(request, info -> new LimitedBody(limit));
            var response = future.get(105, TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status != 200) throw switch (status) {
                case 400, 413, 422 -> ApiCode.AI_INVALID_REQUEST.failure();
                case 401, 403 -> ApiCode.AI_UPSTREAM_AUTH_FAILED.failure();
                case 429 -> ApiCode.AI_BUSY.failure();
                case 504 -> ApiCode.AI_TIMEOUT.failure();
                default -> ApiCode.AI_UNAVAILABLE.failure();
            };
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) invalidResponse();
            JsonNode value = json.readTree(response.body());
            if (value == null || !value.isObject()) invalidResponse();
            return value;
        } catch (ApiCode.Failure failure) { throw failure; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw ApiCode.AI_UNAVAILABLE.failure(); }
        catch (TimeoutException error) { throw ApiCode.AI_TIMEOUT.failure(); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException) throw ApiCode.AI_TIMEOUT.failure();
            throw ApiCode.AI_UNAVAILABLE.failure();
        } catch (RuntimeException error) { throw ApiCode.AI_INVALID_RESPONSE.failure(); }
        finally { if (future != null && !future.isDone()) future.cancel(true); slots.release(); }
    }

    private JsonNode select(JsonNode source, Set<String> names) {
        var result = json.createObjectNode();
        for (String name : names) if (source.has(name)) result.set(name, source.get(name));
        return result;
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        for (String name : value.propertyNames()) if (!allowed.contains(name)) throw ApiCode.AI_INVALID_REQUEST.failure();
    }
    private static String text(JsonNode value, int max, boolean nonblank) {
        if (value == null || !value.isString()) throw ApiCode.AI_INVALID_REQUEST.failure();
        String text = value.asText();
        if (text.codePointCount(0, text.length()) > max || nonblank && text.isBlank()) throw ApiCode.AI_INVALID_REQUEST.failure();
        return text;
    }
    private static boolean nonnegativeInteger(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0; }
    private static void probability(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0 || value.asDouble() > 1) invalidResponse();
    }
    private static void invalidResponse() { throw ApiCode.AI_INVALID_RESPONSE.failure(); }

    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IllegalStateException("AI response limit exceeded")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
