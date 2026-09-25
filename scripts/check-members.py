"""Real member isolation/state/session checks; uses only newly created development fixtures."""
import argparse
import json
import os
import secrets
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

base = 'http://nginx:8080/api/v1/admin'
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
    except HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (method, url, status, expected)
    return json.loads(body) if body else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--keep-active', action='store_true', help='Keep successful fixtures active for browser checks')
    args = parser.parse_args()
    admin = request('POST', identity+'/realms/platform-admin-dev/protocol/openid-connect/token', dict(
        grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD']), form=True)['access_token']

    def provisioner():
        return request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(
            grant_type='client_credentials', client_id='platform-provisioner-dev',
            client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']

    projects, envs, keys = [], [], []
    passed = False
    try:
        for index in range(2):
            project = request('POST', base+'/projects', dict(code='members-'+uuid.uuid4().hex[:10], name='회원 관리 검증 '+str(index+1)), admin, expected=201)
            projects.append(project)
            envs.append(request('POST', base+'/projects/'+project['id']+'/environments', dict(
                code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201))
        # Include another environment of the same project as well as a separate project.
        envs.append(request('POST', base+'/projects/'+projects[0]['id']+'/environments', dict(
            code='dev-two', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:3000/cb']), admin, expected=201))
        env = envs[0]
        eurl = base+'/environments/'+env['id']
        url = eurl+'/users'
        realm = identity+'/admin/realms/'+env['realm']
        kc = provisioner()
        assert request('GET', url, token=admin) == dict(items=[], hasMore=False)
        password = secrets.token_urlsafe(24)
        client = 'member-check'
        request('POST', realm+'/clients', dict(clientId=client, publicClient=True, standardFlowEnabled=False,
            directAccessGrantsEnabled=True), kc, expected=201)
        for name in ['alice', 'bob'] + ['page-'+str(i).zfill(2) for i in range(20)]:
            request('POST', realm+'/users', dict(username=name, firstName='검증', lastName=name, email=name+'@example.invalid',
                emailVerified=True, enabled=True, credentials=[dict(type='password', value=password, temporary=False)]), kc, expected=201)
        users = request('GET', url+'?limit=20', token=admin)
        assert len(users['items']) == 20 and users['hasMore']
        next_page = request('GET', url+'?limit=20&offset=20', token=admin)
        assert len(next_page['items']) == 2 and not next_page['hasMore']
        assert not set(u['id'] for u in users['items']) & set(u['id'] for u in next_page['items'])
        alice = request('GET', url+'?search=alice', token=admin)['items'][0]
        bob = request('GET', url+'?search=bob', token=admin)['items'][0]
        user_url = url+'/'+alice['id']
        assert request('GET', url+'?search=example.invalid', token=admin)['items']
        for search in ['없는회원', 'alice&enabled=false', 'a+b@example.invalid', '{realm}', '100%']:
            assert not request('GET', url+'?'+urlencode(dict(search=search)), token=admin)['items']
        for suffix in ['?limit=0', '?limit=101', '?offset=-1', '?offset=1000001', '?search='+'x'*201, '?search=%00']:
            request('GET', url+suffix, token=admin, expected=400)
        detail = request('GET', user_url, token=admin)
        assert detail['providers'] == [] and detail['user'] == alice
        assert set(alice) == {'id', 'username', 'email', 'firstName', 'lastName', 'enabled', 'emailVerified', 'createdTimestamp'}
        # A disabled provider link is sufficient to verify metadata without external login.
        social = request('GET', eurl+'/social-providers', token=admin)
        google = next(provider for provider in social if provider['code'] == 'google')
        request('PUT', eurl+'/social-providers/google', dict(enabled=False, revision=google['revision']), admin)
        request('POST', realm+'/users/'+alice['id']+'/federated-identity/'+google['alias'],
            dict(identityProvider=google['alias'], userId='external-test-id', userName='external-test-name'), kc, expected=204)
        linked = request('GET', user_url, token=admin)
        assert linked['providers'] == [google['alias']] and 'external-test-id' not in json.dumps(linked)
        request('POST', realm+'/clients', dict(clientId='member-service-check', publicClient=False,
            serviceAccountsEnabled=True, standardFlowEnabled=False), kc, expected=201)
        service_client = request('GET', realm+'/clients?clientId=member-service-check', token=kc)[0]
        service_user = request('GET', realm+'/clients/'+service_client['id']+'/service-account-user', token=kc)
        request('GET', url+'/'+service_user['id'], token=admin, expected=404)
        request('PUT', url+'/'+service_user['id']+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=404)
        assert service_user['id'] not in [u['id'] for u in request('GET', url+'?limit=100', token=admin)['items']]
        request('GET', url, expected=401)
        key = request('POST', eurl+'/credentials', token=admin, expected=201)
        keys.append(key['id'])
        request('GET', url, key=key['apiKey'], expected=401)
        request('PUT', user_url+'/state', dict(enabled=False, expectedEnabled=True), key=key['apiKey'], expected=401)
        request('DELETE', user_url+'/sessions', key=key['apiKey'], expected=401)
        for other in envs[1:]:
            foreign = base+'/environments/'+other['id']+'/users/'+alice['id']
            request('GET', foreign, token=admin, expected=404)
            request('GET', foreign+'/sessions', token=admin, expected=404)
            request('PUT', foreign+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=404)
            request('DELETE', foreign+'/sessions', token=admin, expected=404)

        token_url = identity+'/realms/'+env['realm']+'/protocol/openid-connect/token'
        def login(username='alice', offline=False, expected=200):
            return request('POST', token_url, dict(grant_type='password', client_id=client, username=username,
                password=password, scope='openid offline_access' if offline else 'openid'), form=True, expected=expected)

        tokens = login()
        request('GET', url, token=tokens['access_token'], expected=401)
        sessions = request('GET', user_url+'/sessions', token=admin)
        assert len(sessions) == 1 and abs(sessions[0]['start'] - time.time()*1000) < 60000
        bob_tokens = login('bob')
        bob_sessions = request('GET', url+'/'+bob['id']+'/sessions', token=admin)
        request('DELETE', user_url+'/sessions/'+bob_sessions[0]['id'], token=admin, expected=404)
        assert request('GET', url+'/'+bob['id']+'/sessions', token=admin)
        request('DELETE', user_url+'/sessions/'+sessions[0]['id'], token=admin, expected=204)
        assert request('GET', user_url+'/sessions', token=admin) == []
        request('POST', token_url, dict(grant_type='refresh_token', client_id=client, refresh_token=tokens['refresh_token']), form=True, expected=400)
        request('DELETE', user_url+'/sessions/'+sessions[0]['id'], token=admin, expected=404)
        login(); login()
        offline = login(offline=True)
        request('DELETE', user_url+'/sessions', token=admin, expected=204)
        assert request('GET', user_url+'/sessions', token=admin) == []
        request('POST', token_url, dict(grant_type='refresh_token', client_id=client, refresh_token=offline['refresh_token']), form=True, expected=400)
        login()
        for payload in [{}, dict(enabled=False), dict(enabled=None, expectedEnabled=True)]:
            request('PUT', user_url+'/state', payload, admin, expected=400)
        # Only one concurrent state change may use the same observed enabled state.
        def disable(_):
            try:
                request('PUT', user_url+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=204)
                return 204
            except AssertionError as e:
                assert e.args[0][2] == 409
                return 409
        with ThreadPoolExecutor(max_workers=2) as pool:
            assert sorted(pool.map(disable, range(2))) == [204, 409]
        assert not request('GET', user_url, token=admin)['user']['enabled']
        assert request('GET', user_url+'/sessions', token=admin) == []
        login(expected=400)
        request('PUT', user_url+'/state', dict(enabled=True, expectedEnabled=False), admin, expected=204)
        login()
        preserved = request('GET', realm+'/users/'+alice['id'], token=kc)
        assert preserved['email'] == alice['email'] and preserved['emailVerified'] and preserved['firstName'] == alice['firstName']
        # The realm ownership marker is mandatory even with a valid DB environment.
        realm_data = request('GET', realm, token=kc)
        attributes = dict(realm_data['attributes'])
        try:
            request('PUT', realm, dict(attributes=dict(attributes, **{'platform.environmentId': str(uuid.uuid4())})), kc, expected=204)
            request('GET', url, token=admin, expected=409)
            request('PUT', user_url+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=409)
        finally:
            request('PUT', realm, dict(attributes=attributes), kc, expected=204)
        suspended = request('PUT', base+'/projects/'+projects[0]['id'], dict(name=projects[0]['name'], status='SUSPENDED', revision=0), admin)
        assert request('GET', user_url, token=admin)['user']['enabled']
        request('PUT', user_url+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=204)
        request('PUT', user_url+'/state', dict(enabled=True, expectedEnabled=False), admin, expected=409)
        request('DELETE', user_url+'/sessions', token=admin, expected=204)
        assert not request('GET', realm, token=kc)['enabled']
        projects[0] = request('PUT', base+'/projects/'+projects[0]['id'], dict(name=projects[0]['name'], status='ACTIVE', revision=suspended['revision']), admin)
        request('PUT', user_url+'/state', dict(enabled=True, expectedEnabled=False), admin, expected=204)
        login()
        events = request('GET', base+'/audit-events?limit=100', token=admin)
        for action in ['user.disabled', 'user.enabled', 'user.session.ended', 'user.sessions.ended', 'user.disabled.failed']:
            assert any(e['action'] == action and e['target_id'] == alice['id'] and e['environment_id'] == env['id'] for e in events), action
        assert any(e['action'] == 'user.session.ended' and e['session_id'] == sessions[0]['id'] for e in events)
        assert password not in json.dumps(events) and 'access_token' not in json.dumps(events)
        # Disabled development users must fail before resetting their temporary password.
        mock_payload = dict(provider='google', subject='member-state-check')
        mock = request('POST', eurl+'/mock-login', mock_payload, admin)
        mock_url = url+'/'+mock['result']['userId']
        request('PUT', mock_url+'/state', dict(enabled=False, expectedEnabled=True), admin, expected=204)
        request('POST', eurl+'/mock-login', mock_payload, admin, expected=403)
        request('PUT', mock_url+'/state', dict(enabled=True, expectedEnabled=False), admin, expected=204)
        assert request('POST', eurl+'/mock-login', mock_payload, admin)['httpStatus'] == 200
        passed = True
        print('PASS member pagination/search, response allowlist, admin boundary, cross-project/environment/session isolation, refresh revocation, offline logout, state race, actual login denial, profile preservation, suspended project, ownership guard, audit')
        print('Browser fixture project:', projects[0]['code'], 'environment:', env['id'], 'user: alice')
    finally:
        for key_id in keys:
            request('DELETE', base+'/credentials/'+key_id, token=admin, expected=204)
        if not (passed and args.keep_active):
            current = request('GET', base+'/projects?limit=100', token=admin)
            for project in projects:
                row = next(p for p in current if p['id'] == project['id'])
                request('PUT', base+'/projects/'+row['id'], dict(name=row['name'], status='SUSPENDED', revision=row['revision']), admin)
            print('Test projects suspended; users retained; issued API keys revoked.')


if __name__ == '__main__':
    main()
