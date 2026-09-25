"""Identity email integration. DEV only, no external email. Optional browser fixture stays local."""
import argparse
import importlib.util
import json
import os
import re
import secrets
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

spec = importlib.util.spec_from_file_location('members', Path(__file__).with_name('check-members.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
request, base, identity = m.request, m.base, m.identity

def internal(method, url, data=None, key=True, expected=200):
    headers = {'X-Platform-Mail-Key': os.environ['PLATFORM_MAIL_SECRET']} if key else {}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    try:
        with urlopen(Request(url, data=None if data is None else json.dumps(data).encode(), method=method, headers=headers), timeout=40) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (method, url, status, expected)
    return json.loads(body) if body and status < 400 else None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', help='Write successful fixture to an ignored local mount and keep it active for browser checks')
    parser.add_argument('--cleanup', help='Suspend projects from a previously written fixture')
    args = parser.parse_args()
    admin = request('POST', identity+'/realms/platform-admin-dev/protocol/openid-connect/token', dict(
        grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD']), form=True)['access_token']
    def kc():
        return request('POST', identity+'/realms/master/protocol/openid-connect/token', dict(grant_type='client_credentials',
            client_id='platform-provisioner-dev', client_secret=os.environ['KEYCLOAK_PROVISIONER_SECRET']), form=True)['access_token']
    def suspend(projects):
        current = request('GET', base+'/projects?limit=100', token=admin)
        for project in projects:
            assert re.fullmatch(r'email-check-[a-f0-9]{10}', project['code'])
            row = next(row for row in current if row['id'] == project['id'] and row['code'] == project['code'])
            if row['status'] != 'SUSPENDED':
                request('PUT', base+'/projects/'+row['id'], dict(name=row['name'], status='SUSPENDED', revision=row['revision']), admin)
    if args.cleanup:
        suspend(json.loads(Path(args.cleanup).read_text())['projects'])
        print('PASS email fixtures suspended; accounts preserved')
        return
    projects, envs, keys = [], [], []
    passed = False
    try:
        for index in range(2):
            p = request('POST', base+'/projects', dict(code='email-check-'+uuid.uuid4().hex[:10], name='이메일 복구 검증 '+str(index+1)), admin, expected=201)
            projects.append(p)
            envs.append(request('POST', base+'/projects/'+p['id']+'/environments', dict(code='dev', kind='DEV', registrationAllowed=True,
                redirectUris=['https://platform.shnea.kr/']), admin, expected=201))
        env, other = envs
        eurl = base+'/environments/'+env['id']
        rurl = identity+'/admin/realms/'+env['realm']
        notify = 'http://notification-service:8080/internal/v1/email'
        internal('GET', notify+'/readiness', key=False, expected=403)
        assert internal('GET', notify+'/readiness') == dict(mode='dev', ready=True)
        internal('GET', 'http://project-service:8080/internal/v1/email/environments/'+env['id'], key=False, expected=403)
        internal('GET', 'http://nginx:8080/internal/v1/email/environments/'+env['id'], key=False, expected=404)
        request('GET', eurl+'/email-inbox', expected=401)
        key = request('POST', eurl+'/credentials', token=admin, expected=201)
        keys.append(key['id'])
        request('GET', eurl+'/email-inbox', key=key['apiKey'], expected=401)
        assert request('GET', eurl+'/email-inbox', token=admin) == []
        policy = request('GET', eurl+'/authentication-policy', token=admin)
        assert policy['emailActionsAvailable'] and policy['emailDelivery'] == 'MOCK'
        policy = request('PUT', eurl+'/authentication-policy', dict(loginWithEmail=True, verifyEmail=True, resetPasswordAllowed=True,
            passwordMinLength=12, revision=policy['revision']), admin)
        username, password = 'email-user', secrets.token_urlsafe(24)
        for item in envs:
            request('POST', identity+'/admin/realms/'+item['realm']+'/users', dict(username=username, email='email-user@example.invalid',
                firstName='이메일', lastName='검증', enabled=True, emailVerified=False,
                credentials=[dict(type='password', value=password, temporary=False)]), kc(), expected=201)
        user = request('GET', rurl+'/users?username='+username+'&exact=true', token=kc())[0]
        request('PUT', rurl+'/users/'+user['id']+'/send-verify-email', token=kc(), expected=204)
        inbox = request('GET', eurl+'/email-inbox', token=admin)
        assert len(inbox) == 1 and 'login-actions/action-token' in inbox[0]['textBody']
        assert set(inbox[0]) == {'id','recipient','subject','textBody','createdAt'}
        assert request('GET', base+'/environments/'+other['id']+'/email-inbox', token=admin) == []
        # Same request ID is safe under concurrent retry; changed payload is rejected.
        message = dict(id=str(uuid.uuid4()), environmentId=other['id'], realm=other['realm'], recipient='fixture@example.invalid',
            subject='수신함 안전성 <script>', textBody='<script>window.injected=true</script>\n모의 본문', htmlBody='<script>window.injected=true</script>')
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: internal('POST', notify, message), range(2)))
        assert results[0] == results[1] and results[0]['state'] == 'MOCK'
        internal('POST', notify, dict(message, subject='changed'), expected=409)
        internal('POST', notify, dict(message, id=str(uuid.uuid4()), realm=env['realm']), expected=403)
        internal('POST', notify, dict(message, id=str(uuid.uuid4()), recipient='bad\r\n@example.invalid'), expected=400)
        assert len(request('GET', base+'/environments/'+other['id']+'/email-inbox', token=admin)) == 1
        # DEV deployment cannot send for PROD, even with valid NCP credentials present.
        prod = request('POST', base+'/projects/'+projects[1]['id']+'/environments', dict(code='prod', kind='PROD', registrationAllowed=False,
            redirectUris=['https://platform.shnea.kr/']), admin, expected=201)
        purl = base+'/environments/'+prod['id']
        unavailable = request('GET', purl+'/authentication-policy', token=admin)
        assert not unavailable['emailActionsAvailable'] and unavailable['emailDelivery'] == 'UNAVAILABLE'
        request('PUT', purl+'/authentication-policy', dict(loginWithEmail=True, verifyEmail=True, resetPasswordAllowed=True,
            passwordMinLength=12, revision=unavailable['revision']), admin, expected=400)
        internal('POST', notify, dict(message, id=str(uuid.uuid4()), environmentId=prod['id'], realm=prod['realm']), expected=403)
        request('GET', purl+'/email-inbox', token=admin, expected=404)
        # Per-environment rate limit under the same transaction lock.
        for _ in range(19): internal('POST', notify, dict(message, id=str(uuid.uuid4())))
        internal('POST', notify, dict(message, id=str(uuid.uuid4())), expected=429)
        suspend([projects[1]])
        internal('POST', notify, dict(message, id=str(uuid.uuid4())), expected=403)
        if args.fixture:
            Path(args.fixture).write_text(json.dumps(dict(projects=projects, env=env, other=other, userId=user['id'], username=username, password=password)), encoding='utf-8')
        passed = True
        print('PASS email: real Keycloak mail SPI, verification link, environment isolation, DEV/PROD gates, internal auth, admin inbox, replay/conflict, concurrent deduplication, rate limit, suspended project')
    finally:
        for key in keys: request('DELETE', base+'/credentials/'+key, token=admin, expected=204)
        if not (passed and args.fixture): suspend(projects)

if __name__ == '__main__': main()
