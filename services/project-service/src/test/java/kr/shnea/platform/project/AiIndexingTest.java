package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiIndexingTest {
    private final JsonMapper json = new JsonMapper();
    private final UUID project = UUID.randomUUID(), environment = UUID.randomUUID(), job = UUID.randomUUID();
    private final ProjectService.Context context = new ProjectService.Context(project, environment, "DEV", "https://identity.example", List.of());
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>(), body = new AtomicReference<>(), response = new AtomicReference<>();
    private HttpServer server;
    private AiIndexing indexing;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        indexing = new AiIndexing(new AiGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-key"));
        response.set("{\"id\":\"" + job + "\",\"request_id\":\"full-1\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"mode\":\"replace_all\",\"status\":\"succeeded\",\"result\":{\"indexed_count\":0}}" );
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void emptyReplaceAllReplacesOnlyTheAuthenticatedScope() {
        var result = indexing.submit(context, bytes("{\"request_id\":\"full-1\",\"collection\":\"portfolio\",\"mode\":\"replace_all\",\"documents\":[]}"));
        assertThat(result.path("status").asText()).isEqualTo("succeeded");
        assertThat(path.get()).isEqualTo("/api/v1/ai/indexing");
        var sent = json.readTree(body.get());
        assertThat(sent.path("project").asText()).isEqualTo(project.toString());
        assertThat(sent.path("environment").asText()).isEqualTo(environment.toString());
        assertThat(sent.path("documents").size()).isZero();
        assertThat(sent.has("delete_ids")).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void invalidReplacementCannotDispatch() {
        for (String request : List.of(
            "{\"request_id\":\"full-1\",\"mode\":\"replace_all\",\"documents\":[],\"delete_ids\":[]}",
            "{\"request_id\":\"full-1\",\"mode\":\"replace_all\",\"documents\":[{\"id\":\"a\",\"content\":\"x\"},{\"id\":\"a\",\"content\":\"y\"}]}",
            "{\"request_id\":\"full-1\",\"mode\":\"replace_all\",\"documents\":[],\"project\":\"" + UUID.randomUUID() + "\"}",
            "{\"request_id\":\"full-1\",\"mode\":\"replace_all\",\"documents\":[],\"collection\":\"../other\"}"))
            assertThatThrownBy(() -> indexing.submit(context, bytes(request))).isInstanceOf(ApiCode.Failure.class);
        assertThat(calls.get()).isZero();
    }

    @Test void responseFromAnotherEnvironmentCannotBeReturned() {
        response.set(response.get().replace(environment.toString(), UUID.randomUUID().toString()));
        assertThatThrownBy(() -> indexing.submit(context, bytes("{\"request_id\":\"full-1\",\"mode\":\"replace_all\",\"documents\":[]}")))
            .isInstanceOf(ApiCode.Failure.class).satisfies(error -> assertThat(((ApiCode.Failure) error).code).isEqualTo(ApiCode.AI_JOB_NOT_FOUND));
    }

    @Test void upsertAndListAndGetAndCancelFlow() {
        response.set("{\"id\":\"" + job + "\",\"request_id\":\"up-1\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"mode\":\"upsert\",\"status\":\"succeeded\",\"result\":{\"indexed_count\":1}}");
        var result = indexing.submit(context, bytes("{\"request_id\":\"up-1\",\"collection\":\"portfolio\",\"mode\":\"upsert\",\"documents\":[{\"id\":\"doc-1\",\"content\":\"sample text\"}]}"));
        assertThat(result.path("status").asText()).isEqualTo("succeeded");
        assertThat(indexing.get(context, job).path("id").asText()).isEqualTo(job.toString());

        response.set("[{\"id\":\"" + job + "\",\"request_id\":\"up-1\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"mode\":\"upsert\",\"status\":\"succeeded\"}]");
        var list = indexing.list(context, "portfolio", "succeeded", 10);
        assertThat(list.size()).isEqualTo(1);

        // Cancel succeeds only when pending
        response.set("{\"id\":\"" + job + "\",\"request_id\":\"up-1\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"mode\":\"upsert\",\"status\":\"succeeded\"}");
        assertThatThrownBy(() -> indexing.cancel(context, job)).isInstanceOf(ApiCode.Failure.class)
            .satisfies(error -> assertThat(((ApiCode.Failure) error).code).isEqualTo(ApiCode.AI_REQUEST_CONFLICT));

        response.set("{\"id\":\"" + job + "\",\"request_id\":\"up-1\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"mode\":\"upsert\",\"status\":\"pending\"}");
        var cancelled = indexing.cancel(context, job);
        assertThat(cancelled.path("status").asText()).isEqualTo("pending");
    }

    @Test void searchAndCollectionsInspectScope() {
        response.set("{\"query\":\"검색어\",\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"total_candidates\":1,\"matched_count\":1,\"results\":[{\"id\":\"doc-1\",\"similarity\":0.95,\"content\":\"sample text\"}]}");
        var searchResult = indexing.search(context, bytes("{\"collection\":\"portfolio\",\"query\":\"검색어\",\"limit\":5,\"min_similarity\":0.5}"));
        assertThat(searchResult.path("results").size()).isEqualTo(1);
        assertThat(searchResult.path("matched_count").asInt()).isEqualTo(1);

        response.set("[{\"project\":\"" + project + "\",\"environment\":\"" + environment
            + "\",\"collection\":\"portfolio\",\"model\":\"models/gemini-embedding-001\",\"dimensions\":768,\"document_count\":1,\"total_tokens\":10}]");
        var collectionsResult = indexing.collections(context);
        assertThat(collectionsResult.size()).isEqualTo(1);
        assertThat(collectionsResult.get(0).path("collection").asText()).isEqualTo("portfolio");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
