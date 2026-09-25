package kr.shnea.platform.project;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class ProjectService {
    record Project(UUID id, String code, String name, Instant createdAt, String status, long revision) {}
    record Environment(UUID id, UUID projectId, String code, String kind, String realm,
                       boolean registrationAllowed, List<String> redirectUris, String state, String issuer, long revision) {}
    record Credential(UUID id, String apiKey, Instant expiresAt, List<String> scopes) {}
    // Keep the existing JDBC timestamp JSON format used by the credential list.
    record CredentialMetadata(UUID id, @JsonProperty("created_at") java.sql.Timestamp createdAt,
                              @JsonProperty("expires_at") java.sql.Timestamp expiresAt,
                              @JsonProperty("revoked_at") java.sql.Timestamp revokedAt, List<String> scopes) {}
    record Context(UUID projectId, UUID environmentId, String kind, String issuer, List<String> scopes) {}
    record Scope(String code, String label, String description) {}
    record MockResult(int httpStatus, java.util.Map<String, Object> result) {}
    private static final Scope READ = new Scope("integration:read", "연동 정보 조회", "프로젝트·환경과 로그인 주소를 조회합니다.");
    private static final Scope MOCK = new Scope("auth:mock", "개발용 가짜 로그인", "외부 소셜 인증 없이 테스트 사용자의 로그인 토큰을 발급합니다.");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate resetAuditTx;
    private final IdentityClient identity;
    private final String mode;
    private final JsonMapper json = new JsonMapper();
    private final SecureRandom random = new SecureRandom();

    ProjectService(JdbcTemplate db, TransactionTemplate tx, IdentityClient identity,
                   @Value("${platform.mode:prod}") String mode) {
        this.db = db;
        this.tx = tx;
        this.resetAuditTx = new TransactionTemplate(tx.getTransactionManager());
        this.resetAuditTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.identity = identity;
        this.mode = mode;
    }

    List<Project> projects(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw badRequest("Invalid pagination");
        return db.query("SELECT * FROM projects ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (rs, row) -> new Project(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getTimestamp("created_at").toInstant(), rs.getString("status"), rs.getLong("revision")), limit, offset);
    }

    Project createProject(String code, String name, String actor) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            Instant created = db.queryForObject(
                "INSERT INTO projects(id,code,name) VALUES (?,?,?) RETURNING created_at",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id, code, name.strip());
            audit(actor, "project.created", id);
            return new Project(id, code, name.strip(), created, "ACTIVE", 0);
        });
    }

    List<Environment> environments(UUID projectId) {
        requireProject(projectId);
        return db.query("SELECT * FROM environments WHERE project_id=? ORDER BY created_at,id",
            (rs, row) -> environment(rs), projectId);
    }

    Environment createEnvironment(UUID projectId, String code, String kind, boolean registration,
                                  List<String> redirects, String actor) {
        validateRedirects(kind, redirects);
        UUID id = tx.execute(status -> {
            requireActive(lockProject(projectId));
            UUID next = UUID.randomUUID();
            String realm = "p-" + next.toString().replace("-", "");
            db.update("""
                INSERT INTO environments(id,project_id,code,kind,realm,registration_allowed,redirect_uris,state)
                VALUES (?,?,?,?,?,?,?::jsonb,'PENDING')
                """, next, projectId, code, kind, realm, registration, json.writeValueAsString(redirects));
            audit(actor, "environment.created", next);
            return next;
        });
        // The durable row is committed before contacting Keycloak. A crash leaves a retryable PENDING row.
        return provision(id, actor);
    }

    Environment provision(UUID id, String actor) {
        return tx.execute(status -> {
            Project project = lockProject(findEnvironment(id).projectId());
            Environment env = findEnvironment(id);
            if (env.state().equals("READY")) return env;
            String state;
            try {
                identity.ensureRealm(env, project.status().equals("ACTIVE"));
                state = "READY";
            } catch (org.springframework.web.client.RestClientException | IllegalStateException error) {
                state = "FAILED"; // Never log provider response bodies or credentials.
            }
            db.update("UPDATE environments SET state=? WHERE id=?", state, id);
            audit(actor, "environment.provision." + state.toLowerCase(), id);
            return findEnvironment(id);
        });
    }

    List<Scope> credentialScopes(UUID environmentId) {
        Environment env = findEnvironment(environmentId);
        return mode.equals("dev") && env.kind().equals("DEV") ? List.of(READ, MOCK) : List.of(READ);
    }

    Credential issueCredential(UUID id, Instant expiresAt, List<String> requestedScopes, String actor) {
        return tx.execute(status -> {
            requireActive(lockProject(findEnvironment(id).projectId()));
            Environment env = findEnvironment(id);
            if (!env.state().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment not ready");
            List<String> scopes = requestedScopes == null ? List.of(READ.code()) : requestedScopes;
            List<String> allowed = credentialScopes(id).stream().map(Scope::code).toList();
            if (scopes.isEmpty() || scopes.size() > allowed.size() || scopes.stream().anyMatch(s -> s == null || !allowed.contains(s))
                    || new HashSet<>(scopes).size() != scopes.size()) throw badRequest("Select supported, distinct credential scopes");
            UUID keyId = UUID.randomUUID();
            byte[] entropy = new byte[32];
            random.nextBytes(entropy);
            String key = "pk_" + keyId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            if (expiresAt != null && !expiresAt.isAfter(Instant.now())) throw badRequest("Expiry must be in the future");
            db.update("INSERT INTO service_credentials(id,environment_id,secret_hash,expires_at,scopes) VALUES (?,?,?,?,?::jsonb)",
                keyId, id, hash(key), expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), json.writeValueAsString(scopes));
            audit(actor, "credential.issued", keyId);
            return new Credential(keyId, key, expiresAt, List.copyOf(scopes));
        });
    }

    void revokeCredential(UUID id, String actor) {
        tx.executeWithoutResult(status -> {
            if (db.update("UPDATE service_credentials SET revoked_at=coalesce(revoked_at,now()) WHERE id=?", id) == 0)
                throw notFound();
            audit(actor, "credential.revoked", id);
        });
    }

    Context context(String key, String requiredScope) {
        if (key == null || key.length() > 150 || !key.startsWith("pk_")) throw unauthorized();
        UUID keyId;
        try { keyId = UUID.fromString(key.split("_", 3)[1]); }
        catch (RuntimeException error) { throw unauthorized(); }
        var rows = db.query("""
            SELECT e.*, c.secret_hash, c.scopes FROM service_credentials c
            JOIN environments e ON c.environment_id=e.id
            JOIN projects p ON e.project_id=p.id
            WHERE c.id=? AND c.revoked_at IS NULL AND (c.expires_at IS NULL OR c.expires_at>now())
                AND e.state='READY' AND p.status='ACTIVE'
            """, (rs, row) -> {
                if (!MessageDigest.isEqual(hash(key).getBytes(StandardCharsets.US_ASCII),
                        rs.getString("secret_hash").getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
                return new Context(rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class),
                    rs.getString("kind"), identity.issuer(rs.getString("realm")), readScopes(rs));
            }, keyId);
        Context context = rows.stream().findFirst().orElseThrow(ProjectService::unauthorized);
        if (!context.scopes().contains(requiredScope))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key lacks required scope");
        return context;
    }

    List<java.util.Map<String, Object>> auditEvents(int limit) {
        if (limit < 1 || limit > 100) throw badRequest("Invalid limit");
        return db.queryForList("SELECT id,actor,action,target_id,environment_id,session_id,created_at FROM audit_events ORDER BY id DESC LIMIT ?", limit);
    }

    MockResult mockLogin(String key, String provider, String subject, String scenario) {
        Context context = context(key, MOCK.code());
        if (!context.kind().equals("DEV")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEV environment required");
        return tx.execute(status -> {
            // Project lock serializes lifecycle changes and mock password resets. Recheck key after waiting.
            lockProject(context.projectId());
            context(key, MOCK.code());
            Environment env = findEnvironment(context.environmentId());
            return runMock(env, provider, subject, scenario, "api-key:" + env.id());
        });
    }

    MockResult previewMockLogin(UUID id, String provider, String subject, String scenario, String actor) {
        return tx.execute(status -> {
            requireActive(lockProject(findEnvironment(id).projectId()));
            Environment env = findEnvironment(id);
            if (!env.state().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment not ready");
            return runMock(env, provider, subject, scenario, actor);
        });
    }

    MockReset.Preview mockResetPreview(UUID id) {
        return tx.execute(status -> identity.mockResetPreview(resetEnvironment(id)));
    }

    private Environment resetEnvironment(UUID id) {
        requireActive(lockProject(findEnvironment(id).projectId()));
        Environment env = findEnvironment(id);
        requireReady(env);
        if (!mode.equals("dev") || !env.kind().equals("DEV"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEV environment required");
        return env;
    }

    MockReset.Result resetMockUsers(UUID id, MockController.Reset request, String actor) {
        return tx.execute(status -> {
            Environment env = resetEnvironment(id);
            var preview = identity.mockResetPreview(env);
            var ids = preview.items().stream().map(MockReset.Target::id).toList();
            if (ids.isEmpty() || !preview.revision().equals(request.revision())
                    || request.userIds().size() != ids.size() || !new HashSet<>(request.userIds()).equals(new HashSet<>(ids)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset targets changed; reload preview");
            var results = new java.util.ArrayList<MockReset.Item>();
            for (var target : preview.items()) {
                // Commit intent before the external operation; a process crash leaves a trace.
                resetAudit(actor, "mock.user.reset.started", target.id(), id);
                boolean deleted = false;
                try {
                    identity.deleteMockUser(env, target.id());
                    deleted = true;
                } catch (org.springframework.web.client.RestClientException | ResponseStatusException error) {
                    // Never return provider response bodies or re-enable partially processed users.
                }
                resetAudit(actor, deleted ? "mock.user.deleted" : "mock.user.reset.failed", target.id(), id);
                results.add(new MockReset.Item(target.id(), target.username(), deleted ? "DELETED" : "FAILED"));
            }
            int deleted = (int) results.stream().filter(item -> item.status().equals("DELETED")).count();
            resetAudit(actor, deleted == results.size() ? "mock.reset.completed" : "mock.reset.partial", id, id);
            return new MockReset.Result(results.size(), deleted, results.size() - deleted, List.copyOf(results));
        });
    }

    private void resetAudit(String actor, String action, UUID target, UUID environment) {
        resetAuditTx.executeWithoutResult(status -> db.update(
            "INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES (?,?,?,?)", actor, action, target, environment));
    }

    private MockResult runMock(Environment env, String provider, String subject, String requestedScenario, String actor) {
        if (!mode.equals("dev") || !env.kind().equals("DEV"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEV environment required");
        String scenario = requestedScenario == null ? "success" : requestedScenario;
        if (scenario.equals("success")) {
            var result = new java.util.LinkedHashMap<>(identity.mockLogin(env, provider, subject));
            result.put("scenario", scenario);
            audit(actor, "mock.login", env.id());
            return new MockResult(200, result);
        }
        int code = switch (scenario) {
            case "cancelled", "access_denied" -> 403;
            case "provider_unavailable" -> 503;
            default -> throw badRequest("Unknown mock scenario");
        };
        // Failed simulations never create/reset users or issue tokens, even when Keycloak is unavailable.
        audit(actor, "mock.login." + scenario, env.id());
        return new MockResult(code, java.util.Map.of("mode", "mock", "scenario", scenario, "error", scenario,
            "provider", provider, "projectId", env.projectId(), "environmentId", env.id()));
    }

    Project updateProject(UUID id, String name, String desiredStatus, long revision, String actor) {
        if (!List.of("ACTIVE", "SUSPENDED").contains(desiredStatus)) throw badRequest("Invalid project status");
        tx.executeWithoutResult(transaction -> {
            Project current = lockProject(id);
            checkRevision(current.revision(), revision);
            db.update("UPDATE projects SET name=?,status=?,revision=revision+1 WHERE id=?", name.strip(), desiredStatus, id);
            if (!current.status().equals(desiredStatus))
                db.update("UPDATE environments SET state='PENDING',revision=revision+1 WHERE project_id=?", id);
            audit(actor, "project.updated." + desiredStatus.toLowerCase(), id);
        });
        // Desired state survives an outage/crash. Each realm can be retried independently.
        for (Environment env : environments(id)) if (!env.state().equals("READY")) provision(env.id(), actor);
        return project(id, false);
    }

    Environment updateEnvironment(UUID id, boolean registration, List<String> redirects, long revision, String actor) {
        tx.executeWithoutResult(transaction -> {
            lockProject(findEnvironment(id).projectId());
            Environment env = findEnvironment(id);
            checkRevision(env.revision(), revision);
            validateRedirects(env.kind(), redirects);
            db.update("UPDATE environments SET registration_allowed=?,redirect_uris=?::jsonb,state='PENDING',revision=revision+1 WHERE id=?",
                registration, json.writeValueAsString(redirects), id);
            audit(actor, "environment.updated", id);
        });
        return provision(id, actor);
    }

    List<CredentialMetadata> credentials(UUID id) {
        findEnvironment(id);
        return db.query("SELECT id,created_at,expires_at,revoked_at,scopes FROM service_credentials WHERE environment_id=? ORDER BY created_at DESC,id LIMIT 100",
            (rs, row) -> new CredentialMetadata(rs.getObject("id", UUID.class), rs.getTimestamp("created_at"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("revoked_at"), readScopes(rs)), id);
    }

    List<SocialProvider.Metadata> socialProviders(UUID id) {
        Environment env = findEnvironment(id);
        requireReady(env);
        return identity.socialProviders(env);
    }

    AuthenticationPolicy authenticationPolicy(UUID id) {
        Environment env = findEnvironment(id);
        requireReady(env);
        return identity.authenticationPolicy(env);
    }

    java.util.Map<String, Object> emailContext(UUID id) {
        Environment env = findEnvironment(id);
        Project project = project(env.projectId(), false);
        return java.util.Map.of("realm", env.realm(), "kind", env.kind(), "mode", mode,
            "active", project.status().equals("ACTIVE") && env.state().equals("READY"));
    }

    AuthenticationPolicy updateAuthenticationPolicy(UUID id, ProjectController.AuthenticationSettings request, String actor) {
        return tx.execute(transaction -> {
            Project project = lockProject(findEnvironment(id).projectId());
            Environment env = findEnvironment(id);
            requireReady(env);
            if (request.verifyEmail() || request.resetPasswordAllowed()) requireActive(project);
            var result = identity.updateAuthenticationPolicy(env, request);
            audit(actor, "authentication.policy.updated", id);
            return result;
        });
    }

    SocialProvider.Metadata updateSocialProvider(UUID id, String code, ProjectController.SocialSettings request, String actor) {
        SocialProvider provider = SocialProvider.find(code);
        return tx.execute(transaction -> {
            lockProject(findEnvironment(id).projectId());
            Environment env = findEnvironment(id);
            requireReady(env);
            var result = identity.updateSocialProvider(env, provider, request);
            audit(actor, "social.updated." + provider.code(), id);
            return result;
        });
    }

    private static void requireReady(Environment env) {
        if (!env.state().equals("READY"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment not ready");
    }

    Member.Page members(UUID id, String search, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000 || search.length() > 200
                || search.chars().anyMatch(Character::isISOControl)) throw badRequest("Invalid user search or pagination");
        Environment env = findEnvironment(id);
        requireReady(env);
        return identity.members(env, search.strip(), limit, offset);
    }

    Member.Detail member(UUID id, UUID userId) {
        Environment env = findEnvironment(id);
        requireReady(env);
        return identity.member(env, userId);
    }

    List<Member.Session> memberSessions(UUID id, UUID userId) {
        Environment env = findEnvironment(id);
        requireReady(env);
        return identity.memberSessions(env, userId);
    }

    void updateMember(UUID id, UUID userId, ProjectController.MemberState request, String actor) {
        memberAction(id, userId, null, actor, request.enabled() ? "user.enabled" : "user.disabled", env ->
            identity.updateMember(env, userId, request.enabled(), request.expectedEnabled()), request.enabled());
    }

    void endMemberSessions(UUID id, UUID userId, String sessionId, String actor) {
        if (sessionId != null && !sessionId.matches("[A-Za-z0-9_-]{1,128}")) throw badRequest("Invalid session ID");
        memberAction(id, userId, sessionId, actor, sessionId == null ? "user.sessions.ended" : "user.session.ended",
            env -> identity.endMemberSessions(env, userId, sessionId), false);
    }

    private void memberAction(UUID id, UUID userId, String sessionId, String actor, String action,
                              java.util.function.Consumer<Environment> operation, boolean requireActiveProject) {
        RuntimeException failure = tx.execute(transaction -> {
            Project project = lockProject(findEnvironment(id).projectId());
            Environment env = findEnvironment(id);
            requireReady(env);
            if (requireActiveProject) requireActive(project);
            RuntimeException error = null;
            try { operation.accept(env); }
            catch (org.springframework.web.client.RestClientException | ResponseStatusException e) { error = e; }
            // Keycloak changes cannot roll back with our DB. Persist failed/partial attempts, too.
            db.update("INSERT INTO audit_events(actor,action,target_id,environment_id,session_id) VALUES (?,?,?,?,?)",
                actor, action + (error == null ? "" : ".failed"), userId, id, sessionId);
            return error;
        });
        if (failure != null) throw failure;
    }

    @SuppressWarnings("unchecked")
    private List<String> readScopes(ResultSet rs) throws SQLException {
        return json.readValue(rs.getString("scopes"), List.class);
    }

    private Project lockProject(UUID id) { return project(id, true); }
    private Project project(UUID id, boolean lock) {
        return db.query("SELECT * FROM projects WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Project(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("status"), rs.getLong("revision")), id)
            .stream().findFirst().orElseThrow(ProjectService::notFound);
    }
    private static void requireActive(Project project) {
        if (!project.status().equals("ACTIVE")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Project suspended");
    }
    private static void checkRevision(long current, long requested) {
        if (current != requested) throw new ResponseStatusException(HttpStatus.CONFLICT, "Settings changed; reload before saving");
    }

    static void validateRedirects(String kind, List<String> redirects) {
        if (!List.of("DEV", "PROD").contains(kind) || redirects == null || redirects.isEmpty() || redirects.size() > 10)
            throw badRequest("Invalid environment or redirect URI list");
        for (String redirect : redirects) {
            try {
                URI uri = URI.create(redirect);
                String host = uri.getHost();
                boolean loopback = List.of("localhost", "127.0.0.1", "[::1]").contains(host == null ? "" : host);
                boolean allowed = "https".equals(uri.getScheme())
                    || ("DEV".equals(kind) && "http".equals(uri.getScheme()) && loopback);
                if (redirect.length() > 2048 || redirect.contains("*") || !allowed || host == null
                        || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                    throw badRequest("Use an exact HTTPS callback; DEV also allows HTTP loopback");
            } catch (IllegalArgumentException error) { throw badRequest("Invalid redirect URI"); }
        }
    }

    private Environment findEnvironment(UUID id) {
        return db.query("SELECT * FROM environments WHERE id=?", (rs, row) -> environment(rs), id)
            .stream().findFirst().orElseThrow(ProjectService::notFound);
    }

    @SuppressWarnings("unchecked")
    private Environment environment(ResultSet rs) throws SQLException {
        return new Environment(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getString("code"), rs.getString("kind"), rs.getString("realm"), rs.getBoolean("registration_allowed"),
            json.readValue(rs.getString("redirect_uris"), List.class), rs.getString("state"),
            identity.issuer(rs.getString("realm")), rs.getLong("revision"));
    }

    private void requireProject(UUID id) {
        if (db.queryForObject("SELECT count(*) FROM projects WHERE id=?", Integer.class, id) != 1) throw notFound();
    }
    private void audit(String actor, String action, UUID target) {
        db.update("INSERT INTO audit_events(actor,action,target_id) VALUES (?,?,?)", actor, action, target);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
