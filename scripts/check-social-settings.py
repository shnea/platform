"""Local social settings checks only: dummy credentials, disabled brokers, no external login."""
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

from concurrent.futures import ThreadPoolExecutor

api = base+'/api/v1/admin'
p = request('POST', api+'/projects', dict(code='social-settings-'+uuid.uuid4().hex[:10], name='소셜 설정 검증'), admin, expected=201)
purl = api+'/projects/'+p['id']
env = request('POST', purl+'/environments', dict(code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201)
eurl = api+'/environments/'+env['id']
url = eurl+'/social-providers'
key = request('POST', eurl+'/credentials', token=admin, expected=201)
request('GET', url, expected=401)
request('GET', url, key=key['apiKey'], expected=401)
request('GET', api+'/environments/'+str(uuid.uuid4())+'/social-providers', token=admin, expected=404)
items = request('GET', url, token=admin)
assert {item['code'] for item in items} == {'kakao','naver','google'}
assert all(not item['configured'] and not item['activationAllowed'] for item in items)
allowed = {'code','label','alias','configured','enabled','clientId','secretConfigured','revision','callbackUrl','activationAllowed'}
latest = {}
for item in items:
    provider_url = url+'/'+item['code']
    payload = dict(clientId='dummy-client', clientSecret='dummy-secret-not-a-real-key', enabled=False, revision=item['revision'])
    request('PUT', provider_url, payload, expected=401)
    request('PUT', provider_url, dict(payload, clientSecret=None), admin, expected=400)
    request('PUT', provider_url, dict(payload, enabled=True), admin, expected=400)
    request('PUT', provider_url, dict(payload, clientId=' '), admin, expected=400)
    first = request('PUT', provider_url, payload, admin)
    assert set(first) == allowed
    assert first['configured'] and first['secretConfigured'] and not first['enabled']
    assert first['callbackUrl'] == env['issuer']+'/broker/'+item['alias']+'/endpoint'
    assert 'dummy-secret' not in json.dumps(first)
    request('PUT', provider_url, payload, admin, expected=409)
    request('PUT', provider_url, dict(payload, clientId='changed', clientSecret=None, revision=first['revision']), admin, expected=400)
    second = request('PUT', provider_url, dict(payload, clientSecret=None, revision=first['revision']), admin)
    assert second['secretConfigured'] and second['revision'] != first['revision']
    latest[item['code']] = second
request('PUT', url+'/unknown', payload, admin, expected=400)

# An update race uses the same revision; exactly one writer wins.
provider_url = url+'/google'
payload = dict(clientId='dummy-client', clientSecret=None, enabled=False, revision=latest['google']['revision'])
def race(_):
    data = json.dumps(payload).encode()
    headers = {'Authorization':'Bearer '+admin, 'Content-Type':'application/json'}
    try:
        with urlopen(Request(provider_url, data=data, headers=headers, method='PUT'), timeout=45) as r: return r.status
    except HTTPError as e: return e.code
with ThreadPoolExecutor(max_workers=2) as pool:
    assert sorted(pool.map(race, range(2))) == [200,409]

# Refresh the provisioner token so it contains access to this newly created realm.
provisioner = request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(
    grant_type='client_credentials', client_id='platform-provisioner-dev', client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']
instances = identity+'/admin/realms/'+env['realm']+'/identity-provider/instances'
for item in items:
    stored = request('GET', instances+'/'+item['alias'], token=provisioner)
    assert stored['providerId'] == {'kakao':'oidc','naver':'platform-naver','google':'google'}[item['code']]
    assert not stored['enabled'] and not stored['trustEmail'] and not stored['storeToken']
    assert stored['firstBrokerLoginFlowAlias'] == 'first broker login'
    assert stored['config']['platform.environmentId'] == env['id']
    assert stored['config']['clientSecret'] and stored['config']['clientSecret'] != 'dummy-secret-not-a-real-key'
    if item['code'] == 'kakao':
        assert stored['config']['validateSignature'] == 'true' and stored['config']['useJwksUrl'] == 'true'

before = request('GET', url, token=admin)
request('POST', eurl+'/provision', token=admin)
assert before == request('GET', url, token=admin), 'Provision must preserve social settings'
request('PUT', purl, dict(name=p['name'], status='SUSPENDED', revision=p['revision']), admin)
assert before == request('GET', url, token=admin), 'Suspension must preserve social settings'
request('DELETE',api+'/credentials/'+key['id'],token=admin,expected=204)
events = request('GET', api+'/audit-events?limit=100', token=admin)
assert {'social.updated.kakao','social.updated.naver','social.updated.google'} <= {e['action'] for e in events if e['target_id']==env['id']}
assert 'dummy-secret' not in json.dumps(events)
# Remove dummy settings; leave only the suspended development project for traceability.
for item in items: request('DELETE', instances+'/'+item['alias'], token=provisioner, expected=204)
print('PASS local social settings: authorization, validation, secret masking/retention, callback isolation, revisions, race, provider registration, lifecycle preservation, audit')
print('No external social login attempted. Suspended test project:', p['code'])
