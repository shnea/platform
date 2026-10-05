package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiGatewayTest {
    HttpServer server; AiGateway ai;
    final JsonMapper json = new JsonMapper();
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<String> response = new AtomicReference<>(), path = new AtomicReference<>(), body = new AtomicReference<>(), auth = new AtomicReference<>();
    CountDownLatch entered, release;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().toString()); auth.set(exchange.getRequestHeaders().getFirst("X-Noedaeri-API-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (entered != null) { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/should-not-follow");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); ai = new AiGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "server-secret");
    }
    @AfterEach void cleanup() { if (release != null) release.countDown(); if (server != null) server.stop(0); }
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    void fails(Runnable action, ApiCode code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiCode.Failure.class, error -> assertThat(error.code).isEqualTo(code)); }
    String vector(int index, int dimensions) { return "{\"index\":" + index + ",\"embedding\":" + json.writeValueAsString(Collections.nCopies(dimensions, 0.25)) + "}"; }
    void embeddingsReply(int dimensions, String data) {
        response.set("{\"model\":\"" + AiGateway.EMBEDDING_MODEL + "\",\"dimensions\":" + dimensions + ",\"data\":[" + data + "],\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5},\"private\":\"provider-secret\"}");
    }
    @Test void singleAndBatchKeepIndicesModelDimensionsAndActualUsage() {
        embeddingsReply(768, vector(0, 768));
        var result = ai.embeddings(bytes("{\"input\":\"공통 임베딩\"}"));
        assertThat(result.path("dimensions").asInt()).isEqualTo(768);
        assertThat(result.path("data").get(0).path("embedding").size()).isEqualTo(768);
        assertThat(result.path("usage").path("total_tokens").asInt()).isEqualTo(5);
        assertThat(result.toString()).doesNotContain("provider-secret", "private");
        assertThat(path.get()).isEqualTo("/api/v1/ai/embeddings"); assertThat(auth.get()).isEqualTo("server-secret");
        assertThat(json.readTree(body.get()).path("model").asText()).isEqualTo(AiGateway.EMBEDDING_MODEL);
        embeddingsReply(3072, vector(1, 3072) + "," + vector(0, 3072));
        result = ai.embeddings(bytes("{\"input\":[\"하나\",\"둘\"],\"dimensions\":3072}"));
        assertThat(result.path("data").size()).isEqualTo(2);
        assertThat(result.path("data").get(0).path("index").asInt()).isEqualTo(1);
        assertThat(result.path("data").get(1).path("index").asInt()).isEqualTo(0);
    }
    @Test void malformedVectorsCannotEnterTheHostIndex() {
        for (String data : List.of(vector(0, 2), vector(-1, 3), vector(1, 3), vector(0, 3) + "," + vector(0, 3), "{\"index\":0,\"embedding\":[1,2,\"NaN\"]}")) {
            embeddingsReply(3, data);
            fails(() -> ai.embeddings(bytes("{\"input\":\"text\",\"dimensions\":3}")), ApiCode.AI_INVALID_RESPONSE);
        }
        embeddingsReply(768, vector(0, 768));
        response.set(response.get().replace(AiGateway.EMBEDDING_MODEL, "another-model"));
        fails(() -> ai.embeddings(bytes("{\"input\":\"text\"}")), ApiCode.AI_INVALID_RESPONSE);
        embeddingsReply(768, vector(0, 768)); response.set(response.get().replace("\"total_tokens\":5", "\"total_tokens\":-1"));
        fails(() -> ai.embeddings(bytes("{\"input\":\"text\"}")), ApiCode.AI_INVALID_RESPONSE);
    }
    @Test void badRequestsAreRejectedBeforeAnyProviderCall() {
        for (String input : List.of("null", "[]", "{}", "{\"input\":\" \"}", "{\"input\":[]}", "{\"input\":[42]}",
            "{\"input\":\"x\",\"dimensions\":0}", "{\"input\":\"x\",\"dimensions\":3073}", "{\"input\":\"x\",\"dimensions\":768.0}",
            "{\"input\":\"x\",\"model\":\"models/text-embedding-004\"}", "{\"input\":\"x\",\"owner\":\"other\"}",
            "{\"input\":\"x\",\"input\":\"y\"}", "{\"input\":\"x\"} {}"))
            fails(() -> ai.embeddings(bytes(input)), ApiCode.AI_INVALID_REQUEST);
        fails(() -> ai.embeddings(bytes(json.writeValueAsString(Map.of("input", Collections.nCopies(101, "x"))))), ApiCode.AI_INVALID_REQUEST);
        fails(() -> ai.embeddings(bytes(json.writeValueAsString(Map.of("input", "x".repeat(16001))))), ApiCode.AI_INVALID_REQUEST);
        fails(() -> ai.embeddings(new byte[AiGateway.EMBEDDING_LIMIT + 1]), ApiCode.PAYLOAD_TOO_LARGE);
        assertThat(calls.get()).isZero();
    }
    @Test void rayaAcceptsFutureTaskTypesAndRetainsTruncationWithoutCreatingAnAnswer() {
        response.set("{\"task_type\":\"future.task\",\"model_tier\":\"L2\",\"probabilities\":{\"L1\":0.1,\"L2\":0.7,\"L3\":0.2,\"secret\":\"hidden\"},\"confidence\":0.6,\"input_tokens\":512,\"input_truncated\":true,\"inference_ms\":30,\"elapsed_ms\":35,\"cold_start\":false,\"model\":\"TextCortex/raya\",\"revision\":\"revision\",\"device\":\"cpu\",\"runtime\":\"onnx-fp32\",\"answer\":\"hidden\"}");
        var result = ai.route(bytes("{\"task_type\":\"future.task\",\"prompt\":\"본문\",\"instruction\":\"\",\"has_images\":true}"));
        assertThat(result.path("model_tier").asText()).isEqualTo("L2");
        assertThat(result.path("input_truncated").asBoolean()).isTrue();
        assertThat(result.toString()).doesNotContain("hidden", "answer", "secret");
        assertThat(path.get()).isEqualTo("/api/v1/ai/raya/route");
        response.set(response.get().replace("\"L2\",\"probabilities\"", "\"L4\",\"probabilities\""));
        fails(() -> ai.route(bytes("{\"task_type\":\"future.task\",\"prompt\":\"text\"}")), ApiCode.AI_INVALID_RESPONSE);
    }
    @Test void rayaRejectsImagesAndSpoofedContextFields() {
        for (String input : List.of("{\"task_type\":\"Chat\",\"prompt\":\"x\"}", "{\"task_type\":\"chat.general\",\"prompt\":\" \"}",
            "{\"task_type\":\"chat.general\",\"prompt\":\"x\",\"images\":[\"secret\"]}", "{\"task_type\":\"chat.general\",\"prompt\":\"x\",\"has_images\":1}"))
            fails(() -> ai.route(bytes(input)), ApiCode.AI_INVALID_REQUEST);
        fails(() -> ai.route(new byte[AiGateway.ROUTE_LIMIT + 1]), ApiCode.PAYLOAD_TOO_LARGE);
        assertThat(calls.get()).isZero();
    }
    @Test void errorsNeverExposeProviderBodyFollowRedirectsOrRetry() {
        response.set("{\"detail\":\"provider-secret\"}");
        for (var item : Map.of(401, ApiCode.AI_UPSTREAM_AUTH_FAILED, 403, ApiCode.AI_UPSTREAM_AUTH_FAILED, 422, ApiCode.AI_INVALID_REQUEST,
            429, ApiCode.AI_BUSY, 503, ApiCode.AI_UNAVAILABLE, 504, ApiCode.AI_TIMEOUT, 302, ApiCode.AI_UNAVAILABLE).entrySet()) {
            status.set(item.getKey()); int before = calls.get();
            fails(() -> ai.embeddings(bytes("{\"input\":\"x\"}")), item.getValue()); assertThat(calls.get()).isEqualTo(before + 1);
        }
        status.set(200); response.set("not JSON provider-secret");
        fails(() -> ai.embeddings(bytes("{\"input\":\"x\"}")), ApiCode.AI_INVALID_RESPONSE);
    }
    @Test void concurrentRequestsAreBoundedWithoutQueueingExtraProviderCalls() throws Exception {
        embeddingsReply(768, vector(0, 768)); entered = new CountDownLatch(2); release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> ai.embeddings(bytes("{\"input\":\"a\"}")));
            var b = executor.submit(() -> ai.embeddings(bytes("{\"input\":\"b\"}")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            fails(() -> ai.embeddings(bytes("{\"input\":\"c\"}")), ApiCode.AI_BUSY); assertThat(calls.get()).isEqualTo(2);
            release.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void runtimeOriginsAreHttpsOnlyAndConfigurationIsNotClaimedAsConnectivity() {
        for (String url : List.of("http://example.invalid", "https://user:secret@example.invalid", "https://example.invalid/other", "https://example.invalid/?key=secret", "https://example.invalid/#fragment"))
            assertThatThrownBy(() -> new AiGateway(url, "key")).isInstanceOf(IllegalArgumentException.class);
        var disabled = new AiGateway("", ""); assertThat(disabled.configured()).isFalse();
        fails(() -> disabled.embeddings(bytes("{\"input\":\"x\"}")), ApiCode.AI_NOT_CONFIGURED);
        assertThat(disabled.services().toString()).contains("awaiting_upstream_api").doesNotContain("server-secret");
    }
}
