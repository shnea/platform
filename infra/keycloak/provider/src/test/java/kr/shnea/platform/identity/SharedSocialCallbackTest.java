package kr.shnea.platform.identity;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.common.util.Time;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.urls.UrlType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedSocialCallbackTest {
    private KeycloakSession session;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final Map<String, Cookie> cookies = new HashMap<>();
    private final MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();

    @BeforeEach void setup() {
        session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
        when(session.getContext().getUri(UrlType.FRONTEND).getBaseUri()).thenReturn(URI.create("https://platform.example/auth/"));
        when(session.getContext().getUri().getQueryParameters()).thenReturn(query);
        when(session.getContext().getRequestHeaders().getCookies()).thenReturn(cookies);
        var store = session.singleUseObjects();
        doAnswer(call -> { cache.put(call.getArgument(0), new HashMap<>(call.getArgument(2))); return null; })
            .when(store).put(anyString(), anyLong(), anyMap());
        when(store.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        when(store.remove(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)));
    }

    record Login(RealmModel realm, IdentityProviderModel config, String provider, String state, String cookieName) {}

    private Login start(String provider) {
        String environment = UUID.randomUUID().toString();
        RealmModel realm = mock(RealmModel.class);
        when(realm.getId()).thenReturn(environment);
        when(realm.getName()).thenReturn("p-" + environment.replace("-", ""));
        when(realm.isEnabled()).thenReturn(true);
        when(realm.getAttribute("platform.environmentId")).thenReturn(environment);
        when(session.realms().getRealm(environment)).thenReturn(realm);
        var config = new IdentityProviderModel();
        config.setEnabled(true);
        config.setAlias("platform-" + provider);
        config.setProviderId("platform-" + provider);
        config.setConfig(new HashMap<>(Map.of("platform.callbackMode", "shared-v1", "platform.environmentId", environment,
            "platform.environmentKind", "PROD", "platform.revision", UUID.randomUUID().toString(), "clientId", "client", "clientSecret", "secret")));
        when(session.identityProviders().getByAlias(config.getAlias())).thenReturn(config);
        AuthenticationRequest request = mock(AuthenticationRequest.class, RETURNS_DEEP_STUBS);
        String state = UUID.randomUUID().toString();
        when(request.getRealm()).thenReturn(realm);
        when(request.getState().getEncoded()).thenReturn(state);
        Response response = SharedSocialCallback.begin(session, config, provider, request,
            Response.seeOther(URI.create("https://provider.example/authorize?redirect_uri=https%3A%2F%2Fold.example&state=" + state + "&code_challenge=challenge&nonce=nonce")).build());
        assertTrue(response.getLocation().getQuery().contains("redirect_uri=https://platform.example/auth/social/" + provider + "/callback"));
        assertTrue(response.getLocation().getQuery().contains("state=" + state));
        assertTrue(response.getLocation().getQuery().contains("code_challenge=challenge&nonce=nonce"));
        var cookie = response.getCookies().values().iterator().next();
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("/auth/", cookie.getPath());
        assertEquals(300, cookie.getMaxAge());
        cookies.put(cookie.getName(), cookie);
        return new Login(realm, config, provider, state, cookie.getName());
    }

    private Response route(Login login, String code) {
        query.clear();
        query.putSingle("state", login.state());
        query.putSingle("code", code);
        when(session.identityProviders().getByAlias(login.config().getAlias())).thenReturn(login.config());
        return SharedSocialCallback.route(session, login.provider());
    }

    private SimpleHttpRequest token(Login login, String code) {
        when(session.getContext().getRealm()).thenReturn(login.realm());
        query.clear();
        query.putSingle("state", login.state());
        SimpleHttpRequest request = mock(SimpleHttpRequest.class);
        when(request.getParam("grant_type")).thenReturn("authorization_code");
        when(request.getParam("code")).thenReturn(code);
        when(request.param(anyString(), anyString())).thenReturn(request);
        return SharedSocialCallback.tokenRequest(session, login.config(), login.provider(), request);
    }

    @Test void fixedCallbackRoutesConcurrentRealmsAndBindsEachCodeExchange() {
        for (String provider : new String[]{"naver", "kakao", "google"}) {
            Login first = start(provider);
            Login second = start(provider);
            assertNotEquals(first.cookieName(), second.cookieName());
            Response a = route(first, "first-code");
            Response b = route(second, "second-code");
            assertEquals("/auth/realms/" + first.realm().getName() + "/broker/platform-" + provider + "/endpoint", a.getLocation().getPath());
            assertNotEquals(a.getLocation().getPath(), b.getLocation().getPath());
            assertEquals("no-store", a.getHeaderString("Cache-Control"));
            assertThrows(BadRequestException.class, () -> token(first, "second-code"));
            verify(token(first, "first-code")).param("redirect_uri", "https://platform.example/auth/social/" + provider + "/callback");
            verify(token(second, "second-code")).param("redirect_uri", "https://platform.example/auth/social/" + provider + "/callback");
            assertThrows(BadRequestException.class, () -> token(first, "first-code"));
            assertThrows(BadRequestException.class, () -> route(first, "first-code"));
        }
    }

    @Test void foreignBrowserProviderTamperingAndDuplicateStateDoNotConsumeLegitimateRequest() {
        Login login = start("naver");
        Cookie valid = cookies.remove(login.cookieName());
        assertThrows(BadRequestException.class, () -> route(login, "code"));
        cookies.put(login.cookieName(), new Cookie(login.cookieName(), "wrong-browser"));
        assertThrows(BadRequestException.class, () -> route(login, "code"));
        cookies.put(login.cookieName(), valid);
        assertThrows(BadRequestException.class, () -> SharedSocialCallback.route(session, "google"));
        query.add("state", "another-state");
        assertThrows(BadRequestException.class, () -> SharedSocialCallback.route(session, "naver"));
        route(login, "code");
        assertThrows(BadRequestException.class, () -> token(login, "altered-code"));
        token(login, "code");
    }

    @Test void expiryDisabledRealmAndChangedCredentialsAreRejected() {
        Login expired = start("naver");
        cache.values().forEach(notes -> notes.put("expires", Long.toString(Time.currentTime() - 1L)));
        assertThrows(BadRequestException.class, () -> route(expired, "code"));
        Login disabled = start("naver");
        when(disabled.realm().isEnabled()).thenReturn(false);
        assertThrows(BadRequestException.class, () -> route(disabled, "code"));
        Login changed = start("naver");
        changed.config().getConfig().put("platform.revision", "changed");
        assertThrows(BadRequestException.class, () -> route(changed, "code"));
    }

    @Test void directNativeCallbackCannotBypassRouterAndCancellationIsSingleUse() {
        Login login = start("naver");
        assertThrows(BadRequestException.class, () -> token(login, "code"));
        query.putSingle("error", "access_denied");
        query.putSingle("error_description", "untrusted text");
        query.putSingle("redirect_uri", "https://evil.example/");
        Response cancelled = SharedSocialCallback.route(session, "naver");
        assertEquals("platform.example", cancelled.getLocation().getHost());
        assertTrue(cancelled.getLocation().getQuery().contains("error=access_denied"));
        assertFalse(cancelled.getLocation().toString().contains("evil"));
        assertFalse(cancelled.getLocation().toString().contains("untrusted"));
        assertEquals(0, cancelled.getCookies().values().iterator().next().getMaxAge());
        assertThrows(BadRequestException.class, () -> SharedSocialCallback.route(session, "naver"));
    }
}
