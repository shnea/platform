"""DEV reset end-to-end contract; mutates only newly created test projects."""
import argparse
import hashlib
import json
import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

api = 'http://nginx:8080/api/v1/admin'
identity = 'http://keycloak:8080/auth'


def request(method, url, data=None, token=None, key=None, expected=200, form=False):
    headers = {}
    if token: headers['Authorization'] = 'Bearer ' + token
    if key: headers['X-Platform-Key'] = key
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(data) if form else json.dumps(data)).encode()
    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=120) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    if expected is None: return status, json.loads(body) if body else None
    assert status == expected, (method, url, status, expected)
    return json.loads(body) if body else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--keep-active', action='store_true')
    args = parser.parse_args()
    admin = request('POST', identity+'/realms/platform-admin-dev/protocol/openid-connect/token', dict(
        grant_type='password', client_id='platform-admin-cli', username='admin',
        password=os.environ['PLATFORM_ADMIN_PASSWORD']), form=True)['access_token']

    def provisioner():
        return request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(
            grant_type='client_credentials', client_id='platform-provisioner-dev',
            client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']

    projects, keys = [], []
    passed = False
    try:
        for index in range(2):
            projects.append(request('POST', api+'/projects', dict(code='reset-check-'+uuid.uuid4().hex[:8],
                name='테스트 계정 초기화 검증 '+str(index+1)), admin, expected=201))

        def environment(project, code, kind='DEV'):
            return request('POST', api+'/projects/'+project['id']+'/environments', dict(code=code, kind=kind,
                registrationAllowed=False, redirectUris=['https://example.test/callback']), admin, expected=201)

        env = environment(projects[0], 'dev')
        other = environment(projects[0], 'dev-two')
        prod = environment(projects[0], 'prod', 'PROD')
        foreign = environment(projects[1], 'dev')
        kc = provisioner()
        eurl = api+'/environments/'+env['id']
        reset = eurl+'/mock-users/reset'
        preview_url = reset+'-preview'
        realm = identity+'/admin/realms/'+env['realm']
        first = request('GET', preview_url, token=admin)
        assert first['items'] == [] and not first['hasMore']

        def mock(target, subject):
            return request('POST', api+'/environments/'+target['id']+'/mock-login',
                dict(provider='google', subject=subject), admin)['result']['userId']

        own = mock(env, 'reset-one')
        others = [(other, mock(other, 'keep-other-env')), (foreign, mock(foreign, 'keep-other-project'))]
        key = request('POST', eurl+'/credentials', dict(scopes=['auth:mock']), admin, expected=201)
        keys.append(key)
        user_token = request('POST', 'http://nginx:8080/api/v1/dev/login',
            dict(provider='google', subject='reset-one'), key=key['apiKey'])['accessToken']
        request('GET', preview_url, expected=401)
        request('GET', preview_url, key=key['apiKey'], expected=401)
        request('GET', preview_url, token=user_token, expected=401)
        for method, suffix in [('GET','-preview'), ('POST','')]:
            data = dict(userIds=[own], revision='0'*64) if method == 'POST' else None
            request(method, api+'/environments/'+prod['id']+'/mock-users/reset'+suffix, data, admin, expected=403)
        request('POST', reset, dict(userIds=[own], revision='0'*64), expected=401)
        request('POST', reset, dict(userIds=[own], revision='0'*64), key=key['apiKey'], expected=401)
        request('POST', reset, dict(userIds=[own], revision='0'*64), token=user_token, expected=401)

        # An ordinary account with a mock-looking name is never eligible without the marker.
        ordinary_name = 'mock-' + hashlib.sha256(b'ordinary').hexdigest()
        request('POST', realm+'/users', dict(username=ordinary_name, firstName='Mock', lastName='google',
            email=ordinary_name[5:]+'@example.invalid', enabled=True), kc, expected=201)
        ordinary = request('GET', realm+'/users?username='+ordinary_name+'&exact=true', token=kc)[0]['id']
        protected = mock(env, 'protected-admin')
        management = request('GET', realm+'/clients?clientId=realm-management', token=kc)[0]
        role = request('GET', realm+'/clients/'+management['id']+'/roles/manage-users', token=kc)
        request('POST', realm+'/users/'+protected+'/role-mappings/clients/'+management['id'], [role], kc, expected=204)
        grouped = mock(env, 'protected-group')
        request('POST', realm+'/groups', dict(name='protected-group'), kc, expected=201)
        group = request('GET', realm+'/groups?search=protected-group', token=kc)[0]
        request('PUT', realm+'/users/'+grouped+'/groups/'+group['id'], token=kc, expected=204)
        plan = request('GET', preview_url, token=admin)
        assert [u['id'] for u in plan['items']] == [own]
        assert set(plan) == {'items','hasMore','revision'}
        payload = dict(userIds=[own], revision=plan['revision'])
        for ids in [[ordinary], [protected], [others[0][1]], [own, own]]:
            request('POST', reset, dict(payload, userIds=ids), admin, expected=409)
        for invalid in [dict(payload, userIds=[]), dict(payload, userIds=[None]), dict(payload, revision='bad'),
                        dict(payload, userIds=[own]*21)]:
            request('POST', reset, invalid, admin, expected=400)
        assert request('GET', realm+'/users/'+own+'/sessions', token=kc)

        # Fail closed on tampered realm ownership or a user-editable mock marker.
        realm_data = request('GET', realm, token=kc)
        request('PUT', realm, dict(attributes={'platform.environmentId':str(uuid.uuid4())}), kc, expected=204)
        request('POST', reset, payload, admin, expected=409)
        request('PUT', realm, dict(attributes=realm_data['attributes']), kc, expected=204)
        profile = request('GET', realm+'/users/profile', token=kc)
        changed = json.loads(json.dumps(profile))
        next(a for a in changed['attributes'] if a['name']=='platformMock')['permissions']['edit'] = ['admin','user']
        request('PUT', realm+'/users/profile', changed, kc)
        request('POST', reset, payload, admin, expected=409)
        request('PUT', realm+'/users/profile', profile, kc)

        newer = mock(env, 'created-after-preview')
        request('POST', reset, payload, admin, expected=409)
        plan = request('GET', preview_url, token=admin)
        assert {u['id'] for u in plan['items']} == {own,newer}

        # Obtain an offline session for a disposable account and ensure its refresh grant dies.
        mock_client = request('GET', realm+'/clients?clientId=platform-mock', token=kc)[0]
        secret = request('GET', realm+'/clients/'+mock_client['id']+'/client-secret', token=kc)['value']
        password = uuid.uuid4().hex + uuid.uuid4().hex
        request('PUT', realm+'/users/'+own+'/reset-password', dict(type='password', value=password, temporary=False), kc, expected=204)
        username = next(u['username'] for u in plan['items'] if u['id']==own)
        token_url = identity+'/realms/'+env['realm']+'/protocol/openid-connect/token'
        offline = request('POST', token_url, dict(grant_type='password', client_id='platform-mock', client_secret=secret,
            username=username, password=password, scope='offline_access'), form=True)
        assert request('GET', realm+'/users/'+own+'/offline-sessions/'+mock_client['id'], token=kc)
        payload = dict(userIds=[u['id'] for u in plan['items']], revision=plan['revision'])
        # Concurrent repeat: one wins, the other must re-preview, never delete a replacement user.
        with ThreadPoolExecutor(max_workers=2) as pool:
            replies = list(pool.map(lambda _: request('POST', reset, payload, admin, expected=None), range(2)))
        assert sorted(status for status,_ in replies) == [200,409]
        result = next(body for status,body in replies if status == 200)
        assert (result['requested'], result['deleted'], result['failed']) == (2,2,0)
        for user_id in [own,newer]: request('GET', realm+'/users/'+user_id, token=kc, expected=404)
        request('POST', token_url, dict(grant_type='refresh_token', client_id='platform-mock', client_secret=secret,
            refresh_token=offline['refresh_token']), form=True, expected=400)
        for user_id in [ordinary,protected,grouped]:
            assert request('GET', realm+'/users/'+user_id, token=kc)['enabled']
        for target,user_id in others:
            assert request('GET', identity+'/admin/realms/'+target['realm']+'/users/'+user_id, token=kc)['enabled']
        assert request('GET', preview_url, token=admin)['items'] == []
        replacement = mock(env, 'reset-one')
        assert replacement != own
        request('POST', reset, payload, admin, expected=409)

        events = request('GET', api+'/audit-events?limit=100', token=admin)
        for user_id in [own,newer]:
            assert {'mock.user.reset.started','mock.user.deleted'} <= {
                e['action'] for e in events if e['target_id']==user_id and e['environment_id']==env['id']}
        assert any(e['action']=='mock.reset.completed' and e['target_id']==env['id'] for e in events)
        assert not any(e['action'].startswith('mock.user.') and e['target_id'] in [ordinary,protected,grouped] for e in events)
        # Batch boundary: use this test realm only and admin-owned synthetic mock fixtures.
        for index in range(20):
            name = 'mock-'+hashlib.sha256(('batch-'+str(index)).encode()).hexdigest()
            request('POST', realm+'/users', dict(username=name, firstName='Mock', lastName='google',
                email=name[5:]+'@example.invalid', enabled=True, attributes={'platformMock':['true']}), kc, expected=201)
        plan = request('GET', preview_url, token=admin)
        assert len(plan['items']) == 20 and plan['hasMore']
        result = request('POST', reset, dict(userIds=[u['id'] for u in plan['items']], revision=plan['revision']), admin)
        assert result['deleted'] == 20 and result['failed'] == 0
        plan = request('GET', preview_url, token=admin)
        assert len(plan['items']) == 1 and not plan['hasMore']
        request('POST', reset, dict(userIds=[u['id'] for u in plan['items']], revision=plan['revision']), admin)
        assert request('GET', preview_url, token=admin)['items'] == []
        mock(env, 'browser-target')
        # Suspension rejects both endpoints; reactivate only this new test project for browser use.
        project_url = api+'/projects/'+projects[0]['id']
        suspended = request('PUT', project_url, dict(name=projects[0]['name'], status='SUSPENDED', revision=projects[0]['revision']), admin)
        projects[0] = suspended
        request('GET', preview_url, token=admin, expected=409)
        request('POST', reset, payload, admin, expected=409)
        if args.keep_active:
            projects[0] = request('PUT', project_url, dict(name=suspended['name'], status='ACTIVE', revision=suspended['revision']), admin)
        passed = True
        print('PASS reset: preview, auth boundary, DEV/realm isolation, marker/role/group protection, stale targets, online/offline logout, concurrent replay, audit, 20-user batches, suspension')
        print(json.dumps({'projectId':projects[0]['id'], 'projectCode':projects[0]['code'], 'environmentId':env['id']}, ensure_ascii=False))
    finally:
        for key in keys: request('DELETE',api+'/credentials/'+key['id'],token=admin,expected=204)
        for project in projects:
            if not (passed and args.keep_active) and project['status'] != 'SUSPENDED':
                request('PUT', api+'/projects/'+project['id'], dict(name=project['name'], status='SUSPENDED', revision=project['revision']), admin)


if __name__ == '__main__': main()
