package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class AiJobsDatabaseTest {
    JdbcTemplate admin, db; String schema; AiGateway ai; AiJobs jobs;
    ProjectService.Context own, other; final JsonMapper json = new JsonMapper();
    UUID remote = UUID.randomUUID(); AtomicInteger posts = new AtomicInteger();
    AtomicReference<JsonNode> accepted = new AtomicReference<>(); String state = "succeeded";
    @BeforeEach void setup() {
        String url = System.getenv("JOB_TEST_DB_URL"); schema = "ai_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "job_checks", "isolated-test-only")); admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, "job_checks", "isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate(); db = new JdbcTemplate(ds);
        own = scope("one"); other = scope("two");
        ai = spy(new AiGateway((java.net.URI) null, ""));
        jobs = new AiJobs(db, new TransactionTemplate(new DataSourceTransactionManager(ds)), ai);
        doAnswer(call -> {
            String method = call.getArgument(0), path = call.getArgument(1); JsonNode request = call.getArgument(2);
            if (method.equals("POST") && path.equals("/api/v1/ai/jobs")) { posts.incrementAndGet(); accepted.set(request); return reply(own); }
            if (method.equals("GET") && path.startsWith("/api/v1/ai/jobs?")) return json.createArrayNode().add(reply(own));
            if (method.equals("GET")) return reply(own);
            return json.readTree("{\"cancelled\":true}");
        }).when(ai).exchange(anyString(), anyString(), any(), anyInt());
    }
    ProjectService.Context scope(String code) {
        UUID project = UUID.randomUUID(), env = UUID.randomUUID();
        db.update("INSERT INTO projects(id,code,name,status) VALUES (?,?,?,'ACTIVE')", project, code, code);
        db.update("INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state) VALUES (?,?,'dev','DEV',?,false,'[]','READY')", env, project, "p-" + env);
        return new ProjectService.Context(project, env, "DEV", "https://identity.example", List.of("ai:execute"));
    }
    @AfterEach void cleanup() { if (admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    byte[] request(String prompt) { return ("{\"request_id\":\"stable-id\",\"task_type\":\"chat.general\",\"prompt\":\"" + prompt + "\",\"input\":{\"collection\":\"document\"}}").getBytes(StandardCharsets.UTF_8); }
    JsonNode reply(ProjectService.Context scope) {
        var n = json.createObjectNode().put("id", remote.toString()).put("request_id", "stable-id").put("task_type", "chat.general")
            .put("project", scope.projectId().toString()).put("environment", scope.environmentId().toString()).put("status", state)
            .put("created_at", Instant.now().toString()).put("finished_at", Instant.now().toString()).put("expires_at", Instant.now().plusSeconds(86400).toString())
            .put("error_code", state.equals("failed") ? "http_502" : null).put("error_message", "private provider URL or key must not be echoed");
        n.putObject("result").put("ai_result", "answer"); n.put("prompt", "must not leak"); return n;
    }
    void fails(Runnable operation, ApiCode code) { assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiCode.Failure.class, error -> assertThat(error.code).isEqualTo(code)); }

    @Test void scopedSubmissionReplaysThroughGetAndStoresOnlyFingerprints() {
        var first = jobs.submit(own, request("hello"));
        assertThat(accepted.get().path("project").asText()).isEqualTo(own.projectId().toString());
        assertThat(accepted.get().path("environment").asText()).isEqualTo(own.environmentId().toString());
        assertThat(accepted.get().path("input").path("collection").asText()).isEqualTo(AiJobs.collection(own, "document"));
        assertThat(jobs.submit(own, request("hello")).path("reused").asBoolean()).isTrue(); assertThat(posts.get()).isEqualTo(1);
        fails(() -> jobs.submit(own, request("changed")), ApiCode.AI_REQUEST_CONFLICT);
        assertThat(first.toString()).doesNotContain("private provider", "must not leak");
        var row = db.queryForMap("SELECT * FROM ai_request_ledger");
        assertThat(row.keySet()).containsExactlyInAnyOrder("environment_id", "request_id", "fingerprint", "remote_id", "created_at");
        assertThat(row.get("fingerprint").toString()).hasSize(64); assertThat(row.get("remote_id")).isEqualTo(remote);
    }
    @Test void failuresAndCancellationNeverExecuteAgainAndLostResponsesRecoverByList() {
        doAnswer(call -> { posts.incrementAndGet(); accepted.set(call.getArgument(2)); throw ApiCode.AI_TIMEOUT.failure(); })
            .when(ai).exchange(eq("POST"), eq("/api/v1/ai/jobs"), any(), anyInt());
        fails(() -> jobs.submit(own, request("hello")), ApiCode.AI_TIMEOUT);
        state = "failed";
        assertThat(jobs.submit(own, request("hello")).path("status").asText()).isEqualTo("failed");
        state = "cancelled";
        assertThat(jobs.submit(own, request("hello")).path("status").asText()).isEqualTo("cancelled");
        assertThat(posts.get()).isEqualTo(1);
        db.update("UPDATE ai_request_ledger SET remote_id=NULL");
        doReturn(json.createArrayNode()).when(ai).exchange(eq("GET"), contains("/api/v1/ai/jobs?"), isNull(), anyInt());
        fails(() -> jobs.submit(own, request("hello")), ApiCode.AI_REQUEST_UNCONFIRMED); assertThat(posts.get()).isEqualTo(1);
    }
    @Test void knownLocalRejectionDoesNotConsumeAnExecutionIdentity() {
        doThrow(ApiCode.AI_BUSY.beforeDispatch()).when(ai).exchange(eq("POST"), eq("/api/v1/ai/jobs"), any(), anyInt());
        fails(() -> jobs.submit(own, request("hello")), ApiCode.AI_BUSY);
        assertThat(db.queryForObject("SELECT count(*) FROM ai_request_ledger", Integer.class)).isZero();
        doReturn(reply(own)).when(ai).exchange(eq("POST"), eq("/api/v1/ai/jobs"), any(), anyInt());
        assertThat(jobs.submit(own, request("hello")).path("id").asText()).isEqualTo(remote.toString());
    }
    @Test void concurrentDuplicatesHaveOnlyOneExecutionAndJsonOrderDoesNotChangeIdentity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { posts.incrementAndGet(); accepted.set(call.getArgument(2)); entered.countDown(); release.await(10, TimeUnit.SECONDS); return reply(own); })
            .when(ai).exchange(eq("POST"), eq("/api/v1/ai/jobs"), any(), anyInt());
        doReturn(json.createArrayNode()).when(ai).exchange(eq("GET"), contains("/api/v1/ai/jobs?"), isNull(), anyInt());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> jobs.submit(own, request("hello"))); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            fails(() -> jobs.submit(own, request("hello")), ApiCode.AI_REQUEST_UNCONFIRMED);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        var reordered = "{\"input\":{\"collection\":\"document\"},\"prompt\":\"hello\",\"sync\":false,\"task_type\":\"chat.general\",\"request_id\":\"stable-id\"}";
        assertThat(jobs.submit(own, reordered.getBytes(StandardCharsets.UTF_8)).path("reused").asBoolean()).isTrue(); assertThat(posts.get()).isEqualTo(1);
    }
    @Test void crossEnvironmentAccessAndCancelAreRejectedBeforeMutation() {
        fails(() -> jobs.get(other, remote), ApiCode.AI_JOB_NOT_FOUND);
        fails(() -> jobs.cancel(other, remote), ApiCode.AI_JOB_NOT_FOUND);
        verify(ai, never()).exchange(eq("POST"), contains("/cancel"), any(), anyInt());
        assertThat(jobs.cancel(own, remote).path("execution_stopped").isNull()).isTrue();
        verify(ai).exchange(eq("POST"), eq("/api/v1/ai/jobs/" + remote + "/cancel"), isNull(), anyInt());
        fails(() -> jobs.list(other, null, 50), ApiCode.AI_JOB_NOT_FOUND);
        verify(ai).exchange(eq("GET"), contains("project=" + other.projectId() + "&environment=" + other.environmentId()), isNull(), anyInt());
    }
    @Test void spoofedScopeNestedRoutingAndInvalidTasksCannotReachUpstream() {
        for (String value : List.of(
            "{\"request_id\":\"r\",\"task_type\":\"future.task\",\"prompt\":\"x\"}",
            "{\"request_id\":\"r\",\"task_type\":\"chat.general\",\"prompt\":\"x\",\"project\":\"other\"}",
            "{\"request_id\":\"r\",\"task_type\":\"chat.general\",\"prompt\":\"x\",\"input\":{\"environment\":\"other\"}}",
            "{\"request_id\":\"r\",\"task_type\":\"chat.general\",\"prompt\":\"x\",\"input\":{\"cache\":{\"hit\":true}}}",
            "{\"request_id\":\"r\",\"task_type\":\"chat.general\",\"prompt\":\"x\",\"input\":{\"collection\":\"../portfolio\"}}"))
            fails(() -> jobs.submit(own, value.getBytes(StandardCharsets.UTF_8)), ApiCode.AI_INVALID_REQUEST);
        fails(() -> jobs.prepare(own, new byte[AiGateway.JOB_LIMIT + 1]), ApiCode.PAYLOAD_TOO_LARGE);
        fails(() -> jobs.prepare(own, request("x".repeat(200001))), ApiCode.AI_INVALID_REQUEST);
        verify(ai, never()).exchange(anyString(), anyString(), any(), anyInt());
        assertThat(AiJobs.collection(own, "portfolio")).isNotEqualTo(AiJobs.collection(other, "portfolio"));
    }
    @Test void expiresAtHidesExpiredResultsAndUsageCannotLeakScopeOrInventMeasurement() {
        var expired = (tools.jackson.databind.node.ObjectNode) reply(own); expired.put("expires_at", Instant.now().minusSeconds(1).toString());
        expired.put("request_id", "😀".repeat(128)); // Bounds are Unicode code points, consistent with upstream/schema.
        doReturn(expired).when(ai).exchange(eq("GET"), eq("/api/v1/ai/jobs/" + remote), isNull(), anyInt());
        assertThat(jobs.get(own, remote).path("result").isNull()).isTrue(); assertThat(jobs.get(own, remote).path("result_expired").asBoolean()).isTrue();
        var usage = json.createObjectNode();
        usage.putArray("summary").addObject().put("provider", "unknown").put("model", "unknown").put("task_type", "chat.general")
            .put("call_count", 1).put("total_prompt_tokens", 3).put("total_completion_tokens", 2).put("total_tokens", 5);
        var row = usage.putArray("records").addObject().put("id", UUID.randomUUID().toString()).putNull("job_id")
            .put("project", own.projectId().toString()).put("environment", own.environmentId().toString()).put("request_id", "r")
            .put("provider", "unknown").put("model", "unknown").put("task_type", "chat.general").put("prompt_tokens", 3)
            .put("completion_tokens", 2).put("total_tokens", 5).put("created_at", Instant.now().toString()).put("prompt", "hidden");
        doReturn(usage).when(ai).exchange(eq("GET"), contains("/api/v1/ai/usage?"), isNull(), anyInt());
        assertThat(jobs.usage(own, null, 50).toString()).doesNotContain("hidden");
        assertThat(jobs.usage(own, null, 50).path("measurement").asText()).isEqualTo("upstream_reported_unverified");
        row.put("environment", other.environmentId().toString()); fails(() -> jobs.usage(own, null, 50), ApiCode.AI_JOB_NOT_FOUND);
        row.put("environment", own.environmentId().toString()).put("total_tokens", -1); fails(() -> jobs.usage(own, null, 50), ApiCode.AI_INVALID_RESPONSE);
    }
}
