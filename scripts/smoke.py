"""Network checks against the real Compose stack. No external calls."""
import json
import os
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

base = "http://nginx:8080"
for path in ("/healthz", "/api/projects/health", "/api/files/health", "/api/notifications/health"):
    expected_down = "--db-down" in sys.argv and path != "/healthz"
    try:
        with urlopen(base + path, timeout=10) as response:
            assert not expected_down and json.load(response)["status"] == "UP", path
    except HTTPError as error:
        assert expected_down and error.code == 503, (path, error.code)
    print("PASS", path)

if "--db-down" in sys.argv:
    print("PASS DB outage is reflected in API readiness")
    raise SystemExit(0)

for path in ("/actuator/env", "/api/projects/actuator/env", "/api/files/upload", "/unknown"):
    try:
        urlopen(base + path, timeout=10)
    except HTTPError as error:
        assert error.code == 404, (path, error.code)
    else:
        raise AssertionError("Unexpected public endpoint: " + path)
    print("PASS blocked", path)

request = Request(base + "/auth/realms/master/.well-known/openid-configuration",
                  headers={"X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "https"})
with urlopen(request, timeout=10) as response:
    discovery = json.load(response)
assert discovery["issuer"] == os.environ["KEYCLOAK_PUBLIC_URL"] + "/realms/master", discovery["issuer"]
assert "attacker.invalid" not in json.dumps(discovery)
print("PASS Keycloak discovery and fixed issuer")

for service in ("project-service", "file-service", "notification-service"):
    try:
        urlopen(f"http://{service}:8080/actuator/env", timeout=10)
    except HTTPError as error:
        assert error.code == 404
    else:
        raise AssertionError(service + " exposed actuator env")
print("PASS internal actuator exposure")
