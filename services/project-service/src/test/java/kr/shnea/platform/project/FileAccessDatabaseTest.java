package kr.shnea.platform.project;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL", matches="jdbc:postgresql://[^/]+/job_checks")
class FileAccessDatabaseTest {
    JdbcTemplate db, admin; ProjectService projects; UUID project, environment; String schema;
    @BeforeEach void setup() {
        String url=System.getenv("JOB_TEST_DB_URL"); schema="file_access_"+UUID.randomUUID().toString().replace("-", "");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only")); admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds);
        var identity=mock(IdentityClient.class); when(identity.issuer(anyString())).thenReturn("https://example.test");
        projects=new ProjectService(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),identity,"dev");
        project=projects.createProject("files-check","파일 검증","test").id(); environment=UUID.randomUUID();
        db.update("INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state) VALUES (?,?,'dev','DEV',?,false,'[]','READY')", environment,project,"p-"+environment);
    }
    @AfterEach void cleanup() { if(admin!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    void code(ApiCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiCode.Failure.class,e -> assertThat(e.code).isEqualTo(code));
    }
    @Test void newFileScopesAreExplicitAndRevocationExpirySuspensionTakeEffect() {
        var old=projects.issueCredential(environment,null,null,"test");
        code(ApiCode.INSUFFICIENT_SCOPE,()->projects.context(old.apiKey(),"files:read"));
        assertThat(projects.credentials(environment).getFirst().scopes()).containsExactly("integration:read");
        var key=projects.issueCredential(environment,null,List.of("files:read","files:write","files:delete"),"test");
        assertThat(projects.context(key.apiKey(),"files:write").environmentId()).isEqualTo(environment);
        code(ApiCode.INSUFFICIENT_SCOPE,()->projects.context(key.apiKey(),"auth:mock"));
        db.update("UPDATE projects SET status='SUSPENDED' WHERE id=?", project);
        code(ApiCode.INVALID_API_KEY,()->projects.context(key.apiKey(),"files:read"));
        db.update("UPDATE projects SET status='ACTIVE' WHERE id=?", project);
        db.update("UPDATE service_credentials SET expires_at=now()-interval '1 second' WHERE id=?", key.id());
        code(ApiCode.INVALID_API_KEY,()->projects.context(key.apiKey(),"files:read"));
        db.update("UPDATE service_credentials SET expires_at=NULL WHERE id=?", key.id());
        projects.revokeCredential(key.id(),"test");
        code(ApiCode.INVALID_API_KEY,()->projects.context(key.apiKey(),"files:read"));
    }
    @Test void internalAccessRequiresDedicatedSecretAndOnlyFileScopes() {
        var key=projects.issueCredential(environment,null,List.of("files:read"),"test");
        String secret="s".repeat(32);
        var controller=new FileAccessController(projects,secret);
        code(ApiCode.ACCESS_DENIED,()->controller.access(null,key.apiKey(),"files:read"));
        code(ApiCode.ACCESS_DENIED,()->controller.active(environment,"wrong"));
        code(ApiCode.ACCESS_DENIED,()->new FileAccessController(projects,"").access("",key.apiKey(),"files:read"));
        code(ApiCode.INSUFFICIENT_SCOPE,()->controller.access(secret,key.apiKey(),"integration:read"));
        var result=(Map<?,?>)controller.access(secret,key.apiKey(),"files:read");
        assertThat(result.get("credentialId")).isEqualTo(key.id());
        assertThat(result.get("environmentId")).isEqualTo(environment);
    }
}
