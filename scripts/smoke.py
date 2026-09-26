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

with urlopen(base + "/", timeout=10) as response:
    assert "text/html" in response.headers["Content-Type"]
    assert response.headers["X-Frame-Options"] == "DENY"
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["Referrer-Policy"] == "no-referrer"
    assert response.headers["Cache-Control"] == "no-store"
print("PASS admin HTML and security/cache headers")

for suffix in ("", "react/", "vue/", "vanilla/"):
    with urlopen(base + "/examples/editor/" + suffix, timeout=10) as response:
        assert "text/html" in response.headers["Content-Type"]
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        assert response.headers["X-Frame-Options"] == "DENY"
        assert "connect-src 'none'" in response.headers["Content-Security-Policy"]
        assert 'lang="ko"' in response.read().decode("utf-8")
print("PASS public editor examples and isolated network policy")

for suffix, expected in (("browser/editor.js", "javascript"), ("browser/editor.css", "text/css"), ("INTEGRATION.md", "@shnea/editor/react")):
    with urlopen(base + "/examples/editor/" + suffix, timeout=10) as response:
        body = response.read().decode("utf-8")
        assert expected in (body if suffix.endswith(".md") else response.headers["Content-Type"])
print("PASS standalone editor assets and integration guide")

for path in ("/actuator/env", "/api/projects/actuator/env", "/api/files/upload", "/unknown", "/internal/v1/monitoring", "/examples/editor/jsp/index.jsp", "/examples/editor/missing.js"):
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
        assert error.code == {"project-service": 401, "notification-service": 403, "file-service": 401}[service]
    else:
        raise AssertionError(service + " exposed actuator env")
print("PASS internal actuator exposure")
