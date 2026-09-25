"""Isolated Keycloak routing checks. No real credentials, users, or external HTTP calls."""
import base64
import hashlib
import http.cookiejar
import json
import uuid
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, HTTPCookieProcessor, build_opener

BASE = 'http://callback-keycloak:8080/auth'

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def browser():
    return build_opener(NoRedirect, HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(opener, method, url, data=None, token=None, form=False, expected=200):
    # Fail closed if an accidental test redirect/call would leave the isolated Keycloak.
    assert url.startswith(BASE + '/'), 'External request prohibited'
    headers = {}
    if token: headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(data) if form else json.dumps(data)).encode()
    try:
        response = opener.open(Request(url, data=data, headers=headers, method=method), timeout=30)
    except HTTPError as error:
        response = error
    body = response.read()
    assert response.code in (expected if isinstance(expected, tuple) else (expected,)), (method, urlsplit(url).path, response.code, expected)
    return response.headers, body

admin = browser()
_, body = request(admin, 'POST', BASE+'/realms/master/protocol/openid-connect/token', dict(
    grant_type='password', client_id='admin-cli', username='callback-admin', password='callback-test-only'), form=True)
token = json.loads(body)['access_token']
_, body = request(admin, 'GET', BASE+'/realms/master/platform-social/configuration')
assert json.loads(body) == dict(naver=True, kakao=True, google=True)
assert b'callback-test-secret' not in body and b'callback-test-client' not in body

realms = []
for _ in range(2):
    environment = str(uuid.uuid4())
    realm = 'p-' + environment.replace('-', '')
    realms.append(realm)
    request(admin, 'POST', BASE+'/admin/realms', dict(realm=realm, enabled=True, sslRequired='none',
        attributes={'platform.environmentId': environment}), token, expected=201)
    path = BASE+'/admin/realms/'+realm
    request(admin, 'POST', path+'/clients', dict(clientId='test-web', publicClient=True, standardFlowEnabled=True,
        redirectUris=['http://client.invalid/callback']), token, expected=201)
    for provider in ('naver','google','kakao'):
        config = {'platform.callbackMode':'shared-v1', 'platform.environmentId':environment,
            'platform.environmentKind':'PROD', 'platform.revision':str(uuid.uuid4()), 'clientAuthMethod':'client_secret_post'}
        if provider == 'kakao':
            config.update(authorizationUrl='https://kauth.kakao.com/oauth/authorize', tokenUrl='https://kauth.kakao.com/oauth/token',
                defaultScope='openid', pkceEnabled='true', pkceMethod='S256')
        request(admin, 'POST', path+'/identity-provider/instances', dict(alias='platform-'+provider,
            providerId='platform-'+provider, enabled=True, trustEmail=False, storeToken=False, config=config), token, expected=201)

def start(realm, provider):
    client = browser()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(b'callback-test-verifier-long-enough-for-pkce-123456').digest()).decode().rstrip('=')
    url = BASE+'/realms/'+realm+'/protocol/openid-connect/auth?'+urlencode(dict(
        client_id='test-web', response_type='code', scope='openid', redirect_uri='http://client.invalid/callback',
        state='service-state', nonce='service-nonce', code_challenge=challenge, code_challenge_method='S256', kc_idp_hint='platform-'+provider))
    for _ in range(5):
        headers, _ = request(client, 'GET', url, expected=(302,303))
        url = headers['Location']
        if not url.startswith(BASE+'/'): break
    else: raise AssertionError('Unexpected redirect loop')
    params = parse_qs(urlsplit(url).query)
    assert params['redirect_uri'] == [BASE+'/social/'+provider+'/callback']
    assert params['client_id'] == ['callback-test-client']
    if provider == 'kakao': assert params['code_challenge_method'] == ['S256'] and params['nonce']
    return client, params['state'][0]

def callback(provider, state, **params):
    # Nginx maps the fixed public URL to this resource; test the same Keycloak handler.
    return BASE+'/realms/master/platform-social/'+provider+'/callback?'+urlencode(dict(state=state, **params))

for provider in ('naver','google','kakao'):
    pending = [start(realm, provider) for realm in realms]
    for realm, (client, state) in zip(realms, pending):
        url = callback(provider, state, code='test-code-never-exchanged')
        request(browser(), 'GET', url, expected=400)
        wrong = 'google' if provider != 'google' else 'naver'
        request(client, 'GET', callback(wrong, state, code='test-code'), expected=400)
        request(client, 'GET', url+'&state=duplicate', expected=400)
        headers, _ = request(client, 'GET', url, expected=303)
        assert urlsplit(headers['Location']).path == '/auth/realms/'+realm+'/broker/platform-'+provider+'/endpoint'
        assert headers['Cache-Control'] == 'no-store'
        request(client, 'GET', url, expected=400)
    client, state = start(realms[0], provider)
    headers, _ = request(client, 'GET', callback(provider, state, error='access_denied', error_description='untrusted'), expected=303)
    assert 'untrusted' not in headers['Location']
    headers, body = request(client, 'GET', headers['Location'], expected=(200,302))
    if headers.get('Location'):
        assert headers['Location'].startswith('http://client.invalid/callback')
        assert parse_qs(urlsplit(headers['Location']).query)['error'] == ['access_denied']
    else:
        # Keycloak may return to the realm login form so the user can choose another method.
        assert b'<form' in body and realms[0].encode() in body and b'access_token' not in body
    # A direct native callback must not reach any token endpoint without the common handoff.
    client, state = start(realms[0], provider)
    direct = BASE+'/realms/'+realms[0]+'/broker/platform-'+provider+'/endpoint?'+urlencode(dict(state=state, code='direct-bypass'))
    request(client, 'GET', direct, expected=400)
    print('PASS', provider, 'fixed authorization URI, common credentials, two realms, browser binding, provider mismatch, duplicate/replay rejection, cancellation, native bypass rejection')

request(admin, 'GET', callback('naver', 'unknown', code='code'), expected=400)
print('PASS shared callback routing; no external requests or real social authentication performed')
