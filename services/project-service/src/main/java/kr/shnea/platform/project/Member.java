package kr.shnea.platform.project;

import java.util.List;
import java.util.Map;

// Explicit response fields: never forward Keycloak credentials, attributes or tokens.
record Member(String id, String username, String email, String firstName, String lastName,
              boolean enabled, boolean emailVerified, Long createdTimestamp) {
    static Member from(Map<String, Object> data) {
        return new Member((String) data.get("id"), (String) data.get("username"), (String) data.get("email"),
            (String) data.get("firstName"), (String) data.get("lastName"), Boolean.TRUE.equals(data.get("enabled")),
            Boolean.TRUE.equals(data.get("emailVerified")),
            data.get("createdTimestamp") instanceof Number value ? value.longValue() : null);
    }
    record Page(List<Member> items, boolean hasMore) {}
    record Detail(Member user, List<String> providers) {}
    record Session(String id, String ipAddress, Long start, Long lastAccess, List<String> clients) {}
}
