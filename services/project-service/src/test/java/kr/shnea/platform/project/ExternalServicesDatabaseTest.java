package kr.shnea.platform.project;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class ExternalServicesDatabaseTest {
 JdbcTemplate admin,db;ProjectService projects;ExternalJobs jobs;CommonLogs logs;UUID env,other;String schema;
 HttpServer loki;AtomicReference<String> received=new AtomicReference<>(),tenant=new AtomicReference<>();
 final JsonMapper json=new JsonMapper();
 @BeforeEach void setup()throws Exception {
  String url=System.getenv("JOB_TEST_DB_URL");schema="external_test_"+UUID.randomUUID().toString().replace("-","");
  admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only"));admin.execute("CREATE SCHEMA "+schema);
  var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
  Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();db=new JdbcTemplate(ds);
  var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));projects=new ProjectService(db,tx,mock(IdentityClient.class),"dev");jobs=new ExternalJobs(db,tx,projects);
  env=environment("one");other=environment("two");
  loki=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  loki.createContext("/",exchange->{tenant.set(exchange.getRequestHeaders().getFirst("X-Scope-OrgID"));received.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));exchange.sendResponseHeaders(204,-1);exchange.close();});loki.start();
  logs=new CommonLogs(db,projects,"http://127.0.0.1:"+loki.getAddress().getPort());
 }
 UUID environment(String code){UUID id=UUID.randomUUID(),project=projects.createProject(code,code,"test").id();db.update("INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state) VALUES (?,?,'dev','DEV',?,false,'[]','READY')",id,project,"p-"+id);return id;}
 @AfterEach void cleanup(){if(loki!=null)loki.stop(0);if(admin!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
 ExternalJobs.Submit request(UUID id,int max){return new ExternalJobs.Submit(id,"export",json.readTree("{\"item\":42}"),max,null);}
 ExternalJobs.Job enqueue(int max){return jobs.enqueue(env,request(UUID.randomUUID(),max),"test","0123456789abcdef0123456789abcdef");}
 void due(UUID id){db.update("UPDATE external_jobs SET next_run_at=now()-interval '1 second' WHERE id=?",id);}
 void fails(Runnable operation,ApiCode expected){assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiCode.Failure.class,error->assertThat(error.code).isEqualTo(expected));}
 @Test void allAdvertisedScopesPassRequestValidationAndIssueWithoutWideningPermissions() {
  db.update("UPDATE projects SET files_enabled=true");
  try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
   var validator=factory.getValidator();
   for(String mode:List.of("dev","prod")) {
    db.update("UPDATE environments SET kind=? WHERE id=?",mode.equals("dev")?"DEV":"PROD",env);
    var service=new ProjectService(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),mock(IdentityClient.class),mode);
    var scopes=service.credentialScopes(env).stream().map(ProjectService.Scope::code).toList();
    assertThat(scopes).hasSize(mode.equals("dev")?14:13);
    assertThat(validator.validate(new ProjectController.NewCredential(null,scopes))).isEmpty();
    var key=service.issueCredential(env,null,scopes,"test");
    assertThat(key.scopes()).containsExactlyElementsOf(scopes);
    for(String scope:scopes)assertThat(service.context(key.apiKey(),scope).environmentId()).isEqualTo(env);
    fails(()->service.issueCredential(env,null,List.of("unknown:permission"),"test"),ApiCode.INVALID_CREDENTIAL_SCOPES);
    fails(()->service.issueCredential(env,null,List.of("logs:read","logs:read"),"test"),ApiCode.INVALID_CREDENTIAL_SCOPES);
    if(mode.equals("prod"))fails(()->service.issueCredential(env,null,List.of("auth:mock"),"test"),ApiCode.INVALID_CREDENTIAL_SCOPES);
   }
   assertThat(validator.validate(new ProjectController.NewCredential(null,List.of()))).isNotEmpty();
   assertThat(validator.validate(new ProjectController.NewCredential(null,List.of("")))).isNotEmpty();
  }
 }
 @Test void scopedKeysNeverGainNewPermissionsAndRevocationBlocksAccess() {
  var old=projects.issueCredential(env,null,null,"test");fails(()->projects.context(old.apiKey(),"jobs:write"),ApiCode.INSUFFICIENT_SCOPE);
  for(String scope:List.of("jobs:read","jobs:write","jobs:work","logs:read","logs:write","ai:read","ai:route","ai:embed")) {
   fails(()->projects.context(old.apiKey(),scope),ApiCode.INSUFFICIENT_SCOPE);
   var key=projects.issueCredential(env,null,List.of(scope),"test");assertThat(projects.context(key.apiKey(),scope).environmentId()).isEqualTo(env);
   String wrong=scope.equals("logs:read")?"logs:write":"logs:read";fails(()->projects.context(key.apiKey(),wrong),ApiCode.INSUFFICIENT_SCOPE);
   projects.revokeCredential(key.id(),"test");fails(()->projects.context(key.apiKey(),scope),ApiCode.INVALID_API_KEY);
  }
 }
 @Test void concurrentDuplicatesAndClaimsHaveOneWinner()throws Exception {
  UUID request=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(4)){
   var submissions=new ArrayList<Future<ExternalJobs.Job>>();for(int i=0;i<4;i++)submissions.add(pool.submit(()->jobs.enqueue(env,request(request,3),"test",null)));
   var id=submissions.getFirst().get().id();for(var value:submissions)assertThat(value.get().id()).isEqualTo(id);
   var claims=new ArrayList<Future<ExternalJobs.Claim>>();for(int i=0;i<4;i++)claims.add(pool.submit(()->jobs.claim(env,"export","worker","test")));
   int winners=0;for(var value:claims)if(value.get()!=null)winners++;assertThat(winners).isEqualTo(1);
   fails(()->jobs.enqueue(env,request(request,4),"test",null),ApiCode.EXTERNAL_JOB_REQUEST_CONFLICT);
  }
 }
 @Test void heartbeatCompletionIsolationAndCancellation() {
  UUID id=enqueue(3).id();var claim=jobs.claim(env,"export","worker","test");
  fails(()->jobs.detail(other,id),ApiCode.RESOURCE_NOT_FOUND);fails(()->jobs.heartbeat(other,id,claim.leaseToken(),10),ApiCode.RESOURCE_NOT_FOUND);
  fails(()->jobs.heartbeat(env,id,"bad",10),ApiCode.EXTERNAL_JOB_LEASE_LOST);
  assertThat(jobs.heartbeat(env,id,claim.leaseToken(),40).progress()).isEqualTo(40);
  fails(()->jobs.cancel(env,id,"test"),ApiCode.JOB_NOT_CANCELLABLE);
  var result=json.readTree("{\"done\":true}");assertThat(jobs.report(env,id,claim.leaseToken(),true,result,null,false,"test").state()).isEqualTo("SUCCEEDED");
  jobs.report(env,id,claim.leaseToken(),true,result,null,false,"test");
  fails(()->jobs.report(env,id,claim.leaseToken(),true,json.createObjectNode(),null,false,"test"),ApiCode.EXTERNAL_JOB_LEASE_LOST);
  UUID pending=enqueue(3).id();jobs.cancel(env,pending,"test");assertThat(jobs.claim(env,"export","worker","test")).isNull();
 }
 @Test void expiredLeaseRecoversAndOldWorkerCannotOverwrite() {
  UUID id=enqueue(2).id();var old=jobs.claim(env,"export","old","test");
  db.update("UPDATE external_jobs SET lease_until=now()-interval '1 second' WHERE id=?",id);
  fails(()->jobs.report(env,id,old.leaseToken(),true,null,null,false,"test"),ApiCode.EXTERNAL_JOB_LEASE_LOST);
  jobs.maintain();assertThat(jobs.detail(env,id).job().state()).isEqualTo("RETRY_WAIT");due(id);
  var next=jobs.claim(env,"export","new","test");fails(()->jobs.heartbeat(env,id,old.leaseToken(),80),ApiCode.EXTERNAL_JOB_LEASE_LOST);
  assertThat(jobs.report(env,id,next.leaseToken(),false,null,"BUSY",true,"test").state()).isEqualTo("FAILED");
  var retry=jobs.retry(env,id,"test",null);assertThat(jobs.retry(env,id,"test",null).id()).isEqualTo(retry.id());
  assertThat(jobs.detail(env,id).attempts()).extracting(ExternalJobs.Attempt::state).containsExactly("ABANDONED","FAILED");
 }
 @Test void failureRetryIsBoundedAndIdempotentReportsMustMatch() {
  UUID id=enqueue(2).id();var first=jobs.claim(env,"export","worker","test");
  jobs.report(env,id,first.leaseToken(),false,null,"BUSY",true,"test");jobs.report(env,id,first.leaseToken(),false,null,"BUSY",true,"test");
  fails(()->jobs.report(env,id,first.leaseToken(),false,null,"BUSY",false,"test"),ApiCode.EXTERNAL_JOB_LEASE_LOST);
  assertThat(jobs.claim(env,"export","worker","test")).isNull();due(id);
  var next=jobs.claim(env,"export","worker","test");assertThat(jobs.report(env,id,next.leaseToken(),false,null,"BUSY",true,"test").state()).isEqualTo("FAILED");
  db.update("UPDATE external_jobs SET completed_at=now()-interval '31 days' WHERE id=?",id);jobs.maintain();fails(()->jobs.detail(env,id),ApiCode.RESOURCE_NOT_FOUND);
  assertThat(db.queryForObject("SELECT count(*) FROM external_job_attempts WHERE job_id=?",Integer.class,id)).isZero();
 }
 CommonLogs.Batch batch(String message){return new CommonLogs.Batch(List.of(new CommonLogs.Entry(null,"api","ERROR",message,"request-1","trace-1","BUSY",json.readTree("{\"nested\":{\"password\":\"never-store\",\"count\":2},\"apiKey\":\"never-store\"}"),"Authorization=secret-stack")));}
 @Test void logTenantMaskingQuotaAndOutageAreExplicit() {
  assertThat(logs.ingest(env,batch("password=secret-pass Bearer secret-auth test@example.com")).accepted()).isEqualTo(1);
  assertThat(tenant.get()).isEqualTo(env.toString());assertThat(received.get()).doesNotContain("never-store","secret-pass","secret-auth","test@example.com","secret-stack").contains("REDACTED","request-1","trace-1");
  db.update("UPDATE log_ingestion_budgets SET reserved_bytes=? WHERE environment_id=?",CommonLogs.DAILY_BYTES,env);
  fails(()->logs.ingest(env,batch("quota")),ApiCode.LOG_QUOTA_EXCEEDED);logs.ingest(other,batch("isolated"));assertThat(tenant.get()).isEqualTo(other.toString());
  loki.stop(0);fails(()->logs.ingest(other,batch("offline")),ApiCode.LOG_BACKEND_UNAVAILABLE);
 }
 @Test void logInputAndQueryBoundsDoNotReachStorage() {
  fails(()->logs.ingest(env,new CommonLogs.Batch(List.of(new CommonLogs.Entry(null,"api",null,"message",null,null,null,null,null)))),ApiCode.INVALID_REQUEST);
  fails(()->logs.ingest(env,batch("x".repeat(8193))),ApiCode.PAYLOAD_TOO_LARGE);
  fails(()->logs.query(env,Instant.now().minus(Duration.ofDays(8)),null,null,null,null,null,null,100),ApiCode.INVALID_REQUEST);
  fails(()->logs.query(env,null,null,"bad\"service",null,null,null,null,100),ApiCode.INVALID_REQUEST);
  assertThat(received.get()).isNull();
 }
 @Test void logMinuteQuotaRollsOverAndNestedNullIsPreserved() {
  logs.ingest(env,batch("first"));db.update("UPDATE log_ingestion_budgets SET minute_requests=120 WHERE environment_id=?",env);
  fails(()->logs.ingest(env,batch("limited")),ApiCode.LOG_QUOTA_EXCEEDED);
  db.update("UPDATE log_ingestion_budgets SET minute_bucket=now()-interval '2 minutes' WHERE environment_id=?",env);
  logs.ingest(env,batch("new minute"));assertThat(db.queryForObject("SELECT minute_requests FROM log_ingestion_budgets WHERE environment_id=?",Integer.class,env)).isEqualTo(1);
  assertThat(LogSanitizer.attributes(json.readTree("{\"value\":null}"),0).get("value").isNull()).isTrue();
 }
}
