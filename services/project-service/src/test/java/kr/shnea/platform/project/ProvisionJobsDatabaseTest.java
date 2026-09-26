package kr.shnea.platform.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real PostgreSQL checks. Each test owns a new schema in the disposable job_checks database. */
@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL", matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class ProvisionJobsDatabaseTest {
    private static final String REQUEST = "0123456789abcdef0123456789abcdef";
    private JdbcTemplate admin, db;
    private TransactionTemplate tx;
    private IdentityClient identity;
    private ProjectService projects;
    private ProvisionJobs jobs;
    private String schema;
    private UUID projectId, environmentId;

    @BeforeEach void setup() {
        String url = System.getenv("JOB_TEST_DB_URL");
        schema = "job_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "job_checks", "isolated-test-only"));
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, "job_checks", "isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        identity = mock(IdentityClient.class);
        when(identity.issuer(anyString())).thenReturn("https://identity.example.test/realm");
        projects = new ProjectService(db, tx, identity, "dev");
        jobs = new ProvisionJobs(db, tx, projects);
        projectId = projects.createProject("job-test", "작업 검증", "test-admin").id();
        environmentId = UUID.randomUUID();
        db.update("INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state) VALUES (?,?,'dev','DEV',?,false,'[]','PENDING')",
            environmentId, projectId, "p-" + environmentId.toString().replace("-", ""));
    }

    @AfterEach void cleanup() {
        MDC.clear();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void concurrentEnqueueAndClaimHaveOneWinnerAndCompletionIsIdempotent() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<ProvisionJobs.Job>> queued = new ArrayList<>();
            for (int i = 0; i < 6; i++) queued.add(pool.submit(() -> { start.await(); return enqueue(); }));
            start.countDown();
            UUID id = queued.getFirst().get(10, TimeUnit.SECONDS).id();
            for (var result : queued) assertThat(result.get(10, TimeUnit.SECONDS).id()).isEqualTo(id);
            assertThat(count("SELECT count(*) FROM platform_jobs")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM audit_events WHERE action='job.queued'")).isEqualTo(1);

            List<Future<ProvisionJobs.Claim>> claims = new ArrayList<>();
            for (int i = 0; i < 6; i++) claims.add(pool.submit(jobs::claim));
            List<ProvisionJobs.Claim> winners = new ArrayList<>();
            for (var result : claims) { var claim = result.get(10, TimeUnit.SECONDS); if (claim != null) winners.add(claim); }
            assertThat(winners).hasSize(1);
            var claim = winners.getFirst();
            assertThat(jobs.job(id).state()).isEqualTo("RUNNING");
            jobs.execute(claim);
            jobs.execute(claim);
            jobs.failed(claim);
            assertThat(jobs.job(id).state()).isEqualTo("SUCCEEDED");
            assertThat(jobs.detail(id).attempts()).extracting(ProvisionJobs.Attempt::state).containsExactly("SUCCEEDED");
            verify(identity, times(1)).ensureRealm(any(), eq(true));
            assertThat(count("SELECT count(*) FROM project_outbox WHERE event_type='job.succeeded' AND request_id='" + REQUEST + "'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM audit_events WHERE action='job.succeeded'")).isEqualTo(1);
            assertThat(jobs.list(environmentId, "SUCCEEDED", 20, 0, null, null)).hasSize(1);
            assertThat(jobs.list(UUID.randomUUID(), null, 20, 0, null, null)).isEmpty();
        }
    }

    @Test void failuresWaitThenExhaustAndManualRetryPreservesHistory() {
        doThrow(new RestClientException("provider-secret-must-not-escape")).when(identity).ensureRealm(any(), anyBoolean());
        UUID id = enqueue().id();
        for (int attempt = 1; attempt <= 3; attempt++) {
            jobs.execute(jobs.claim());
            var job = jobs.job(id);
            assertThat(job.attempts()).isEqualTo(attempt);
            assertThat(job.errorCode()).isEqualTo("ENVIRONMENT_PROVISION_FAILED");
            assertThat(job.state()).isEqualTo(attempt < 3 ? "RETRY_WAIT" : "FAILED");
            if (attempt < 3) {
                assertThat(job.nextRunAt()).isAfter(Instant.now().plusSeconds(attempt == 1 ? 5 : 20));
                assertThat(jobs.claim()).isNull();
                due(id);
            }
        }
        assertThat(jobs.claim()).isNull();
        assertThat(jobs.detail(id).attempts()).extracting(ProvisionJobs.Attempt::state).containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(count("SELECT count(*) FROM project_outbox WHERE event_type='job.failed'")).isEqualTo(1);
        var retry = jobs.retry(id, "test-admin", REQUEST);
        assertThat(jobs.retry(id, "test-admin", REQUEST).id()).isEqualTo(retry.id());
        assertThat(retry.retryOf()).isEqualTo(id);
        assertThat(jobs.job(id).state()).isEqualTo("FAILED");
        doNothing().when(identity).ensureRealm(any(), anyBoolean());
        jobs.execute(jobs.claim());
        assertThat(jobs.job(retry.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(jobs.retry(id, "test-admin", REQUEST).id()).isEqualTo(retry.id());
        assertThat(jobs.detail(id).attempts()).hasSize(3);
    }

    @Test void expiredClaimIsRecoveredAndOldOwnerCannotChangeNewAttempt() {
        UUID id = enqueue().id();
        var abandoned = jobs.claim();
        expire(id);
        // New service/worker objects have no memory of the old claim, like a restarted process.
        var restarted = new ProvisionJobs(db, tx, projects);
        var current = restarted.claim();
        assertThat(current.token()).isNotEqualTo(abandoned.token());
        jobs.execute(abandoned);
        jobs.failed(abandoned);
        verify(identity, never()).ensureRealm(any(), anyBoolean());
        assertThat(jobs.job(id).state()).isEqualTo("RUNNING");
        restarted.execute(current);
        assertThat(jobs.detail(id).attempts()).extracting(ProvisionJobs.Attempt::state).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(count("SELECT count(*) FROM audit_events WHERE action='job.recovered'")).isEqualTo(1);
    }

    @Test void repeatedWorkerLossStopsAtAttemptLimit() {
        UUID id = enqueue().id();
        for (int i = 0; i < 3; i++) { assertThat(jobs.claim()).isNotNull(); expire(id); }
        assertThat(jobs.claim()).isNull();
        assertThat(jobs.job(id).state()).isEqualTo("FAILED");
        assertThat(jobs.job(id).errorCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(jobs.detail(id).attempts()).extracting(ProvisionJobs.Attempt::state).containsExactly("ABANDONED", "ABANDONED", "ABANDONED");
        assertThat(count("SELECT count(*) FROM project_outbox")).isEqualTo(1);
    }

    @Test void cancellationAndRevisionChangePreventExternalExecution() {
        UUID id = enqueue().id();
        assertThat(jobs.cancel(id, "test-admin").state()).isEqualTo("CANCELLED");
        jobs.cancel(id, "test-admin");
        assertThat(jobs.claim()).isNull();
        assertThat(count("SELECT count(*) FROM project_outbox")).isEqualTo(1);
        assertThatThrownBy(() -> jobs.retry(id, "test-admin", REQUEST)).isInstanceOf(ResponseStatusException.class);
        var changed = enqueue();
        db.update("UPDATE environments SET revision=revision+1 WHERE id=?", environmentId);
        var claim = jobs.claim();
        assertThatThrownBy(() -> jobs.cancel(changed.id(), "test-admin")).isInstanceOf(ResponseStatusException.class);
        jobs.execute(claim);
        assertThat(jobs.job(changed.id()).state()).isEqualTo("CANCELLED");
        assertThat(jobs.job(changed.id()).errorCode()).isEqualTo("JOB_TARGET_CHANGED");
        verify(identity, never()).ensureRealm(any(), anyBoolean());
        var waiting = enqueue();
        doThrow(new RestClientException("offline")).when(identity).ensureRealm(any(), anyBoolean());
        jobs.execute(jobs.claim());
        assertThat(jobs.cancel(waiting.id(), "test-admin").state()).isEqualTo("CANCELLED");
        assertThat(jobs.claim()).isNull();
    }

    @Test void failedCompletionRollsBackStateAuditAndEventTogether() {
        UUID id = enqueue().id();
        var claim = jobs.claim();
        db.execute("ALTER TABLE project_outbox ADD CONSTRAINT fail_completion CHECK (false)");
        assertThatThrownBy(() -> jobs.execute(claim)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jobs.job(id).state()).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT state FROM environments WHERE id=?", String.class, environmentId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM audit_events WHERE action IN ('job.succeeded','environment.provision.ready')")).isZero();
        assertThat(count("SELECT count(*) FROM project_outbox")).isZero();
        jobs.failed(claim);
        assertThat(jobs.job(id).state()).isEqualTo("RETRY_WAIT");
        db.execute("ALTER TABLE project_outbox DROP CONSTRAINT fail_completion");
        due(id);
        jobs.execute(jobs.claim());
        assertThat(jobs.job(id).state()).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM project_outbox")).isEqualTo(1);
        verify(identity, times(2)).ensureRealm(any(), anyBoolean()); // External calls must remain idempotent after DB rollback.
    }

    @Test void activeExecutionKeepsOwnershipAndDoesNotBlockOtherClaims() throws Exception {
        UUID id = enqueue().id();
        var claim = jobs.claim();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        db.update("UPDATE platform_jobs SET lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?", id);
        doAnswer(invocation -> { entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); return null; })
            .when(identity).ensureRealm(any(), anyBoolean());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var executing = pool.submit(() -> jobs.execute(claim));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(2100);
                assertThat(db.queryForObject("SELECT lease_until<clock_timestamp() FROM platform_jobs WHERE id=?", Boolean.class, id)).isTrue();
                // Even with an expired lease, the executing transaction owns the lock and must be skipped.
                assertThat(jobs.claim()).isNull();
            } finally { release.countDown(); }
            executing.get(10, TimeUnit.SECONDS);
        }
        assertThat(jobs.job(id).state()).isEqualTo("SUCCEEDED");
        verify(identity, times(1)).ensureRealm(any(), anyBoolean());
    }

    @Test void suspendedProjectStaysDisabledAndReadyEnvironmentIsNotRewritten() {
        db.update("UPDATE projects SET status='SUSPENDED' WHERE id=?", projectId);
        enqueue();
        jobs.execute(jobs.claim());
        verify(identity).ensureRealm(any(), eq(false));
        enqueue();
        jobs.execute(jobs.claim());
        verify(identity, times(1)).ensureRealm(any(), anyBoolean());
    }

    @Test void workerHandlesUnexpectedFailureAndRestoresLoggingContext() {
        var job = enqueue();
        doThrow(new IllegalArgumentException("must-not-log-this")).when(identity).ensureRealm(any(), anyBoolean());
        MDC.put("requestId", "previous-context");
        new JobWorker(jobs).tick();
        assertThat(jobs.job(job.id()).state()).isEqualTo("RETRY_WAIT");
        assertThat(jobs.job(job.id()).errorCode()).isEqualTo("JOB_EXECUTION_FAILED");
        assertThat(MDC.get("requestId")).isEqualTo("previous-context");
        assertThat(MDC.get("jobId")).isNull();
        assertThatThrownBy(() -> jobs.list(null, "unknown", 20, 0, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jobs.list(null, null, 101, 0, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jobs.enqueue(UUID.randomUUID(), "test-admin", REQUEST)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void creationRangeIncludesStartExcludesEndAndCombinesWithEnvironmentAndState() {
        var job = enqueue();
        var start = Instant.parse("2026-01-01T00:00:00Z");
        db.update("UPDATE platform_jobs SET created_at=? WHERE id=?", java.sql.Timestamp.from(start), job.id());
        assertThat(jobs.list(environmentId, "QUEUED", 20, 0, start, start.plusSeconds(1))).hasSize(1);
        assertThat(jobs.list(environmentId, "QUEUED", 20, 0, start.minusSeconds(1), start)).isEmpty();
        assertThat(jobs.list(environmentId, "FAILED", 20, 0, start, null)).isEmpty();
        assertThat(jobs.list(UUID.randomUUID(), null, 20, 0, start, null)).isEmpty();
        assertThatThrownBy(() -> jobs.list(null, null, 20, 0, start, start)).isInstanceOf(ResponseStatusException.class);
    }

    private ProvisionJobs.Job enqueue() { return jobs.enqueue(environmentId, "test-admin", REQUEST); }
    private int count(String sql) { return db.queryForObject(sql, Integer.class); }
    private void due(UUID id) { db.update("UPDATE platform_jobs SET next_run_at=now()-interval '1 second' WHERE id=?", id); }
    private void expire(UUID id) { db.update("UPDATE platform_jobs SET lease_until=now()-interval '1 second' WHERE id=?", id); }
}
