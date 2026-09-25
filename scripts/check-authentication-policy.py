"""Authentication policy configuration only; no delivery or external social login."""
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
p = request('POST', api+'/projects', dict(code='policy-check-'+uuid.uuid4().hex[:10], name='가입 복구 정책 검증'), admin, expected=201)
purl = api+'/projects/'+p['id']
env = request('POST', purl+'/environments', dict(code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201)
eurl = api+'/environments/'+env['id']
url = eurl+'/authentication-policy'
request('GET', url, expected=401)
key = request('POST', eurl+'/credentials', token=admin, expected=201)
request('GET', url, key=key['apiKey'], expected=401)
current = request('GET', url, token=admin)
assert current == dict(loginWithEmail=True, verifyEmail=False, resetPasswordAllowed=False,
    passwordMinLength=12, passwordPolicyEditable=True, emailActionsAvailable=False, revision='unconfigured')
payload = dict(loginWithEmail=False, verifyEmail=False, resetPasswordAllowed=False, passwordMinLength=16, revision=current['revision'])
for update in [dict(passwordMinLength=11), dict(passwordMinLength=129), dict(passwordMinLength=None),
               dict(verifyEmail=True), dict(resetPasswordAllowed=True), dict(loginWithEmail=None)]:
    request('PUT', url, dict(payload, **update), admin, expected=400)
assert request('GET', url, token=admin) == current, 'Invalid settings must not change policy'
changed = request('PUT', url, payload, admin)
assert not changed['loginWithEmail'] and changed['passwordMinLength']==16
request('PUT', url, payload, admin, expected=409)

provisioner = request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(
    grant_type='client_credentials', client_id='platform-provisioner-dev', client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']
rurl = identity+'/admin/realms/'+env['realm']
realm = request('GET', rurl, token=provisioner)
assert realm['passwordPolicy'] == 'length(16) and maxLength(128)'
assert realm['enabled'] and not realm['registrationAllowed'] and not realm['loginWithEmailAllowed']
assert realm['attributes']['platform.environmentId'] == env['id']

# Verify enforcement using internal Keycloak administration, not user login.
username = 'policy-local-user'
request('POST', rurl+'/users', dict(username=username, enabled=True), provisioner, expected=201)
user = request('GET',rurl+'/users?exact=true&username='+username,token=provisioner)[0]
password_url = rurl+'/users/'+user['id']+'/reset-password'
request('PUT',password_url,dict(type='password',value='short',temporary=False),provisioner,expected=400)
request('PUT',password_url,dict(type='password',value='x'*129,temporary=False),provisioner,expected=400)
request('PUT',password_url,dict(type='password',value='dummy-long-password-12345',temporary=False),provisioner,expected=204)
request('DELETE',rurl+'/users/'+user['id'],token=provisioner,expected=204)

# The strongest managed policy must still allow the internal DEV Mock flow.
maximum = request('PUT',url,dict(payload,passwordMinLength=128,revision=changed['revision']),admin)
mock = request('POST',eurl+'/mock-login',dict(provider='kakao',subject='policy-mock'),admin)
assert mock['httpStatus']==200
request('DELETE',rurl+'/users/'+mock['result']['userId'],token=provisioner,expected=204)
changed = request('PUT',url,dict(payload,revision=maximum['revision']),admin)

# Updating signup/callback settings must retain the separate policy and its revision.
request('PUT', eurl, dict(registrationAllowed=True,redirectUris=env['redirectUris'],revision=env['revision']),admin)
assert request('GET',url,token=admin)==changed
assert request('GET',rurl,token=provisioner)['registrationAllowed']

payload = dict(payload, revision=changed['revision'], passwordMinLength=20)
def race(_):
    headers={'Authorization':'Bearer '+admin,'Content-Type':'application/json'}
    try:
        with urlopen(Request(url,data=json.dumps(payload).encode(),headers=headers,method='PUT'),timeout=45) as response: return response.status
    except HTTPError as error: return error.code
with ThreadPoolExecutor(max_workers=2) as pool: assert sorted(pool.map(race,range(2)))==[200,409]

# Existing custom Keycloak rules are visible as unsupported and cannot be weakened.
managed = request('GET', url, token=admin)
request('PUT',rurl,dict(passwordPolicy='length(24) and digits(1)'),provisioner,expected=204)
assert not request('GET',url,token=admin)['passwordPolicyEditable']
request('PUT',url,dict(payload,revision=managed['revision']),admin,expected=409)
assert request('GET',rurl,token=provisioner)['passwordPolicy']=='length(24) and digits(1)'
request('PUT',rurl,dict(passwordPolicy='length(20) and maxLength(128)'),provisioner,expected=204)

before = request('GET',url,token=admin)
request('PUT',purl,dict(name=p['name'],status='SUSPENDED',revision=p['revision']),admin)
request('PUT',url,dict(payload,revision=before['revision'],passwordMinLength=18),admin)
realm = request('GET',rurl,token=provisioner)
assert not realm['enabled'] and realm['passwordPolicy']=='length(18) and maxLength(128)'
request('DELETE',api+'/credentials/'+key['id'],token=admin,expected=204)
events=request('GET',api+'/audit-events?limit=100',token=admin)
assert any(e['action']=='authentication.policy.updated' and e['target_id']==env['id'] for e in events)
print('PASS policies: admin boundary, defaults, validation, production email gate, actual password length enforcement, revision race, custom-rule protection, signup coexistence, suspended realm, audit')
print('No email or social login attempted. Suspended test project:',p['code'])
