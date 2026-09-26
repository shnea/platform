package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(40)
class OutboxDatabaseTest {
    private JdbcTemplate admin,db;
    private TransactionTemplate tx;
    private String schema;
    private HttpServer server;
    private OutboxDelivery delivery;
    private UUID jobId,eventId;
    private final AtomicInteger calls=new AtomicInteger();
    private volatile int response=200;
    private volatile boolean loseResponse=false,wrongReceipt=false;
    private volatile String lastBody,lastKey,lastTrace;
    private volatile CountDownLatch entered,release;
    private static final String SECRET="event-test-"+"e".repeat(40), TRACE="0123456789abcdef0123456789abcdef";

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("JOB_TEST_DB_URL");
        schema="outbox_test_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only"));
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var projects=new ProjectService(db,tx,mock(IdentityClient.class),"dev");
        UUID project=projects.createProject("outbox-test","이벤트 검증","test").id(),env=UUID.randomUUID();
        db.update("INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state) VALUES (?,?,'dev','DEV',?,false,'[]','READY')",env,project,"p-"+env);
        var jobs=new ProvisionJobs(db,tx,projects);
        jobId=jobs.enqueue(env,"test",TRACE).id(); jobs.cancel(jobId,"test");
        eventId=db.queryForObject("SELECT id FROM project_outbox WHERE job_id=?",UUID.class,jobId);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/events/jobs",exchange->{
            calls.incrementAndGet();
            lastBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            lastKey=exchange.getRequestHeaders().getFirst("X-Platform-Event-Key");
            lastTrace=exchange.getRequestHeaders().getFirst("X-Request-ID");
            if (entered!=null) {
                entered.countDown();
                try { release.await(8,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (loseResponse) { exchange.close(); return; }
            byte[] body=("{\"id\":\""+(wrongReceipt ? UUID.randomUUID():eventId)+"\",\"state\":\"ACCEPTED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(response,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); delivery=newDelivery();
    }
    private OutboxDelivery newDelivery() {
        return new OutboxDelivery(db,tx,new JsonMapper(),SECRET,true,"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void cleanup() {
        if (release!=null) release.countDown();
        if (server!=null) server.stop(0);
        if (admin!=null && schema!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE");
        MDC.clear();
    }
    private void due() { db.update("UPDATE project_outbox SET next_run_at=now()-interval '1 second' WHERE id=?",eventId); }
    private OutboxDelivery.Detail detail() { return delivery.forJob(jobId).getFirst(); }

    @Test void committedEventDeliversThroughHttpWithVersionScopeTraceAndHistory() {
        MDC.put("requestId","old"); new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("DELIVERED");
        assertThat(detail().attempts()).extracting(OutboxDelivery.Attempt::state).containsExactly("DELIVERED");
        assertThat(lastKey).isEqualTo(SECRET); assertThat(lastTrace).isEqualTo(TRACE);
        var body=new JsonMapper().readTree(lastBody);
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.path("targetId").asText()).isEqualTo(jobId.toString());
        assertThat(body.path("type").asText()).isEqualTo("job.cancelled");
        assertThat(body.path("projectId").asText()).isNotBlank();
        assertThat(body.path("targetRevision").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(MDC.get("requestId")).isEqualTo("old"); assertThat(MDC.get("eventId")).isNull();
        new OutboxWorker(delivery).tick(); assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(()->delivery.retry(eventId,"test")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void responseLossAndWrongAcknowledgementRemainRetryableAndKeepSameEvent() {
        loseResponse=true; new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("PENDING");
        assertThat(detail().delivery().errorCode()).isEqualTo("DELIVERY_UNCONFIRMED");
        String original=lastBody; loseResponse=false; wrongReceipt=true; due(); new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("PENDING");
        wrongReceipt=false; due(); new OutboxWorker(delivery).tick();
        assertThat(lastBody).isEqualTo(original); assertThat(detail().delivery().state()).isEqualTo("DELIVERED");
        assertThat(detail().attempts()).hasSize(3);
    }
    @Test void transientErrorsAreBoundedAndManualReplayPreservesHistory() {
        response=503;
        for (int i=1;i<=5;i++) {
            new OutboxWorker(delivery).tick();
            assertThat(detail().delivery().attempts()).isEqualTo(i);
            if (i<5) { assertThat(delivery.claim()).isNull(); due(); }
        }
        assertThat(detail().delivery().state()).isEqualTo("FAILED");
        delivery.retry(eventId,"test-admin"); delivery.retry(eventId,"test-admin");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_events WHERE action='event.requeued'",Integer.class)).isEqualTo(1);
        response=200; new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("DELIVERED");
        assertThat(detail().delivery().attempts()).isEqualTo(6); assertThat(detail().delivery().cycleAttempts()).isEqualTo(1);
        assertThat(detail().attempts()).hasSize(6);
    }
    @Test void unsupportedVersionRejectionStopsAndRateLimitRetries() {
        response=422; new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("FAILED");
        assertThat(detail().attempts().getFirst().httpStatus()).isEqualTo(422);
        delivery.retry(eventId,"test"); response=429; new OutboxWorker(delivery).tick();
        assertThat(detail().delivery().state()).isEqualTo("PENDING");
    }
    @Test void restartRecoversClaimAndFencesPreviousOwner() {
        var old=delivery.claim(); assertThat(delivery.claim()).isNull();
        db.update("UPDATE project_outbox SET lease_until=now()-interval '1 second' WHERE id=?",eventId);
        delivery=newDelivery(); var current=delivery.claim();
        delivery.deliver(old); delivery.failed(old,new IllegalStateException()); assertThat(calls.get()).isZero();
        delivery.deliver(current);
        assertThat(detail().attempts()).extracting(OutboxDelivery.Attempt::state).containsExactly("DELIVERED","ABANDONED");
    }
    @Test void concurrentClaimsAndExpiredLeaseCannotDuplicateAnActiveHttpCall() throws Exception {
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(4)) {
            var futures=new java.util.ArrayList<Future<OutboxDelivery.Claim>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(()->{start.await();return delivery.claim();}));
            start.countDown(); var claims=new java.util.ArrayList<OutboxDelivery.Claim>();
            for (var future:futures) {var c=future.get(10,TimeUnit.SECONDS);if(c!=null)claims.add(c);}
            assertThat(claims).hasSize(1);
            db.update("UPDATE project_outbox SET lease_until=now()+interval '1 second' WHERE id=?",eventId);
            entered=new CountDownLatch(1);release=new CountDownLatch(1);
            var running=pool.submit(()->delivery.deliver(claims.getFirst()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Thread.sleep(1200); assertThat(delivery.claim()).isNull(); release.countDown(); running.get(10,TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
