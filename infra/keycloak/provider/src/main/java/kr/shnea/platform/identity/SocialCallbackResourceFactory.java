package kr.shnea.platform.identity;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.Map;
import jakarta.ws.rs.core.Response;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public final class SocialCallbackResourceFactory implements RealmResourceProviderFactory {
    @Override public String getId() { return "platform-social"; }
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            @Override public Object getResource() {
                // The existing master realm only hosts this resource. No shared users/SSO are created.
                return "master".equals(session.getContext().getRealm().getName()) ? new Resource(session) : null;
            }
            @Override public void close() {}
        };
    }
    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}

    public static final class Resource {
        private final KeycloakSession session;
        Resource(KeycloakSession session) { this.session = session; }
        @GET @Path("{provider}/callback")
        public Response callback(@PathParam("provider") String provider) {
            return SharedSocialCallback.route(session, provider);
        }
        @GET @Path("configuration") @Produces("application/json")
        public Map<String, Boolean> configuration() { return SocialCredentials.readiness(); }
    }
}
