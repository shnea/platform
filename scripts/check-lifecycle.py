"""Lifecycle integration check. Uses a new test project, never existing project settings."""
import json
import os
import uuid
import time
from datetime import datetime, timezone, timedelta
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from urllib.error import HTTPError

base = 'http://nginx:8080'
identity = 'http://keycloak:8080/auth'

def request(method, url, data=None, token=None, key=None, expected=200, form=False):
    headers = {}
    if token: headers['Authorization'] = 'Bearer ' + token
    if key: headers['X-Platform-Key'] = key
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(data) if form else json.dumps(data)).encode()
    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=60) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    assert status in (expected if isinstance(expected, tuple) else (expected,)), (method, url, status, expected)
    result = json.loads(body) if body else None
    return (status, result) if isinstance(expected, tuple) else result

admin = request('POST', identity + '/realms/platform-admin-dev/protocol/openid-connect/token',
    dict(grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD']), form=True)['access_token']
api = base + '/api/v1/admin'
p = request('POST', api + '/projects', dict(code='lifecycle-' + uuid.uuid4().hex[:12], name='수명 주기 검증'), admin, expected=201)
purl = api + '/projects/' + p['id']
e = request('POST', purl + '/environments', dict(code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201)
eurl = api + '/environments/' + e['id']
assert e['state'] == 'READY'
k = request('POST', eurl + '/credentials', token=admin, expected=201)
assert 'expiresAt' in k and k['expiresAt'] is None
listing = request('GET', eurl + '/credentials', token=admin)
assert 'expires_at' in listing[0] and listing[0]['expires_at'] is None
assert listing[0]['id'] == k['id'] and 'apiKey' not in listing[0] and 'secret_hash' not in listing[0]
request('GET', base + '/api/v1/integration/context', key=k['apiKey'])
# Optional expiry remains enforced; omitting it preserves the non-expiring default.
request('POST', eurl + '/credentials', dict(expiresAt='2000-01-01T00:00:00Z'), admin, expected=400)
request('POST', eurl + '/credentials', dict(expiresAt='not-a-date'), admin, expected=400)
expiry = datetime.now(timezone.utc) + timedelta(seconds=3)
short = request('POST', eurl + '/credentials', dict(expiresAt=expiry.isoformat()), admin, expected=201)
assert datetime.fromisoformat(short['expiresAt'].replace('Z', '+00:00')) == expiry
request('GET', base + '/api/v1/integration/context', key=short['apiKey'])
time.sleep(max(0, (expiry - datetime.now(timezone.utc)).total_seconds()) + 0.2)
request('GET', base + '/api/v1/integration/context', key=short['apiKey'], expected=401)
request('POST', base + '/api/v1/dev/login', dict(provider='google', subject='expired'), key=short['apiKey'], expected=401)
request('GET', base + '/api/v1/integration/context', key=k['apiKey'])

settings = dict(registrationAllowed=True, redirectUris=['https://example.org/login/callback'], revision=e['revision'])
e = request('PUT', eurl, settings, admin)
assert e['state'] == 'READY' and e['revision'] == 1
request('PUT', eurl, settings, admin, expected=409)
request('PUT', eurl, dict(settings, redirectUris=['http://unsafe.example/cb'], revision=e['revision']), admin, expected=400)
# Two editors saving the same revision must produce exactly one accepted update.
with ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(lambda _: request('PUT', eurl, dict(settings, revision=e['revision']), admin, expected=(200, 409)), range(2)))
assert sorted(status for status, _ in results) == [200, 409]
e = next(body for status, body in results if status == 200)
provisioner = request('POST', identity + '/realms/master/protocol/openid-connect/token',
    dict(grant_type='client_credentials', client_id='platform-provisioner-dev', client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']
realm_url = identity + '/admin/realms/' + e['realm']
realm = request('GET', realm_url, token=provisioner)
assert realm['registrationAllowed'] and realm['enabled']
client = request('GET', realm_url + '/clients?clientId=app', token=provisioner)[0]
assert client['redirectUris'] == settings['redirectUris'] and client['webOrigins'] == ['https://example.org']
assert client['attributes']['pkce.code.challenge.method'] == 'S256' and not client['directAccessGrantsEnabled']
request('POST', base + '/api/v1/dev/login', dict(provider='google', subject='lifecycle-check'), key=k['apiKey'])
mock_user = request('GET', realm_url + '/users', token=provisioner)[0]
assert request('GET', realm_url + '/users/' + mock_user['id'] + '/sessions', token=provisioner)
original = dict(name=p['name'], status='SUSPENDED', revision=p['revision'])
p = request('PUT', purl, original, admin)
assert p['status'] == 'SUSPENDED'
request('PUT', purl, original, admin, expected=409)
request('GET', base + '/api/v1/integration/context', key=k['apiKey'], expected=401)
request('POST', base + '/api/v1/dev/login', dict(provider='google', subject='stopped'), key=k['apiKey'], expected=401)
request('POST', eurl + '/credentials', token=admin, expected=409)
request('POST', purl + '/environments', dict(code='blocked', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost/cb']), admin, expected=409)
assert not request('GET', realm_url, token=provisioner)['enabled']
assert request('GET', realm_url + '/users/' + mock_user['id'] + '/sessions', token=provisioner) == []
e = request('GET', purl + '/environments', token=admin)[0]
# Changing settings while suspended must not accidentally re-enable a realm.
e = request('PUT', eurl, dict(registrationAllowed=False, redirectUris=['http://localhost:3000/new'], revision=e['revision']), admin)
assert e['state'] == 'READY' and not request('GET', realm_url, token=provisioner)['enabled']
p = request('PUT', purl, dict(name='재개한 프로젝트', status='ACTIVE', revision=p['revision']), admin)
request('GET', base + '/api/v1/integration/context', key=k['apiKey'])
assert request('GET', realm_url, token=provisioner)['enabled']
request('DELETE', api + '/credentials/' + k['id'], token=admin, expected=204)
request('GET', base + '/api/v1/integration/context', key=k['apiKey'], expected=401)
assert request('GET', base + '/api/v1/config')['clientId'] == 'platform-admin-web'
print('PASS lifecycle: default non-expiring keys, optional expiry enforcement, settings, concurrent revision conflict, origins, session logout, suspend/resume, blocked keys/Mock/creation, suspended edits, credential metadata')
