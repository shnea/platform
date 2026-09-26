"""DEV-only Job HTTP/scheduler check. Creates one owned fixture and suspends it afterwards."""
import importlib.util
import json
import os
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from jsonschema import Draft202012Validator, FormatChecker

loader = importlib.util.spec_from_file_location('contracts', Path(__file__).with_name('check-openapi.py'))
contracts = importlib.util.module_from_spec(loader)
loader.loader.exec_module(contracts)
call = contracts.call
A = '/api/v1/admin'


def main():
    config, _ = call('GET', '/api/v1/config')
    assert config['mode'] == 'dev', 'DEV stack required'
    missing = str(uuid.uuid4())
    for method, path in [('GET', A+'/jobs'), ('GET', A+'/jobs/'+missing),
                         ('POST', A+'/environments/'+missing+'/provision-jobs'),
                         ('POST', A+'/jobs/'+missing+'/cancel'), ('POST', A+'/jobs/'+missing+'/retry')]:
        call(method, path, expected=401)
    form = {'Content-Type': 'application/x-www-form-urlencoded'}
    session, _ = call('POST', contracts.OIDC+'/token', form, urlencode(dict(grant_type='password',
        client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD'])).encode())
    headers = {'Authorization': 'Bearer '+session['access_token'], 'Content-Type': 'application/json'}
    project = None
    try:
        spec, _ = call('GET', A+'/openapi', headers)

        def verify(model, value):
            Draft202012Validator({'$ref': '#/components/schemas/'+model, 'components': spec['components']},
                                 format_checker=FormatChecker()).validate(value)

        def error(method, path, status, code):
            body, hdr = call(method, path, headers, expected=status)
            verify('Problem', body)
            assert body['code'] == code and body['requestId'] == hdr['X-Request-ID']

        error('GET', A+'/jobs?limit=0', 400, 'INVALID_PAGINATION')
        error('GET', A+'/jobs?state=unknown', 400, 'INVALID_REQUEST')
        error('GET', A+'/jobs/'+missing, 404, 'RESOURCE_NOT_FOUND')
        error('POST', A+'/environments/'+missing+'/provision-jobs', 404, 'RESOURCE_NOT_FOUND')
        code = 'job-check-'+uuid.uuid4().hex[:10]
        project, _ = call('POST', A+'/projects', headers, json.dumps({'code': code, 'name': 'Job API 검증'}).encode(), 201)
        env, _ = call('POST', A+'/projects/'+project['id']+'/environments', headers, json.dumps(dict(
            code='dev', kind='DEV', registrationAllowed=False, redirectUris=['http://localhost:30140/callback'])).encode(), 201)
        assert env['state'] == 'READY', 'Fixture Keycloak provisioning failed'
        job, hdr = call('POST', A+'/environments/'+env['id']+'/provision-jobs', headers, expected=202)
        verify('Job', job)
        assert job['requestId'] == hdr['X-Request-ID'] and job['targetRevision'] == env['revision']
        path = A+'/jobs/'+job['id']
        deadline = time.monotonic()+25
        while True:
            detail, _ = call('GET', path, headers)
            verify('JobDetail', detail)
            if detail['job']['state'] in ('SUCCEEDED', 'FAILED', 'CANCELLED'): break
            assert time.monotonic() < deadline, 'Scheduler did not complete the job'
            time.sleep(0.3)
        assert detail['job']['state'] == 'SUCCEEDED'
        assert [a['state'] for a in detail['attempts']] == ['SUCCEEDED']
        assert detail['job']['requestId'] == job['requestId'] and detail['job']['completedAt']
        rows, _ = call('GET', A+'/jobs?'+urlencode(dict(environmentId=env['id'], state='SUCCEEDED')), headers)
        assert [r['id'] for r in rows] == [job['id']]
        for row in rows: verify('Job', row)
        error('POST', path+'/cancel', 409, 'JOB_NOT_CANCELLABLE')
        error('POST', path+'/retry', 409, 'JOB_NOT_RETRYABLE')
        events, _ = call('GET', A+'/audit-events?limit=100', headers)
        actions = {e['action'] for e in events if e['target_id'] == job['id']}
        assert {'job.queued', 'job.started', 'job.succeeded'} <= actions
        print('PASS Job 202, scheduler success, detail/history schemas, request ID, filtering, audit, 400/401/404/409')
        print('FIXTURE '+code)
    finally:
        try:
            if project:
                # This script owns the fixture; it has no members or API keys and must remain disabled afterwards.
                call('PUT', A+'/projects/'+project['id'], headers, json.dumps(dict(name=project['name'],
                    status='SUSPENDED', revision=project['revision'])).encode())
        finally:
            call('POST', contracts.OIDC+'/logout', form, urlencode(dict(client_id='platform-admin-cli',
                refresh_token=session['refresh_token'])).encode(), 204)
    print('PASS own fixture suspended and admin session ended; no external email sent')


if __name__ == '__main__':
    main()
