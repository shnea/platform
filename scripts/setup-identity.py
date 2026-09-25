"""Explicit bootstrap; master credentials never enter an application container."""
import json
import os
import uuid
from urllib.request import Request, urlopen
from urllib.parse import urlencode, urlsplit
from urllib.error import HTTPError

base = "http://keycloak:8080/auth"
mode = os.environ["PLATFORM_MODE"]
assert mode in ("dev", "prod"), "PLATFORM_MODE must be dev or prod"
web_url = os.environ.get("PLATFORM_WEB_URL", "http://localhost:30140").rstrip("/")
assert "*" not in web_url and not any(char.isspace() for char in web_url), "Admin URL must be exact and contain no whitespace"
parsed = urlsplit(web_url)
_ = parsed.port  # Reject malformed or out-of-range ports before any bootstrap writes.
assert parsed.hostname and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment and not parsed.path, "PLATFORM_WEB_URL must be an origin"
assert parsed.scheme == "https" or (mode == "dev" and parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1")), "Admin URL requires HTTPS; DEV permits HTTP loopback"
form = urlencode({"grant_type": "password", "client_id": "admin-cli",
                  "username": os.environ["KEYCLOAK_ADMIN"],
                  "password": os.environ["KEYCLOAK_ADMIN_PASSWORD"]}).encode()
with urlopen(Request(base + "/realms/master/protocol/openid-connect/token", data=form), timeout=15) as response:
    token = json.load(response)["access_token"]

def api(method, path, body=None, allowed=(200, 201, 204)):
    data = None if body is None else json.dumps(body).encode()
    request = Request(base + "/admin/realms" + path, data=data, method=method,
                      headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=20) as response:
            content = response.read()
            return json.loads(content) if content else None
    except HTTPError as error:
        if error.code in allowed:
            return None
        # Do not expose request data or tokens in error output.
        raise SystemExit(f"Identity setup failed: {method} {path}: HTTP {error.code}")

realm = "platform-admin-" + mode
if api("GET", "/" + realm, allowed=(404,)) is None:
    api("POST", "", {"realm": realm, "enabled": True, "registrationAllowed": False,
        "sslRequired": "external", "bruteForceProtected": True, "accessTokenLifespan": 300,
        "roles": {"realm": [{"name": "platform-admin"}]},
        "users": [{"username": "admin", "enabled": True, "realmRoles": ["platform-admin"],
                   "firstName": "Platform", "lastName": "Admin", "email": "platform-admin@example.invalid", "emailVerified": True,
                   "credentials": [{"type": "password", "value": os.environ["PLATFORM_ADMIN_PASSWORD"], "temporary": False}]}]})

# Only platform-owned realms; preserve users, credentials, status and other settings.
korean = {"internationalizationEnabled": True, "supportedLocales": ["ko"], "defaultLocale": "ko"}
localized = 0
for candidate in api("GET", ""):
    name = candidate["realm"]
    owned = name in ("platform-admin-dev", "platform-admin-prod")
    if name.startswith("p-"):
        details = api("GET", "/" + name)
        environment_id = details.get("attributes", {}).get("platform.environmentId", "")
        try:
            owned = name == "p-" + uuid.UUID(environment_id).hex
        except (ValueError, TypeError, AttributeError):
            owned = False
    if owned:
        api("PUT", "/" + name, korean)
        localized += 1
print("PASS Korean locale configured for platform realms:", localized)

users = api("GET", f"/{realm}/users?username=admin&exact=true")
if users and not users[0].get("firstName"):
    api("PUT", f"/{realm}/users/" + users[0]["id"], {
        "firstName": "Platform", "lastName": "Admin", "email": "platform-admin@example.invalid", "emailVerified": True})

# This command may be repeated without resetting users or their passwords.
client = {"clientId": "platform-admin-cli", "protocol": "openid-connect", "publicClient": True,
          "standardFlowEnabled": False, "directAccessGrantsEnabled": mode == "dev",
          "protocolMappers": [{"name": "platform-api-audience", "protocol": "openid-connect",
              "protocolMapper": "oidc-audience-mapper", "config": {
                  "included.custom.audience": "platform-admin-api", "access.token.claim": "true",
                  "id.token.claim": "false"}}]}
clients = api("GET", f"/{realm}/clients?clientId=platform-admin-cli")
api("PUT" if clients else "POST", f"/{realm}/clients" + ("/" + clients[0]["id"] if clients else ""), client)

browser = {"clientId": "platform-admin-web", "protocol": "openid-connect", "publicClient": True,
           "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
           "redirectUris": [web_url + "/"], "webOrigins": [web_url],
           "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": web_url + "/"},
           "protocolMappers": client["protocolMappers"]}
clients = api("GET", f"/{realm}/clients?clientId=platform-admin-web")
api("PUT" if clients else "POST", f"/{realm}/clients" + ("/" + clients[0]["id"] if clients else ""), browser)

provisioner_id = "platform-provisioner-" + mode
clients = api("GET", "/master/clients?clientId=" + provisioner_id)
if not clients:
    api("POST", "/master/clients", {"clientId": provisioner_id, "protocol": "openid-connect",
        "publicClient": False, "serviceAccountsEnabled": True, "standardFlowEnabled": False,
        "directAccessGrantsEnabled": False, "secret": os.environ["KEYCLOAK_PROVISIONER_SECRET"]})
    clients = api("GET", "/master/clients?clientId=" + provisioner_id)
account = api("GET", "/master/clients/" + clients[0]["id"] + "/service-account-user")
role = api("GET", "/master/roles/create-realm")
api("POST", "/master/users/" + account["id"] + "/role-mappings/realm", [role])
print("PASS identity bootstrap:", realm, "and restricted realm provisioner")
