"""Native administrator MFA policy and operator-only emergency recovery."""
import argparse
import json
import os
import secrets
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlencode

from identity_admin import connect 

LEGACY_FLOWS = ('platform-admin-mfa-v1', 'platform-admin-mfa-v2')
FLOW = 'platform-admin-mfa-v3-optional'
DESCRIPTION = 'Platform managed administrator optional MFA v3'
FORMS = FLOW+'-forms'
FACTOR = FLOW+'-factor'
ACTIONS = ('CONFIGURE_TOTP', 'CONFIGURE_RECOVERY_AUTHN_CODES')


def configure(api, realm, production):
    """Build an independent native flow before binding it. Existing credentials survive."""
    if realm not in ('platform-admin-dev', 'platform-admin-prod'):
        raise SystemExit('플랫폼 관리자 인증 영역에서만 MFA를 설정할 수 있습니다.')
    root = '/'+realm
    current = api('GET', root)
    if current.get('browserFlow') not in ('browser', *LEGACY_FLOWS, FLOW):
        raise SystemExit('별도 관리자 인증 흐름이 있습니다. 기존 설정을 검토해 주세요.')
    flows = {f['alias']: f for f in api('GET', root+'/authentication/flows')}
    if FLOW in flows and (flows[FLOW].get('builtIn')
            or flows[FLOW].get('description') != DESCRIPTION):
        raise SystemExit('같은 이름의 별도 인증 흐름이 있습니다. 기존 설정을 검토해 주세요.')
    if FLOW not in flows:
        api('POST', root+'/authentication/flows', dict(alias=FLOW, providerId='basic-flow',
            topLevel=True, builtIn=False, description=DESCRIPTION))
    # The server requires a second factor whenever OTP OR recovery codes exist.
    # A request parameter / unchecked browser control can never bypass it.
    definitions = {
        FLOW: [('auth-cookie', False, 'ALTERNATIVE'), (FORMS, True, 'ALTERNATIVE')],
        FORMS: [('auth-username-password-form', False, 'REQUIRED'), (FACTOR, True, 'CONDITIONAL')],
        FACTOR: [('conditional-user-configured', False, 'REQUIRED'),
                 ('auth-otp-form', False, 'ALTERNATIVE'), ('auth-recovery-authn-code-form', False, 'ALTERNATIVE')],
    }
    for alias, steps in definitions.items():
        endpoint = root+'/authentication/flows/'+alias+'/executions'
        existing = [e for e in api('GET', endpoint) if e['level'] == 0]
        def matches(execution, provider, subflow):
            return execution.get('displayName') == provider if subflow else execution.get('providerId') == provider
        for execution in existing:
            if not any(matches(execution, p, sub) for p, sub, _ in steps):
                raise SystemExit('예상하지 못한 관리자 인증 단계가 있습니다. 설정을 검토해 주세요.')
        for index, (provider, subflow, requirement) in enumerate(steps):
            matching = [e for e in existing if matches(e, provider, subflow)]
            if len(matching) > 1:
                raise SystemExit('중복된 관리자 인증 단계가 있습니다. 설정을 검토해 주세요.')
            if not matching:
                api('POST', endpoint+('/flow' if subflow else '/execution'),
                    dict(alias=provider, type='basic-flow', provider='basic-flow', description=DESCRIPTION)
                    if subflow else dict(provider=provider))
                matching = [e for e in api('GET', endpoint) if e['level'] == 0 and matches(e, provider, subflow)]
            execution = matching[0]
            execution['requirement'] = requirement
            execution['priority'] = (index+1)*10
            api('PUT', endpoint, execution)
        if len(existing) > len(steps):
            raise SystemExit('예상하지 못한 관리자 인증 단계가 있습니다. 설정을 검토해 주세요.')
    actions = api('GET', root+'/authentication/required-actions')
    if not {*ACTIONS, 'delete_credential'}.issubset(a['alias'] for a in actions):
        raise SystemExit('필요한 Keycloak 인증 앱·복구 코드 기능을 찾을 수 없습니다.')
    for action in actions:
        if action['alias'] in ACTIONS:
            action.update(enabled=True, defaultAction=False)
            if action['alias'] == 'CONFIGURE_TOTP':
                action['config'] = {**action.get('config', {}), 'add-recovery-codes': 'true'}
            api('PUT', root+'/authentication/required-actions/'+action['alias'], action)
        elif action['alias'] == 'UPDATE_PASSWORD':
            action.update(enabled=True, priority=53)
            api('PUT', root+'/authentication/required-actions/'+action['alias'], action)
        elif action['alias'] == 'delete_credential':
            action.update(enabled=True, defaultAction=False)
            action['config'] = {**action.get('config', {}), 'max_auth_age': '0'}
            api('PUT', root+'/authentication/required-actions/'+action['alias'], action)
    event_config = api('GET', root+'/events/config')
    event_config.update(adminEventsEnabled=True, adminEventsDetailsEnabled=False)
    api('PUT', root+'/events/config', event_config)
    if production:
        # No password-only CLI path in the production administrator realm.
        for client in api('GET', root+'/clients'):
            if client.get('directAccessGrantsEnabled'):
                api('PUT', root+'/clients/'+client['id'], dict(directAccessGrantsEnabled=False))
    if current.get('browserFlow') != FLOW:
        # Retire old forced enrollment only for users who never registered a factor.
        # An operator recovery requiring a new password must still finish enrollment.
        offset = 0
        while True:
            users = api('GET', root+'/users?first='+str(offset)+'&max=100')
            for user in users:
                user_path = root+'/users/'+quote(user['id'], safe='')
                credentials = {c['type'] for c in api('GET', user_path+'/credentials')}
                required = user.get('requiredActions', [])
                if not credentials.intersection(('otp', 'recovery-authn-codes')) and 'UPDATE_PASSWORD' not in required:
                    remaining = [action for action in required if action not in ACTIONS]
                    if remaining != required:
                        api('PUT', user_path, dict(requiredActions=remaining))
                end_sessions(api, root, user_path)
            if len(users) < 100:
                break
            offset += 100
        api('POST', root+'/logout-all')
    api('PUT', root, dict(browserFlow=FLOW))
    print('PASS 관리자 MFA 설정:', realm, '계정별 선택; 등록된 인증 수단은 로그인 시 필수')


def end_sessions(api, root, user_path):
    api('POST', user_path+'/logout')
    for consent in api('GET', user_path+'/consents'):
        for grant in consent.get('additionalGrants', []):
            if grant.get('key') != 'Offline Token':
                continue
            for session in api('GET', user_path+'/offline-sessions/'+quote(grant['client'], safe='')):
                api('DELETE', root+'/sessions/'+quote(session['id'], safe='')+'?isOffline=true', allowed=(404,))


def recover(api, realm, username, reason, password_file):
    if realm not in ('platform-admin-dev', 'platform-admin-prod'):
        raise SystemExit('플랫폼 관리자 인증 영역만 복구할 수 있습니다.')
    root = '/'+realm
    users = api('GET', root+'/users?'+urlencode(dict(username=username, exact='true')))
    if len(users) != 1 or users[0].get('username') != username or users[0].get('serviceAccountClientId'):
        raise SystemExit('복구 대상 관리자 계정을 정확히 확인해 주세요.')
    user = users[0]
    user_path = root+'/users/'+quote(user['id'], safe='')
    roles = api('GET', user_path+'/role-mappings/realm/composite')
    if not any(role['name'] == 'platform-admin' for role in roles):
        raise SystemExit('플랫폼 관리자 역할이 없는 계정은 이 명령으로 복구할 수 없습니다.')
    if api('GET', root).get('browserFlow') not in (*LEGACY_FLOWS, FLOW):
        raise SystemExit('관리 대상 MFA 흐름을 적용한 관리자 영역에서만 긴급 복구할 수 있습니다.')
    # Write a new private file before touching the account. Never print the password.
    password = secrets.token_urlsafe(32)
    descriptor = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
        output.write(password+'\n')
    event_config = api('GET', root+'/events/config')
    event_config.update(adminEventsEnabled=True, adminEventsDetailsEnabled=False)
    api('PUT', root+'/events/config', event_config)
    api('PUT', user_path, dict(enabled=False))
    completed = False
    try:
        end_sessions(api, root, user_path)
        for credential in api('GET', user_path+'/credentials'):
            if credential['type'] in ('otp', 'recovery-authn-codes', 'webauthn', 'webauthn-passwordless'):
                api('DELETE', user_path+'/credentials/'+quote(credential['id'], safe=''))
        api('PUT', user_path+'/reset-password', dict(type='password', value=password, temporary=True))
        actions = list(dict.fromkeys([*user.get('requiredActions', []), 'UPDATE_PASSWORD', *ACTIONS]))
        api('PUT', user_path, dict(requiredActions=actions))
        end_sessions(api, root, user_path)
        api('DELETE', root+'/attack-detection/brute-force/users/'+quote(user['id'], safe=''))
        api('PUT', user_path, dict(enabled=True))
        completed = True
    finally:
        # On partial failure, keep login disabled. A retry uses a new output file.
        print(json.dumps(dict(event='admin_mfa_recovery', realm=realm, userId=user['id'],
            reason=reason, completed=completed, time=datetime.now(timezone.utc).isoformat()), ensure_ascii=False))
    print('복구 준비 완료. 임시 비밀번호 파일을 본인에게 안전하게 전달하고 사용 후 삭제해 주세요.')
    print('다음 로그인에서 비밀번호 변경·인증 앱 등록·복구 코드 보관을 완료해야 합니다.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('configure', 'recover'))
    parser.add_argument('--username')
    parser.add_argument('--reason')
    parser.add_argument('--password-file', type=Path)
    args = parser.parse_args()
    mode = os.environ.get('PLATFORM_MODE', 'prod')
    if mode not in ('dev', 'prod'):
        parser.error('PLATFORM_MODE는 dev 또는 prod여야 합니다.')
    if args.action == 'recover' and (not args.username or not args.password_file or not args.reason
            or not 5 <= len(args.reason) <= 200 or any(ord(c) < 32 for c in args.reason)):
        parser.error('복구 대상 --username, 사유 --reason(5~200자), 새 --password-file 경로가 필요합니다.')
    api = connect()
    realm = 'platform-admin-'+mode
    if args.action == 'configure':
        configure(api, realm, mode == 'prod')
    else:
        recover(api, realm, args.username, args.reason, args.password_file)


if __name__ == '__main__':
    main()
