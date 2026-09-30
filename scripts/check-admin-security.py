"""Offline failure checks for operator recovery; no real users or external calls."""
import contextlib
import io
import tempfile
from copy import deepcopy
from pathlib import Path

from admin_security import ACTIONS, FACTOR, FLOW, FORMS, configure, recover


def check_policy():
    """Exercise initial migration and repeat setup without changing credentials."""
    root = '/platform-admin-prod'
    state = dict(browserFlow='platform-admin-mfa-v2')
    flows, executions, writes = {}, {}, []
    users = [dict(id='new', requiredActions=['VERIFY_EMAIL', *ACTIONS]),
             dict(id='registered', requiredActions=[]),
             dict(id='recovering', requiredActions=['UPDATE_PASSWORD', *ACTIONS])]
    credentials = {'new': [], 'registered': [dict(type='otp'), dict(type='recovery-authn-codes')], 'recovering': []}
    original = deepcopy(credentials)
    actions = [dict(alias=action) for action in (*ACTIONS, 'UPDATE_PASSWORD', 'delete_credential')]
    def api(method, path, body=None, allowed=()):
        if method == 'GET':
            if path == root: return deepcopy(state)
            if path.endswith('/authentication/flows'): return list(flows.values())
            if path.endswith('/executions'): return deepcopy(executions[path])
            if path.endswith('/required-actions'): return deepcopy(actions)
            if path.endswith('/events/config'): return dict(eventsEnabled=True)
            if path.endswith('/clients'): return [dict(id='cli', directAccessGrantsEnabled=True)]
            if '/users?' in path: return deepcopy(users)
            if path.endswith('/credentials'): return deepcopy(credentials[path.split('/')[-2]])
            if path.endswith('/consents'): return []
            raise AssertionError(path)
        writes.append((method, path, deepcopy(body)))
        assert '/credentials/' not in path, 'Setup must not delete credentials'
        if path == root and method == 'PUT': state.update(body)
        elif path.endswith('/authentication/flows') and method == 'POST':
            flows[body['alias']] = body
            executions[root+'/authentication/flows/'+body['alias']+'/executions'] = []
        elif path.endswith(('/executions/flow', '/executions/execution')):
            parent = path.rsplit('/', 1)[0]
            entry = dict(level=0, id=str(len(writes)))
            if 'alias' in body:
                entry['displayName'] = body['alias']
                executions[root+'/authentication/flows/'+body['alias']+'/executions'] = []
            else: entry['providerId'] = body['provider']
            executions[parent].append(entry)
        elif path.endswith('/executions'):
            executions[path] = [body if e['id'] == body['id'] else e for e in executions[path]]
        elif '/users/' in path and method == 'PUT':
            next(u for u in users if u['id'] == path.split('/')[-1]).update(body)
    configure(api, 'platform-admin-prod', True)
    assert state['browserFlow'] == FLOW
    forms = executions[root+'/authentication/flows/'+FORMS+'/executions']
    factor = executions[root+'/authentication/flows/'+FACTOR+'/executions']
    assert any(e.get('displayName') == FACTOR and e['requirement'] == 'CONDITIONAL' for e in forms)
    assert {e['providerId']: e['requirement'] for e in factor} == {
        'conditional-user-configured': 'REQUIRED', 'auth-otp-form': 'ALTERNATIVE',
        'auth-recovery-authn-code-form': 'ALTERNATIVE'}
    assert users[0]['requiredActions'] == ['VERIFY_EMAIL']
    assert users[2]['requiredActions'] == ['UPDATE_PASSWORD', *ACTIONS]
    for action in ACTIONS:
        assert any(p.endswith('/'+action) and b['enabled'] and not b['defaultAction'] for _, p, b in writes if b)
    assert any(p.endswith('/delete_credential') and b['config']['max_auth_age'] == '0' for _, p, b in writes if b)
    assert any(p.endswith('/clients/cli') and b == dict(directAccessGrantsEnabled=False) for _, p, b in writes)
    writes.clear()
    configure(api, 'platform-admin-prod', True)
    assert not any('/users/' in p or p.endswith('/logout-all') for _, p, _ in writes), 'Repeated setup must preserve sessions and user choices'
    assert credentials == original
    print('PASS optional MFA: conditional factors, migration, recovery preservation, reauthentication, direct-grant block, idempotency')


def run():
    for failure in ('logout', 'reset-password', 'none'):
        writes = []
        state = {'enabled': True}
        def api(method, path, body=None, allowed=()):
            if method == 'GET':
                if '/users?' in path:
                    return [dict(id='test-id', username='operator-test', requiredActions=['VERIFY_EMAIL'])]
                if path.endswith('/composite'):
                    return [dict(name='platform-admin')]
                if path.endswith('/credentials'):
                    return [dict(id='test-otp', type='otp'), dict(id='test-backup', type='recovery-authn-codes')]
                if path.endswith('/consents'):
                    return []
                if path.endswith('/events/config'):
                    return dict(eventsEnabled=True, eventsExpiration=86400)
                return dict(browserFlow=FLOW)
            writes.append((method, path, body))
            if path.endswith('/'+failure):
                raise SystemExit('simulated failure')
            if body and 'enabled' in body:
                state['enabled'] = body['enabled']
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as capture:
            password_file = Path(directory)/'password'
            try:
                recover(api, 'platform-admin-prod', 'operator-test', '복구 실패 처리 검증', password_file)
            except SystemExit:
                assert failure != 'none'
            assert state['enabled'] == (failure == 'none'), 'Partial recovery must not reopen login'
            assert password_file.read_text().strip() not in capture.getvalue(), 'Password must not be logged'
            assert any(body and body.get('eventsEnabled') is True for _, _, body in writes)
            if failure == 'none':
                assert any(body and set(body.get('requiredActions', [])) == {
                    'VERIFY_EMAIL', 'UPDATE_PASSWORD', 'CONFIGURE_TOTP', 'CONFIGURE_RECOVERY_AUTHN_CODES'} for _, _, body in writes)
                assert any('/attack-detection/' in path for _, path, _ in writes)
            count = len(writes)
            try:
                recover(api, 'platform-admin-prod', 'operator-test', '기존 파일 보존 검증', password_file)
                raise AssertionError('Existing output file was overwritten')
            except FileExistsError:
                assert len(writes) == count, 'Existing file rejection must happen before account writes'
    print('PASS admin recovery: fail closed after logout/password failure, password non-disclosure, existing file preserved, required actions and event settings preserved')


if __name__ == '__main__':
    check_policy()
    run()
