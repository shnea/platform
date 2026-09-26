"""Real project API / Keycloak checks. Creates explicitly named development test data."""
import base64
import json
import os
import secrets
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

base = "http://nginx:8080"
identity = "http://keycloak:8080/auth"

def request(method, url, data=None, token=None, key=None, expected=200, form=False):
    headers = {}
    if token: headers["Authorization"] = "Bearer " + token
    if key: headers["X-Platform-Key"] = key
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(data) if form else json.dumps(data)).encode()
    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=45) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (method, url, status, expected)  # Never print token-bearing bodies.
    return json.loads(body) if body else None

def token(realm, **fields):
    return request("POST", identity + f"/realms/{realm}/protocol/openid-connect/token", fields, form=True)["access_token"]

admin = token("platform-admin-dev", grant_type="password", client_id="platform-admin-cli",
              username="admin", password=os.environ["PLATFORM_ADMIN_PASSWORD"])
api = base + "/api/v1/admin"
request("GET", api + "/projects", expected=401)
request("GET", api + "/projects", token=admin[:-8] + "abcdefgh", expected=401)
run = "check-" + uuid.uuid4().hex[:12]
projects = []
environments = []
for index in range(2):
    body = {"code": f"{run}-{index}", "name": "격리 검증 " + str(index)}
    project = request("POST", api + "/projects", body, admin, expected=201)
    projects.append(project)
    request("POST", api + "/projects", body, admin, expected=409)
    endpoint = api + "/projects/" + project["id"] + "/environments"
    request("POST", endpoint, {"code": "bad", "kind": "PROD", "registrationAllowed": False, "redirectUris": ["http://localhost/cb"]}, admin, expected=400)
    env = request("POST", endpoint, {"code": "dev", "kind": "DEV", "registrationAllowed": index == 0,
                  "redirectUris": ["http://localhost:3000/callback"]}, admin, expected=201)
    assert env["state"] == "READY", (env["id"], env["state"])
    environments.append(env)
    retried = request("POST", api + "/environments/" + env["id"] + "/provision", token=admin)
    assert retried == env
    credential = request("POST", api + "/environments/" + env["id"] + "/credentials", token=admin, expected=201)
    context = request("GET", base + "/api/v1/integration/context", key=credential["apiKey"])
    assert context["projectId"] == project["id"] and context["environmentId"] == env["id"]
    request("GET", api + "/projects", key=credential["apiKey"], expected=401)
    request("GET", base + "/api/v1/integration/context", key=credential["apiKey"] + "x", expected=401)
    request("DELETE", api + "/credentials/" + credential["id"], token=admin, expected=204)
    request("GET", base + "/api/v1/integration/context", key=credential["apiKey"], expected=401)
print("PASS authenticated CRUD, callback validation, provisioning retry, scoped keys and revocation")

provisioner = token("master", grant_type="client_credentials", client_id="platform-provisioner-dev",
                    client_secret=os.environ["KEYCLOAK_PROVISIONER_SECRET"])
request("GET", identity + "/admin/realms/platform-admin-dev/users", token=provisioner, expected=403)
passwords = [secrets.token_urlsafe(24), secrets.token_urlsafe(24)]
user_tokens = []
for index, env in enumerate(environments):
    realm_api = identity + "/admin/realms/" + env["realm"]
    realm = request("GET", realm_api, token=provisioner)
    assert realm["registrationAllowed"] == (index == 0)
    clients = request("GET", realm_api + "/clients?clientId=app", token=provisioner)
    assert clients[0]["attributes"]["pkce.code.challenge.method"] == "S256"
    assert not clients[0]["directAccessGrantsEnabled"]
    # A temporary test client avoids weakening the application client's login flow.
    request("POST", realm_api + "/clients", {"clientId": "isolation-test", "publicClient": True,
            "standardFlowEnabled": False, "directAccessGrantsEnabled": True}, provisioner, expected=201)
    request("POST", realm_api + "/users", {"username": "same-user", "enabled": True,
            "firstName": "Test", "lastName": "User", "email": "test@example.test", "emailVerified": True,
            "credentials": [{"type": "password", "value": passwords[index], "temporary": False}]}, provisioner, expected=201)
    user = token(env["realm"], grant_type="password", client_id="isolation-test", username="same-user", password=passwords[index])
    user_tokens.append(json.loads(base64.urlsafe_b64decode(user.split(".")[1] + "==")))
    request("GET", api + "/projects", token=user, expected=401)
    request("POST", identity + f"/realms/{env['realm']}/protocol/openid-connect/token",
            {"grant_type": "password", "client_id": "isolation-test", "username": "same-user", "password": passwords[1-index]}, expected=400, form=True)
    clients = request("GET", realm_api + "/clients?clientId=isolation-test", token=provisioner)
    request("DELETE", realm_api + "/clients/" + clients[0]["id"], token=provisioner, expected=204)
assert user_tokens[0]["iss"] != user_tokens[1]["iss"]
assert user_tokens[0]["sub"] != user_tokens[1]["sub"]
audit = request("GET", api + "/audit-events", token=admin)
assert any(event["action"] == "credential.revoked" for event in audit)
print("PASS isolated same-name users, foreign token rejection, restricted provisioner and audit log")

def claims(value):
    return json.loads(base64.urlsafe_b64decode(value.split(".")[1] + "=="))

mock_subjects = []
for env in environments:
    credential = request("POST", api + "/environments/" + env["id"] + "/credentials", {"scopes": ["auth:mock"]}, token=admin, expected=201)
    for provider in ("kakao", "naver", "google"):
        result = request("POST", base + "/api/v1/dev/login", {"provider": provider, "subject": "test-user"}, key=credential["apiKey"])
        assert result["mode"] == "mock" and result["environmentId"] == env["id"]
        assert claims(result["accessToken"])["iss"] == env["issuer"]
        request("GET", api + "/projects", token=result["accessToken"], expected=401)
        if provider == "kakao": mock_subjects.append(claims(result["accessToken"])["sub"])
    repeated = request("POST", base + "/api/v1/dev/login", {"provider": "kakao", "subject": "test-user"}, key=credential["apiKey"])
    assert claims(repeated["accessToken"])["sub"] == mock_subjects[-1]
    request("DELETE", api + "/credentials/" + credential["id"], token=admin, expected=204)
    request("POST", base + "/api/v1/dev/login", {"provider": "kakao", "subject": "test-user"}, key=credential["apiKey"], expected=401)
assert mock_subjects[0] != mock_subjects[1]
production = request("POST", api + "/projects/" + projects[0]["id"] + "/environments",
    {"code": "prod", "kind": "PROD", "registrationAllowed": False, "redirectUris": ["https://example.test/callback"]}, admin, expected=201)
assert production["state"] == "READY"
prod_url = api + "/environments/" + production["id"]
assert [scope['code'] for scope in request('GET', prod_url + '/credential-scopes', token=admin)] == ['integration:read', 'files:read', 'files:write', 'files:delete', 'files:share']
request('POST', prod_url + '/credentials', {'scopes': ['auth:mock']}, token=admin, expected=400)
credential = request("POST", api + "/environments/" + production["id"] + "/credentials", token=admin, expected=201)
request("POST", base + "/api/v1/dev/login", {"provider": "google", "subject": "test-user"}, key=credential["apiKey"], expected=403)
request("DELETE", api + "/credentials/" + credential["id"], token=admin, expected=204)
print("PASS three mock providers, stable isolated subjects, revoked keys and PROD environment denial")
print("Development test projects retained for inspection:", ", ".join(p["code"] for p in projects))
