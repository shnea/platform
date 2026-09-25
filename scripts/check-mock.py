"""Mock console and integration failure scenarios against real development services."""
import json
import os
import uuid
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
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=45) as r:
            status, body = r.status, r.read()
    except HTTPError as e:
        status, body = e.code, e.read()
    assert status == expected, (method, url, status, expected)
    return json.loads(body) if body else None

admin = request('POST', identity+'/realms/platform-admin-dev/protocol/openid-connect/token', dict(
    grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD']), form=True)['access_token']
provisioner = request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(
    grant_type='client_credentials', client_id='platform-provisioner-dev', client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']
api = base+'/api/v1/admin'
p = request('POST', api+'/projects', dict(code='mock-check-'+uuid.uuid4().hex[:10], name='개발 로그인 검증'), admin, expected=201)
purl = api+'/projects/'+p['id']
env = request('POST', purl+'/environments', dict(code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201)
eurl = api+'/environments/'+env['id']
console = eurl+'/mock-login'
login = base+'/api/v1/dev/login'
users = identity+'/admin/realms/'+env['realm']+'/users'
body = dict(provider='kakao', subject='scenario-check')
k = request('POST', eurl+'/credentials', dict(scopes=['auth:mock']), admin, expected=201)
read = request('POST', eurl+'/credentials', token=admin, expected=201)
request('POST', console, body, expected=401)
request('POST', console, body, key=k['apiKey'], expected=401)
assert request('GET', users, token=provisioner) == []
for scenario, code in [('cancelled',403), ('access_denied',403), ('provider_unavailable',503)]:
    payload = dict(body, scenario=scenario)
    result = request('POST', console, payload, admin)
    assert result['httpStatus'] == code and result['result']['error'] == scenario
    assert result['result']['mode'] == 'mock' and result['result']['environmentId'] == env['id']
    assert not any(name in result['result'] for name in ['accessToken','refreshToken','userId'])
    integration = request('POST', login, payload, key=k['apiKey'], expected=code)
    assert integration == result['result']
    request('POST', login, payload, key=read['apiKey'], expected=403)
assert request('GET', users, token=provisioner) == [], 'Failures must not create users'
for payload in [dict(body, scenario='unknown'), dict(body, provider='unknown'), dict(body, subject='bad user')]:
    request('POST', console, payload, admin, expected=400)
    request('POST', login, payload, key=k['apiKey'], expected=400)
for provider in ['kakao','naver','google']:
    payload = dict(body, provider=provider)
    first = request('POST', console, payload, admin)
    assert first['httpStatus'] == 200 and first['result']['scenario'] == 'success'
    assert 'accessToken' not in first['result'] and first['result']['expiresIn'] > 0
    second = request('POST', console, dict(payload, scenario='success'), admin)
    assert first['result']['userId'] == second['result']['userId']
    integration = request('POST', login, payload, key=k['apiKey'])
    assert integration['accessToken'] and integration['userId'] == first['result']['userId']
    request('POST', console, payload, token=integration['accessToken'], expected=401)
assert len(request('GET', users, token=provisioner)) == 3
prod = request('POST', purl+'/environments', dict(code='prod', kind='PROD', registrationAllowed=False, redirectUris=['https://example.test/cb']), admin, expected=201)
request('POST', api+'/environments/'+prod['id']+'/mock-login', dict(body, scenario='cancelled'), admin, expected=403)
request('PUT', purl, dict(name=p['name'], status='SUSPENDED', revision=p['revision']), admin)
request('POST', console, dict(body, scenario='cancelled'), admin, expected=409)
request('POST', login, dict(body, scenario='cancelled'), key=k['apiKey'], expected=401)
for credential in [k,read]: request('DELETE',api+'/credentials/'+credential['id'],token=admin,expected=204)
events = request('GET', api+'/audit-events?limit=100', token=admin)
assert {'mock.login','mock.login.cancelled','mock.login.access_denied','mock.login.provider_unavailable'} <= {e['action'] for e in events if e['target_id']==env['id']}
print('PASS Mock: admin boundary, safe console results, 3 providers, stable users, failure HTTP codes, no failure users/tokens, scope checks, PROD/suspended denial, audit')
print('Development test project retained:',p['code'])
