"""A wrong-secret probe must persist FAILED and recover through the healthy service."""
import json
import os
import time
import uuid
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from urllib.error import URLError, HTTPError

probe = "http://shnea-platform-dev-failure-check:8080"
for attempt in range(30):
    try:
        with urlopen(probe + "/actuator/health/readiness", timeout=2) as response:
            if response.status == 200: break
    except (URLError, TimeoutError):
        pass
    time.sleep(1)
else:
    raise AssertionError("Failure probe not ready")
form = urlencode({"grant_type": "password", "client_id": "platform-admin-cli", "username": "admin",
                  "password": os.environ["PLATFORM_ADMIN_PASSWORD"]}).encode()
with urlopen(Request("http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect/token", data=form), timeout=10) as response:
    token = json.load(response)["access_token"]

def call(base, path, body, method="POST"):
    data = json.dumps(body).encode()
    with urlopen(Request(base + "/api/v1/admin" + path, data=data, method=method,
                         headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}), timeout=30) as response:
        return json.load(response)

project = call(probe, "/projects", {"code": "recovery-" + uuid.uuid4().hex[:12], "name": "실패 복구 검사"})
failed = call(probe, "/projects/" + project["id"] + "/environments",
              {"code": "dev", "kind": "DEV", "registrationAllowed": False, "redirectUris": ["http://localhost:3000/callback"]})
assert failed["state"] == "FAILED"
recovered = call("http://nginx:8080", "/environments/" + failed["id"] + "/provision", {})
assert recovered["state"] == "READY"
assert recovered["id"] == failed["id"] and recovered["realm"] == failed["realm"]
print("PASS failed realm provisioning persisted and recovered with the same environment and realm")

healthy = "http://nginx:8080"
key = call(healthy, "/environments/" + recovered["id"] + "/credentials", {})["apiKey"]
project = call(probe, "/projects/" + project["id"], {"name": project["name"], "status": "SUSPENDED", "revision": project["revision"]}, "PUT")
assert project["status"] == "SUSPENDED"
try:
    urlopen(Request(healthy + "/api/v1/integration/context", headers={"X-Platform-Key": key}), timeout=10)
except HTTPError as error:
    assert error.code == 401
else:
    raise AssertionError("Failed Keycloak synchronization must still block the project API key")
with urlopen(Request(healthy + "/api/v1/admin/projects/" + project["id"] + "/environments", headers={"Authorization": "Bearer " + token}), timeout=10) as response:
    assert json.load(response)[0]["state"] == "FAILED"
assert call(healthy, "/environments/" + recovered["id"] + "/provision", {})["state"] == "READY"
# Failed resume keeps the environment closed until its latest desired settings are synchronized.
project = call(probe, "/projects/" + project["id"], {"name": project["name"], "status": "ACTIVE", "revision": project["revision"]}, "PUT")
try:
    urlopen(Request(healthy + "/api/v1/integration/context", headers={"X-Platform-Key": key}), timeout=10)
except HTTPError as error:
    assert error.code == 401
else:
    raise AssertionError("Failed resume must not unlock credentials")
assert call(healthy, "/environments/" + recovered["id"] + "/provision", {})["state"] == "READY"
with urlopen(Request(healthy + "/api/v1/integration/context", headers={"X-Platform-Key": key}), timeout=10) as response:
    assert response.status == 200
print("PASS failed suspend/resume persists desired state, blocks keys and recovers without replacing credentials")
