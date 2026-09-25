"""Read-only business API checks; creates and ends only its own admin login session."""
import json
import os
import re
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = 'http://nginx:8080'
IDENTITY = 'http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect'
SENTINEL = 'contract-sensitive-input-must-not-echo'


def call(method, path, data=None, headers=None):
    try:
        with urlopen(Request(path if path.startswith('http') else BASE + path,
                             data=data, headers=headers or {}, method=method), timeout=30) as response:
            return response.status, response.headers, response.read()
    except HTTPError as error:
        return error.code, error.headers, error.read()


def check(method, path, status, code, data=None, token=None, media='application/json'):
    incoming = 'a' * 32
    headers = {'X-Request-ID': incoming, 'Content-Type': media}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    actual, response_headers, raw = call(method, path, data, headers)
    assert actual == status, (method, path.split('?')[0], actual, status)
    assert response_headers.get_content_type() == 'application/problem+json'
    body = json.loads(raw)
    assert body['status'] == status and body['code'] == code
    rid = response_headers['X-Request-ID']
    assert re.fullmatch('[a-f0-9]{32}', rid) and rid != incoming
    assert len(response_headers.get_all('X-Request-ID')) == 1
    assert body['requestId'] == rid
    assert body['instance'] == 'urn:shnea:platform:request:' + rid
    assert body['type'] == 'urn:shnea:platform:error:' + code
    assert re.search('[가-힣]', body['detail']) and response_headers['Content-Language'] == 'ko'
    assert response_headers['Cache-Control'] == 'no-store'
    assert SENTINEL not in raw.decode() and 'stackTrace' not in body
    print(f'PASS {method} {path.split("?")[0]} {status} {code} requestId={rid}')
    return body, response_headers


def main():
    status, _, raw = call('GET', '/api/v1/config')
    assert status == 200 and json.loads(raw)['mode'] == 'dev', 'DEV stack required'
    _, headers = check('GET', '/api/v1/admin/projects', 401, 'AUTHENTICATION_REQUIRED')
    assert headers['WWW-Authenticate'] == 'Bearer'
    check('GET', '/api/v1/admin/projects', 401, 'AUTHENTICATION_REQUIRED', token=SENTINEL)
    check('GET', '/api/v1/integration/context', 401, 'INVALID_API_KEY')
    form_headers = {'Content-Type': 'application/x-www-form-urlencoded'}
    fields = dict(grant_type='password', client_id='platform-admin-cli', username='admin',
                  password=os.environ['PLATFORM_ADMIN_PASSWORD'])
    status, _, raw = call('POST', IDENTITY + '/token', urlencode(fields).encode(), form_headers)
    assert status == 200, 'DEV admin login failed'
    session = json.loads(raw)
    admin = session['access_token']
    try:
        check('GET', '/api/v1/forbidden', 403, 'ACCESS_DENIED', token=admin)
        check('GET', '/api/v1/admin/projects?limit=0&secret=' + SENTINEL, 400, 'INVALID_PAGINATION', token=admin)
        check('GET', '/api/v1/admin/projects?limit=' + SENTINEL, 400, 'INVALID_REQUEST', token=admin)
        check('GET', '/api/v1/admin/projects/' + str(uuid.uuid4()) + '/environments', 404, 'RESOURCE_NOT_FOUND', token=admin)
        check('GET', '/api/v1/admin/missing', 404, 'RESOURCE_NOT_FOUND', token=admin)
        _, headers = check('DELETE', '/api/v1/admin/projects', 405, 'METHOD_NOT_ALLOWED', token=admin)
        assert 'GET' in headers['Allow'] and 'POST' in headers['Allow']
        check('POST', '/api/v1/admin/projects', 400, 'INVALID_REQUEST', b'{"name":"' + SENTINEL.encode() + b'",', admin)
        body, _ = check('POST', '/api/v1/admin/projects', 400, 'VALIDATION_FAILED', b'{"code":"!","name":""}', admin)
        assert {error['field'] for error in body['errors']} == {'code', 'name'}
        assert all(re.search('[가-힣]', error['message']) for error in body['errors'])
        check('POST', '/api/v1/admin/projects', 415, 'UNSUPPORTED_MEDIA_TYPE', SENTINEL.encode(), admin, 'text/plain')
        status, headers, raw = call('GET', '/api/v1/config', headers={'X-Request-ID': 'a' * 32})
        assert status == 200 and headers['X-Request-ID'] != 'a' * 32 and json.loads(raw)['mode'] == 'dev'
        # Protocol errors stay OAuth JSON; the platform problem contract must not wrap them.
        status, headers, raw = call('POST', IDENTITY + '/token', urlencode(dict(grant_type='invalid', client_id='platform-admin-cli')).encode(), form_headers)
        assert status == 400 and 'error' in json.loads(raw) and 'requestId' not in json.loads(raw)
        print('PASS success tracing and unchanged OAuth error contract')
    finally:
        fields = dict(client_id='platform-admin-cli', refresh_token=session['refresh_token'])
        status, _, _ = call('POST', IDENTITY + '/logout', urlencode(fields).encode(), form_headers)
        assert status == 204, 'End the contract-check admin session'
    print('PASS API contract; no project/member data changed; own admin session ended')


if __name__ == '__main__':
    main()
