"""Negative checks against a temporary production-mode application container."""
import json
import os
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

base = "http://shnea-platform-dev-prod-auth-check:8080"
for attempt in range(30):
    try:
        with urlopen(base + "/actuator/health/readiness", timeout=2) as response:
            if response.status == 200:
                break
    except (URLError, TimeoutError):
        pass
    time.sleep(1)
else:
    raise AssertionError("Production-mode probe not ready")
form = urlencode({"grant_type": "password", "client_id": "platform-admin-cli", "username": "admin",
                  "password": os.environ["PLATFORM_ADMIN_PASSWORD"]}).encode()
with urlopen(Request("http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect/token", data=form), timeout=10) as response:
    token = json.load(response)["access_token"]
for request, expected in (
    (Request(base + "/api/v1/admin/projects", headers={"Authorization": "Bearer " + token}), 401),
    (Request(base + "/api/v1/dev/login", data=b'{"provider":"kakao","subject":"test"}',
             headers={"Content-Type": "application/json"}), 404),
):
    try:
        urlopen(request, timeout=10)
    except HTTPError as error:
        assert error.code == expected, (error.code, expected)
    else:
        raise AssertionError("Production boundary unexpectedly allowed request")
print("PASS production mode: validly signed development token rejected; mock endpoint absent")
