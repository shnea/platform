"""Validate OpenAPI and real read-only responses; no external email or business mutation."""
import json
import os
import re
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode
from pathlib import Path
from openapi_spec_validator import validate
from jsonschema import Draft202012Validator, FormatChecker

BASE = 'http://nginx:8080'
OIDC = 'http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect'


def call(method, path, headers=None, data=None, expected=200):
    try:
        with urlopen(Request(path if path.startswith('http') else BASE+path, data=data,
                             headers=headers or {}, method=method), timeout=30) as response:
            status, hdr, raw = response.status, response.headers, response.read()
    except HTTPError as error:
        status, hdr, raw = error.code, error.headers, error.read()
    assert status == expected, (method, path.split('?')[0], status, expected)
    return json.loads(raw) if raw else None, hdr


def main():
    config, _ = call('GET', '/api/v1/config')
    assert config['mode'] == 'dev'
    call('GET', '/api/v1/admin/openapi', expected=401)
    fields = dict(grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD'])
    form = {'Content-Type':'application/x-www-form-urlencoded'}
    session, _ = call('POST', OIDC+'/token', form, urlencode(fields).encode())
    headers = {'Authorization':'Bearer '+session['access_token']}
    try:
        spec, _ = call('GET', '/api/v1/admin/openapi', headers)
        validate(spec)
        assert spec == json.loads(Path('/contracts/project.json').read_text())
        assert len(spec['paths']) == 34 and sum(len(v) for v in spec['paths'].values()) == 39
        assert not any(path.startswith('/internal') for path in spec['paths'])

        def verify(schema, value):
            Draft202012Validator(dict(schema, components=spec['components']), format_checker=FormatChecker()).validate(value)

        def get(path, template=None):
            value, hdr = call('GET', path, headers)
            response = spec['paths'][template or path.split('?')[0]]['get']['responses']['200']
            verify(response['content']['application/json']['schema'], value)
            print('PASS schema', template or path.split('?')[0])
            return value, hdr

        get('/api/v1/config')
        get('/api/v1/admin/jobs')
        projects, _ = get('/api/v1/admin/projects')
        get('/api/v1/admin/audit-events')
        for project in projects:
            environments, _ = get('/api/v1/admin/projects/'+project['id']+'/environments', '/api/v1/admin/projects/{id}/environments')
            env = next((e for e in environments if e['kind']=='DEV' and e['state']=='READY'), None)
            if not env: continue
            prefix = '/api/v1/admin/environments/'+env['id']
            template = '/api/v1/admin/environments/{id}'
            for suffix in ['/credentials','/credential-scopes','/authentication-policy','/social-providers','/users','/email-inbox']:
                _, hdr = get(prefix+suffix, template+suffix)
                if suffix in ['/authentication-policy','/email-inbox']:
                    print('TRACE project-to-notification requestId='+hdr['X-Request-ID'])
            break
        else:
            raise AssertionError('READY DEV fixture needed to check service tracing')

        endpoint='http://notification-service:8080/internal/v1/email'
        body, hdr = call('GET', endpoint+'/readiness', expected=403)
        verify({'$ref':'#/components/schemas/Problem'}, body)
        assert body['code']=='EMAIL_ACCESS_DENIED' and body['requestId']==hdr['X-Request-ID']
        internal={'X-Platform-Mail-Key':os.environ['PLATFORM_MAIL_SECRET'], 'Content-Type':'application/json', 'X-Request-ID':uuid.uuid4().hex}
        body, hdr = call('POST', endpoint, internal, b'{"recipient":"must-not-echo-secret"}', 400)
        verify({'$ref':'#/components/schemas/Problem'}, body)
        assert body['requestId']==internal['X-Request-ID'] and 'must-not-echo-secret' not in json.dumps(body)
        # A nonexistent environment rejects before persistence or provider sending; exercises the reverse hop.
        missing=uuid.uuid4()
        message=dict(id=str(uuid.uuid4()),environmentId=str(missing),realm='p-'+missing.hex,
                     recipient='contract@example.test',subject='contract',textBody='contract',htmlBody='')
        body, _ = call('POST',endpoint,internal,json.dumps(message).encode(),503)
        assert body['code']=='EMAIL_CONTEXT_UNAVAILABLE' and body['requestId']==internal['X-Request-ID']
        print('TRACE notification-to-project requestId='+body['requestId'])
        print('PASS internal errors, no external sending and bidirectional trace headers')
        body, hdr = call('POST','/api/v1/admin/projects',dict(headers,**{'Content-Type':'application/json'}),b'x'*(1024*1024+1),413)
        verify({'$ref':'#/components/schemas/Problem'}, body)
        assert body['requestId']==hdr['X-Request-ID'] and body['code']=='PAYLOAD_TOO_LARGE'
        print('PASS gateway 413 problem')
    finally:
        call('POST',OIDC+'/logout',form,urlencode(dict(client_id='platform-admin-cli',refresh_token=session['refresh_token'])).encode(),204)
    print('PASS OpenAPI validation, real response schemas and own admin session logout')


if __name__=='__main__': main()
