package kr.shnea.platform.notification;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class JobEventsDatabaseTest {
    private JdbcTemplate admin,db;
    private JobEvents events;
    private String schema;
    private final JsonMapper json=new JsonMapper();
    private static final String SECRET="event-test-"+"e".repeat(40),MAIL="mail-test-"+"m".repeat(40);
    @BeforeEach void setup() {
        String url=System.getenv("JOB_TEST_DB_URL");
        schema="event_test_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only"));
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds); events=new JobEvents(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),json);
    }
    @AfterEach void cleanup() { if(admin!=null && schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private JobEvents.Event event(UUID project,UUID env,Instant occurred) {
        return new JobEvents.Event(UUID.randomUUID(),"job.failed",1,"project-service",project,env,UUID.randomUUID(),2,
            "0123456789abcdef0123456789abcdef",null,occurred,new JobEvents.Payload("FAILED","ENVIRONMENT_PROVISION_FAILED"));
    }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Integer.class); }

    @Test void simultaneousDuplicatesAndResponseLossReplayCreateOnlyOneAlert() throws Exception {
        var event=event(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<Future<JobEvents.Receipt>>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{start.await();return events.receive(event);}));
            start.countDown(); for(var future:futures)assertThat(future.get(10,TimeUnit.SECONDS).id()).isEqualTo(event.id());
        }
        // The sender may lose the acknowledgement after commit and redeliver the same envelope.
        assertThat(events.receive(event).state()).isEqualTo("ACCEPTED");
        assertThat(count("received_job_events")).isEqualTo(1); assertThat(count("operational_alerts")).isEqualTo(1);
    }
    @Test void receiptRollsBackWithAlertFailureAndCanBeRetried() {
        var event=event(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        db.execute("CREATE FUNCTION fail_alert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        db.execute("CREATE TRIGGER fail_alert BEFORE INSERT ON operational_alerts FOR EACH ROW EXECUTE FUNCTION fail_alert()");
        assertThatThrownBy(()->events.receive(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("received_job_events")).isZero(); assertThat(count("operational_alerts")).isZero();
        db.execute("DROP TRIGGER fail_alert ON operational_alerts"); events.receive(event);
        assertThat(count("operational_alerts")).isEqualTo(1);
    }
    @Test void reverseOrderIsAppendOnlyAndCollidingIdsCannotMoveEventsAcrossProjects() {
        UUID project=UUID.randomUUID(),env=UUID.randomUUID();
        var newer=event(project,env,Instant.parse("2026-09-26T12:00:00Z"));
        var older=event(project,env,Instant.parse("2026-09-25T12:00:00Z"));
        events.receive(newer);events.receive(older);
        var collision=new JobEvents.Event(newer.id(),newer.type(),1,newer.source(),UUID.randomUUID(),UUID.randomUUID(),
            newer.targetId(),newer.targetRevision(),newer.requestId(),null,newer.occurredAt(),newer.payload());
        assertThatThrownBy(()->events.receive(collision)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        var otherId=new JobEvents.Event(UUID.randomUUID(),newer.type(),1,newer.source(),project,env,newer.targetId(),2,
            newer.requestId(),null,newer.occurredAt(),newer.payload());
        assertThatThrownBy(()->events.receive(otherId)).isInstanceOf(ResponseStatusException.class);
        assertThat(count("received_job_events")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM operational_alerts WHERE project_id=? AND environment_id=?",Integer.class,project,env)).isEqualTo(2);
    }
    @Test void internalAuthenticationVersionAndValidationFailBeforeEffects() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(events).setControllerAdvice(new EmailErrors())
            .addFilters(new kr.shnea.platform.http.RequestTrace(),new InternalSecurity(MAIL,SECRET,json)).build();
        var event=event(UUID.randomUUID(),UUID.randomUUID(),Instant.now()); String body=json.writeValueAsString(event);
        String path="/internal/v1/events/jobs";
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Platform-Mail-Key",MAIL).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Platform-Event-Key",MAIL).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Platform-Event-Key",SECRET).contentType("application/json").content(body.replace("\"schemaVersion\":1","\"schemaVersion\":2")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EVENT_VERSION_UNSUPPORTED"));
        mvc.perform(post(path).header("X-Platform-Event-Key",SECRET).contentType("application/json").content(body.replace("project-service","wrong-source")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
        assertThat(count("received_job_events")).isZero();
        mvc.perform(post(path).header("X-Platform-Event-Key",SECRET).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(event.id().toString())).andExpect(header().string("Cache-Control","no-store"));
        var disabled=MockMvcBuilders.standaloneSetup(events).addFilters(new InternalSecurity(MAIL,"",json)).build();
        disabled.perform(post(path).header("X-Platform-Event-Key",SECRET).contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertThat(count("operational_alerts")).isEqualTo(1);
    }
}
