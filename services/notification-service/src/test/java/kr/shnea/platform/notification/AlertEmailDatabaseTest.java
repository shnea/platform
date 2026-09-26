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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class AlertEmailDatabaseTest {
    private JdbcTemplate admin,db;
    private TransactionTemplate tx;
    private JobEvents events;
    private AlertEmail email;
    private NcpMail mail;
    private String schema;
    private final UUID project=UUID.randomUUID(),env=UUID.randomUUID();
    private final JsonMapper json=new JsonMapper();
    private final Instant base=Instant.parse("2026-09-26T01:00:00Z");
    @BeforeEach void setup() {
        String url=System.getenv("JOB_TEST_DB_URL");schema="alert_test_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only"));admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        events=new JobEvents(db,tx,json);mail=mock(NcpMail.class);when(mail.ready()).thenReturn(true);email=new AlertEmail(db,tx,mail,"dev");
    }
    @AfterEach void cleanup() {if(admin!=null && schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    JobEvents.Event event(String type,long revision,int seconds) {
        return new JobEvents.Event(UUID.randomUUID(),"job."+type,1,"project-service",project,env,UUID.randomUUID(),revision,
            "a".repeat(32),null,base.plusSeconds(seconds),new JobEvents.Payload(type.toUpperCase(java.util.Locale.ROOT),type.equals("failed")?"JOB_EXECUTION_FAILED":null));
    }
    AlertEmail.Save policy(boolean enabled,boolean recovery,long revision,String kind) {
        return new AlertEmail.Save(project,env,kind,enabled,"ops@example.invalid",15,recovery,revision,"admin","b".repeat(32));
    }
    String decision(JobEvents.Event event) {return db.queryForObject("SELECT email_decision FROM operational_alerts WHERE event_id=?",String.class,event.id());}
    int count(String table) {return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}

    JobEvents.Event backlog(String type,UUID job,UUID cause,int seconds) {
        return new JobEvents.Event(UUID.randomUUID(),"job."+type,1,"project-service",project,env,job,1,"a".repeat(32),cause,base.plusSeconds(seconds),
            new JobEvents.Payload(type.toUpperCase(java.util.Locale.ROOT),type.equals("backlogged")?"JOB_QUEUE_DELAYED":type.equals("backlog_closed")?"MONITORING_CHANGED":null));
    }
    @Test void backlogRecoversByIncidentAndNeverRecoversFinalFailure() {
        email.save(policy(true,true,0,"DEV"));var job=UUID.randomUUID();var open=backlog("backlogged",job,null,1);
        events.receive(open);events.receive(open);email.dispatch();
        var failed=new JobEvents.Event(UUID.randomUUID(),"job.failed",1,"project-service",project,env,job,1,"a".repeat(32),null,base.plusSeconds(2),new JobEvents.Payload("FAILED","JOB_EXECUTION_FAILED"));
        events.receive(failed);email.dispatch();assertThat(count("alert_email_deliveries")).isEqualTo(2);
        var recovered=backlog("backlog_recovered",job,open.id(),3);events.receive(recovered);events.receive(recovered);email.dispatch();
        assertThat(count("alert_email_deliveries")).isEqualTo(3);
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,open.id())).isEqualTo(recovered.id());
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,failed.id())).isNull();
        events.receive(backlog("backlogged",job,null,4));email.dispatch();assertThat(count("alert_email_deliveries")).isEqualTo(4);
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void backlogOutOfOrderCloseAndScopeCollisionDoNotSendStaleMail() {
        email.save(policy(true,true,0,"DEV"));var job=UUID.randomUUID();var open=backlog("backlogged",job,null,1);var recovered=backlog("backlog_recovered",job,open.id(),2);
        events.receive(recovered);events.receive(open);assertThat(count("alert_email_deliveries")).isZero();
        assertThat(decision(open)).isEqualTo("STALE");
        assertThat(db.queryForObject("SELECT related_alert_id FROM operational_alerts WHERE event_id=?",UUID.class,recovered.id())).isEqualTo(open.id());
        var another=backlog("backlogged",UUID.randomUUID(),null,3);events.receive(another);
        assertThatThrownBy(()->events.receive(backlog("backlog_recovered",UUID.randomUUID(),another.id(),4))).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,another.id())).isNull();
    }
    @Test void backlogDisableClosesWithoutRecoveryMailAndCancelsPendingAlert() {
        email.save(policy(true,true,0,"DEV"));var job=UUID.randomUUID();var open=backlog("backlogged",job,null,1);events.receive(open);
        var close=backlog("backlog_closed",job,open.id(),2);events.receive(close);email.dispatch();
        assertThat(decision(close)).isEqualTo("MONITORING_CLOSED");
        assertThat(email.deliveries(project,env,20,0)).extracting(AlertEmail.Delivery::state).containsExactly("CANCELLED");
        var alerts=new OperationalAlerts(db,tx).list(project,env,null,20,0);
        assertThat(alerts.stream().filter(a->a.id().equals(open.id())).findFirst().orElseThrow().resolutionType()).isEqualTo("job.backlog_closed");
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void disabledByDefaultAndDevNeverCallsProviderEvenWhenCredentialsExist() {
        assertThat(email.settings(project,env,"DEV").enabled()).isFalse();
        assertThat(email.settings(project,env,"PROD").deliveryMode()).isEqualTo("BLOCKED");
        events.receive(event("failed",1,0));email.dispatch();assertThat(count("alert_email_deliveries")).isZero();
        email.save(policy(true,true,0,"DEV"));events.receive(event("failed",1,1));email.dispatch();
        assertThat(email.deliveries(project,env,20,0)).extracting(AlertEmail.Delivery::state).containsExactly("MOCK");
        verify(mail,never()).send(anyString(),anyString(),anyString());
        assertThat(email.deliveries(UUID.randomUUID(),env,20,0)).isEmpty();
        assertThat(email.deliveries(project,UUID.randomUUID(),20,0)).isEmpty();
        assertThatThrownBy(()->email.save(policy(true,true,1,"PROD"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void concurrentFailuresSuppressionWindowAndRecoveryStartNewIncident() throws Exception {
        email.save(policy(true,true,0,"DEV"));var first=event("failed",1,0);events.receive(first);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=1;i<=4;i++){var next=event("failed",1,i);futures.add(pool.submit(()->events.receive(next)));}
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("operational_alerts")).isEqualTo(5);assertThat(count("alert_email_deliveries")).isEqualTo(1);
        db.update("UPDATE alert_email_deliveries SET created_at=now()-interval '16 minutes'");
        var later=event("failed",1,5);events.receive(later);assertThat(decision(later)).isEqualTo("QUEUED");
        var success=event("succeeded",1,6);events.receive(success);events.receive(success);
        assertThat(count("alert_email_deliveries")).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM operational_alerts WHERE code='JOB_FAILED' AND recovered_by IS NULL",Integer.class)).isZero();
        var regression=event("failed",1,7);events.receive(regression);assertThat(decision(regression)).isEqualTo("QUEUED");
        email.dispatch();assertThat(email.deliveries(project,env,20,0)).allMatch(d->d.state().equals("MOCK"));
    }
    @Test void reverseArrivalAndRevisionOrderingNeverSendStaleRecoveryOrReopenRecoveredFailure() {
        email.save(policy(true,true,0,"DEV"));
        var success=event("succeeded",2,0);events.receive(success);
        var old=event("failed",1,50);events.receive(old);assertThat(decision(old)).isEqualTo("STALE");
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,old.id())).isEqualTo(success.id());
        var current=event("failed",3,1);events.receive(current);
        events.receive(event("succeeded",2,100));events.receive(event("cancelled",4,200));
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,current.id())).isNull();
        assertThat(count("alert_email_deliveries")).isEqualTo(1);
        events.receive(event("succeeded",3,1)); // Equal revision and timestamp must not falsely recover.
        assertThat(db.queryForObject("SELECT recovered_by FROM operational_alerts WHERE event_id=?",UUID.class,current.id())).isNull();
    }
    @Test void recoverySwitchAndAcknowledgementAreIndependent() {
        email.save(policy(true,false,0,"DEV"));var failure=event("failed",1,0);events.receive(failure);
        var alerts=new OperationalAlerts(db,tx);alerts.acknowledge(failure.id(),new OperationalAlerts.Acknowledge(project,env,"admin","c".repeat(32)));
        assertThat(alerts.list(project,env,true,20,0).getFirst().recoveredBy()).isNull();
        var success=event("succeeded",1,1);events.receive(success);
        assertThat(decision(success)).isEqualTo("RECOVERY_DISABLED");assertThat(count("alert_email_deliveries")).isEqualTo(1);
        assertThat(alerts.list(project,env,true,20,0).getFirst().recoveredBy()).isEqualTo(success.id());
        assertThat(count("alert_acknowledgements")).isEqualTo(1);
    }
    @Test void settingsRaceHasOneWinnerCancelsPendingAndAuditFailureRollsBack() throws Exception {
        email.save(policy(true,true,0,"DEV"));events.receive(event("failed",1,0));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var futures=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try{email.save(policy(false,true,1,"DEV"));return true;}catch(ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}}));
            start.countDown();int wins=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
        }
        assertThat(count("alert_email_settings_audit")).isEqualTo(2);assertThat(email.deliveries(project,env,20,0).getFirst().state()).isEqualTo("CANCELLED");
        db.execute("ALTER TABLE alert_email_settings_audit ADD CONSTRAINT injected CHECK(revision<3)");
        assertThatThrownBy(()->email.save(policy(true,true,2,"DEV"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(email.settings(project,env,"DEV").enabled()).isFalse();assertThat(email.settings(project,env,"DEV").revision()).isEqualTo(2);
    }
    @Test void productionClaimIsExclusiveAndUnknownIsNeverAutomaticallyRetried() throws Exception {
        var prod=new AlertEmail(db,tx,mail,"prod");prod.save(policy(true,true,0,"PROD"));events.receive(event("failed",1,0));
        when(mail.send(anyString(),anyString(),anyString())).thenReturn(new NcpMail.Outcome("UNKNOWN",null));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(prod::dispatch);var b=pool.submit(prod::dispatch);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        prod.dispatch();verify(mail,times(1)).send(eq("ops@example.invalid"),anyString(),anyString());
        assertThat(prod.deliveries(project,env,20,0).getFirst().state()).isEqualTo("UNKNOWN");
        events.receive(event("succeeded",1,1));assertThat(prod.claim()).isNotNull(); // Simulate crash after claim.
        db.update("UPDATE alert_email_deliveries SET started_at=now()-interval '3 minutes' WHERE state='SENDING'");
        new AlertEmail(db,tx,mail,"prod").dispatch();verify(mail,times(1)).send(anyString(),anyString(),anyString());
        assertThat(prod.deliveries(project,env,20,0)).allMatch(d->d.state().equals("UNKNOWN"));
    }
    @Test void queueFailureRollsBackReceiptAndLateProviderConfigurationIsChecked() {
        var prod=new AlertEmail(db,tx,mail,"prod");prod.save(policy(true,true,0,"PROD"));
        db.execute("ALTER TABLE alert_email_deliveries ADD CONSTRAINT injected CHECK(state<>'PENDING')");
        var failure=event("failed",1,0);assertThatThrownBy(()->events.receive(failure)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("received_job_events")).isZero();assertThat(count("operational_alerts")).isZero();
        db.execute("ALTER TABLE alert_email_deliveries DROP CONSTRAINT injected");events.receive(failure);
        when(mail.ready()).thenReturn(false);prod.dispatch();
        assertThat(prod.deliveries(project,env,20,0).getFirst().state()).isEqualTo("BLOCKED");verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void internalPolicyEndpointsRequireEventKeyAndValidatePayload() throws Exception {
        String secret="e".repeat(40),mailSecret="m".repeat(40),path="/internal/v1/operational-alerts/email-settings";
        var mvc=MockMvcBuilders.standaloneSetup(email).setControllerAdvice(new EmailErrors())
            .addFilters(new kr.shnea.platform.http.RequestTrace(),new InternalSecurity(mailSecret,secret,json)).build();
        var body=json.writeValueAsString(policy(true,true,0,"DEV"));
        mvc.perform(put(path).header("X-Platform-Mail-Key",mailSecret).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).header("X-Platform-Event-Key",secret).contentType("application/json").content(body.replace("ops@example.invalid","a@b.invalid,c@d.invalid"))).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("X-Platform-Event-Key",secret).contentType("application/json").content(body.replace("\"suppressionMinutes\":15","\"suppressionMinutes\":0"))).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("X-Platform-Event-Key",secret).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(put(path).header("X-Platform-Event-Key",secret).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_CHANGED"));
    }
}
