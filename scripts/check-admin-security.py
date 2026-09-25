"""Offline failure checks for operator recovery; no real users or external calls."""
import contextlib
import io
import tempfile
from pathlib import Path

from admin_security import FLOW, recover


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
    run()
