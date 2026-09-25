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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class ProjectService {
    record Project(UUID id, String code, String name, Instant createdAt) {}
    record Environment(UUID id, UUID projectId, String code, String kind, String realm,
                       boolean registrationAllowed, List<String> redirectUris, String state, String issuer) {}
    record Credential(UUID id, String apiKey, Instant expiresAt) {}
    record Context(UUID projectId, UUID environmentId, String kind, String issuer) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityClient identity;
    private final JsonMapper json = new JsonMapper();
    private final SecureRandom random = new SecureRandom();

    ProjectService(JdbcTemplate db, TransactionTemplate tx, IdentityClient identity) {
        this.db = db;
        this.tx = tx;
        this.identity = identity;
    }

    List<Project> projects(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw badRequest("Invalid pagination");
        return db.query("SELECT * FROM projects ORDER BY created_at,id LIMIT ? OFFSET ?",
            (rs, row) -> new Project(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getTimestamp("created_at").toInstant()), limit, offset);
    }

    Project createProject(String code, String name, String actor) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            Instant created = db.queryForObject(
                "INSERT INTO projects(id,code,name) VALUES (?,?,?) RETURNING created_at",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id, code, name.strip());
            audit(actor, "project.created", id);
            return new Project(id, code, name.strip(), created);
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
            requireProject(projectId);
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
            Environment env = db.query("SELECT * FROM environments WHERE id=? FOR UPDATE",
                (rs, row) -> environment(rs), id).stream().findFirst().orElseThrow(ProjectService::notFound);
            if (env.state().equals("READY")) return env;
            String state;
            try {
                identity.ensureRealm(env);
                state = "READY";
            } catch (org.springframework.web.client.RestClientException | IllegalStateException error) {
                state = "FAILED"; // Never log provider response bodies or credentials.
            }
            db.update("UPDATE environments SET state=? WHERE id=?", state, id);
            audit(actor, "environment.provision." + state.toLowerCase(), id);
            return findEnvironment(id);
        });
    }

    Credential issueCredential(UUID id, String actor) {
        return tx.execute(status -> {
            Environment env = findEnvironment(id);
            if (!env.state().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment not ready");
            UUID keyId = UUID.randomUUID();
            byte[] entropy = new byte[32];
            random.nextBytes(entropy);
            String key = "pk_" + keyId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            Instant expiry = Instant.now().plusSeconds(90L * 24 * 3600);
            db.update("INSERT INTO service_credentials(id,environment_id,secret_hash,expires_at) VALUES (?,?,?,?)",
                keyId, id, hash(key), java.sql.Timestamp.from(expiry));
            audit(actor, "credential.issued", keyId);
            return new Credential(keyId, key, expiry);
        });
    }

    void revokeCredential(UUID id, String actor) {
        tx.executeWithoutResult(status -> {
            if (db.update("UPDATE service_credentials SET revoked_at=coalesce(revoked_at,now()) WHERE id=?", id) == 0)
                throw notFound();
            audit(actor, "credential.revoked", id);
        });
    }

    Context context(String key) {
        if (key == null || key.length() > 150 || !key.startsWith("pk_")) throw unauthorized();
        UUID keyId;
        try { keyId = UUID.fromString(key.split("_", 3)[1]); }
        catch (RuntimeException error) { throw unauthorized(); }
        var rows = db.query("""
            SELECT e.*, c.secret_hash FROM service_credentials c
            JOIN environments e ON c.environment_id=e.id
            WHERE c.id=? AND c.revoked_at IS NULL AND c.expires_at>now() AND e.state='READY'
            """, (rs, row) -> {
                if (!MessageDigest.isEqual(hash(key).getBytes(StandardCharsets.US_ASCII),
                        rs.getString("secret_hash").getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
                return new Context(rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class),
                    rs.getString("kind"), identity.issuer(rs.getString("realm")));
            }, keyId);
        return rows.stream().findFirst().orElseThrow(ProjectService::unauthorized);
    }

    List<java.util.Map<String, Object>> auditEvents(int limit) {
        if (limit < 1 || limit > 100) throw badRequest("Invalid limit");
        return db.queryForList("SELECT id,actor,action,target_id,created_at FROM audit_events ORDER BY id DESC LIMIT ?", limit);
    }

    java.util.Map<String, Object> mockLogin(String key, String provider, String subject) {
        Context context = context(key);
        if (!context.kind().equals("DEV")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEV environment required");
        return tx.execute(status -> {
            // Serialize a realm's mock login/password resets; no shared password is persisted by this service.
            Environment env = db.query("SELECT * FROM environments WHERE id=? FOR UPDATE",
                (rs, row) -> environment(rs), context.environmentId()).getFirst();
            var result = identity.mockLogin(env, provider, subject);
            audit("api-key:" + env.id(), "mock.login", env.id());
            return result;
        });
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
            identity.issuer(rs.getString("realm")));
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
