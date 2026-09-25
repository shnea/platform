"""Operator-only Keycloak connection; never import master credentials into services."""
import json
import os
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def connect():
    base = 'http://keycloak:8080/auth'
    form = urlencode(dict(grant_type='password', client_id='admin-cli',
                          username=os.environ['KEYCLOAK_ADMIN'],
                          password=os.environ['KEYCLOAK_ADMIN_PASSWORD'])).encode()
    try:
        with urlopen(Request(base+'/realms/master/protocol/openid-connect/token', data=form), timeout=15) as response:
            token = json.load(response)['access_token']
    except (HTTPError, URLError, TimeoutError):
        raise SystemExit('운영자 인증에 실패했습니다. Keycloak 상태와 운영자 설정을 확인해 주세요.') from None

    def api(method, path, body=None, allowed=(200, 201, 204)):
        request = Request(base+'/admin/realms'+path, method=method,
                          data=None if body is None else json.dumps(body).encode(),
                          headers={'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=20) as response:
                content = response.read()
                return json.loads(content) if content else None
        except HTTPError as error:
            if error.code in allowed:
                return None
            raise SystemExit(f'Keycloak 작업 실패: {method} {path}: HTTP {error.code}') from None
        except (URLError, TimeoutError):
            raise SystemExit('Keycloak 응답을 확인하지 못했습니다. 상태를 조회한 뒤 다시 진행해 주세요.') from None
    return api
