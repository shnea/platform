package kr.shnea.platform.project;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class OutboxDelivery {
    record Delivery(UUID id, UUID jobId, UUID environmentId, String eventType, String state, int attempts,
                    int cycleAttempts, String errorCode, Instant nextRunAt, Instant deliveredAt) {}
    record Attempt(int attempt, String state, String errorCode, Integer httpStatus, Instant startedAt, Instant endedAt) {}
    record Detail(Delivery delivery, List<Attempt> attempts) {}
    record Event(UUID id, String type, int schemaVersion, String source, UUID projectId, UUID environmentId,
                 UUID targetId, long targetRevision, String requestId, UUID causationId, Instant occurredAt, JsonNode payload) {}
    record Claim(Event event, UUID token, int attempt, int cycleAttempt) {}
    record Receipt(UUID id, String state) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final RestClient http;

    OutboxDelivery(JdbcTemplate db, TransactionTemplate tx, JsonMapper json,
            @Value("${PLATFORM_EVENTS_SECRET:}") String secret,
            @Value("${platform.events.enabled:false}") boolean enabled,
            @Value("${NOTIFICATION_INTERNAL_URL:http://notification-service:8080}") String url) {
        this.db=db; this.tx=tx; this.json=json;
        if (enabled && secret.length()<32) throw new IllegalArgumentException("Configure PLATFORM_EVENTS_SECRET (32+ characters)");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        http=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Platform-Event-Key",secret)
            .requestInterceptor(kr.shnea.platform.http.RequestTrace.propagate()).build();
    }
    List<Detail> forJob(UUID jobId) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_jobs WHERE id=?)",Boolean.class,jobId)))
            throw ApiCode.RESOURCE_NOT_FOUND.failure();
        return db.query("SELECT * FROM project_outbox WHERE job_id=? ORDER BY occurred_at,id",(rs,row)->map(rs),jobId)
            .stream().map(delivery->new Detail(delivery,db.query("SELECT * FROM outbox_attempts WHERE event_id=? ORDER BY attempt DESC LIMIT 100",
                (rs,row)->new Attempt(rs.getInt("attempt"),rs.getString("state"),rs.getString("error_code"),
                    rs.getObject("http_status",Integer.class),instant(rs,"started_at"),instant(rs,"ended_at")),delivery.id()))).toList();
    }
    Delivery retry(UUID id,String actor) {
        return tx.execute(status->{
            var row=db.query("SELECT * FROM project_outbox WHERE id=? FOR UPDATE",(rs,n)->map(rs),id);
            if (row.isEmpty()) throw ApiCode.RESOURCE_NOT_FOUND.failure();
            var delivery=row.getFirst();
            if (delivery.state().equals("PENDING")) return delivery;
            if (!delivery.state().equals("FAILED")) throw ApiCode.EVENT_NOT_RETRYABLE.failure();
            db.update("UPDATE project_outbox SET state='PENDING',cycle_attempts=0,error_code=NULL,next_run_at=now(),lease_token=NULL,lease_until=NULL WHERE id=?",id);
            db.update("INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES (?,'event.requeued',?,?)",actor,id,delivery.environmentId());
            return db.queryForObject("SELECT * FROM project_outbox WHERE id=?",(rs,n)->map(rs),id);
        });
    }
    Claim claim() {
        return tx.execute(status->{
            var rows=db.queryForList("""
                SELECT id,attempts,cycle_attempts,lease_token FROM project_outbox
                WHERE state='PENDING' AND next_run_at<=now() AND (lease_until IS NULL OR lease_until<now())
                ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                """);
            if (rows.isEmpty()) return null;
            var row=rows.getFirst(); UUID id=(UUID)row.get("id");
            int attempts=((Number)row.get("attempts")).intValue(),cycle=((Number)row.get("cycle_attempts")).intValue();
            if (row.get("lease_token")!=null)
                db.update("UPDATE outbox_attempts SET state='ABANDONED',error_code='DELIVERY_INTERRUPTED',ended_at=now() WHERE event_id=? AND state='RUNNING'",id);
            if (cycle>=5) {
                db.update("UPDATE project_outbox SET state='FAILED',error_code='DELIVERY_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE id=?",id);
                return null;
            }
            UUID token=UUID.randomUUID();
            db.update("UPDATE project_outbox SET attempts=attempts+1,cycle_attempts=cycle_attempts+1,lease_token=?,lease_until=now()+interval '60 seconds',error_code=NULL WHERE id=?",token,id);
            db.update("INSERT INTO outbox_attempts(event_id,attempt,state) VALUES (?,?,'RUNNING')",id,attempts+1);
            Event event=db.queryForObject("SELECT * FROM project_outbox WHERE id=?",(rs,n)->new Event(id,rs.getString("event_type"),
                rs.getInt("schema_version"),rs.getString("source"),rs.getObject("project_id",UUID.class),rs.getObject("environment_id",UUID.class),
                rs.getObject("job_id",UUID.class),rs.getLong("target_revision"),rs.getString("request_id"),rs.getObject("causation_id",UUID.class),
                instant(rs,"occurred_at"),json.readTree(rs.getString("payload"))),id);
            return new Claim(event,token,attempts+1,cycle+1);
        });
    }
    void deliver(Claim claim) {
        tx.executeWithoutResult(status->{
            // One bounded HTTP request under the event row lock; a crash leaves the committed lease recoverable.
            if (!owned(claim,true)) return;
            Receipt receipt=http.post().uri("/internal/v1/events/jobs").body(claim.event()).retrieve().body(Receipt.class);
            if (receipt==null || !claim.event().id().equals(receipt.id()) || !"ACCEPTED".equals(receipt.state()))
                throw new IllegalStateException("Unconfirmed event receipt");
            db.update("UPDATE project_outbox SET state='DELIVERED',delivered_at=now(),error_code=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",claim.event().id());
            db.update("UPDATE outbox_attempts SET state='DELIVERED',http_status=200,ended_at=now() WHERE event_id=? AND attempt=?",claim.event().id(),claim.attempt());
        });
    }
    void failed(Claim claim,Exception error) {
        Integer code=error instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
        boolean permanent=code!=null && code>=400 && code<500 && code!=408 && code!=429;
        String reason=code==null ? "DELIVERY_UNCONFIRMED" : permanent ? "DELIVERY_REJECTED" : "DELIVERY_UNAVAILABLE";
        tx.executeWithoutResult(status->{
            if (!owned(claim,false)) return;
            db.update("UPDATE outbox_attempts SET state='FAILED',error_code=?,http_status=?,ended_at=now() WHERE event_id=? AND attempt=?",reason,code,claim.event().id(),claim.attempt());
            int seconds=switch(claim.cycleAttempt()) {case 1->10;case 2->30;case 3->120;default->300;};
            db.update("""
                UPDATE project_outbox SET state=?,error_code=?,next_run_at=now()+(? * interval '1 second'),lease_token=NULL,lease_until=NULL WHERE id=?
                """,permanent || claim.cycleAttempt()>=5 ? "FAILED":"PENDING",reason,seconds,claim.event().id());
        });
    }
    private boolean owned(Claim claim,boolean unexpired) {
        return !db.queryForList("SELECT id FROM project_outbox WHERE id=? AND state='PENDING' AND lease_token=?"+
            (unexpired ? " AND lease_until>now()":"")+" FOR UPDATE",claim.event().id(),claim.token()).isEmpty();
    }
    private static Instant instant(ResultSet rs,String key) throws SQLException {
        var value=rs.getTimestamp(key); return value==null ? null:value.toInstant();
    }
    private static Delivery map(ResultSet rs) throws SQLException {
        return new Delivery(rs.getObject("id",UUID.class),rs.getObject("job_id",UUID.class),rs.getObject("environment_id",UUID.class),
            rs.getString("event_type"),rs.getString("state"),rs.getInt("attempts"),rs.getInt("cycle_attempts"),rs.getString("error_code"),
            instant(rs,"next_run_at"),instant(rs,"delivered_at"));
    }
}
