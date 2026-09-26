"""Opt-in DEV file flow. Creates only its own projects; revokes keys, deletes uploads and suspends fixtures.

Use --phase start, restart only file-service, then --phase finish to check persisted resume.
The temporary state contains test-only credentials and must be mounted from ignored output/playwright.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode
from openapi_spec_validator import validate
from jsonschema import Draft202012Validator, FormatChecker

BASE = os.environ.get('FILE_CHECK_BASE', 'http://nginx:8080')
OIDC = 'http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect'
STATE = Path('/state/file-check.json')
CHUNK = b'file-check\x00\xff<script>safe download</script>\n' * 180000
PAYLOAD = CHUNK + b'resumed-after-restart'
ADMIN = '/api/v1/admin'
state = {}
token = None


def call(method, path, data=None, key=None, auth=False, expected=200, headers=None, raw=False):
    hdr = dict(headers or {})
    if key: hdr['X-Platform-Key'] = key
    if auth: hdr['Authorization'] = 'Bearer ' + token
    if isinstance(data, dict):
        data = json.dumps(data).encode(); hdr['Content-Type'] = 'application/json'
    try:
        with urlopen(Request(path if path.startswith('http') else BASE+path, data=data, headers=hdr, method=method), timeout=330) as response:
            status, response_headers, body = response.status, response.headers, response.read()
    except HTTPError as error:
        status, response_headers, body = error.code, error.headers, error.read()
    assert status == expected, (method, path.split('?')[0], status, expected)
    return (body if raw else json.loads(body) if body else None), response_headers


def login(refresh=None):
    global token
    fields = dict(grant_type='refresh_token', client_id='platform-admin-cli', refresh_token=refresh) if refresh else dict(
        grant_type='password', client_id='platform-admin-cli', username='admin', password=os.environ['PLATFORM_ADMIN_PASSWORD'])
    session, _ = call('POST', OIDC+'/token', urlencode(fields).encode(), headers={'Content-Type':'application/x-www-form-urlencoded'})
    token = session['access_token']; state['refresh'] = session['refresh_token']


def save():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state), encoding='utf-8')
    STATE.chmod(0o600)


def key(environment, scopes):
    expiry = (datetime.now(timezone.utc)+timedelta(minutes=30)).isoformat()
    result, _ = call('POST', ADMIN+'/environments/'+environment['id']+'/credentials', dict(scopes=scopes, expiresAt=expiry), auth=True, expected=201)
    state['keys'].append(result); save()
    return result


def chunk(upload_id, key_value, offset, payload, expected=200, hash_value=None):
    return call('PATCH', '/api/v1/files/uploads/'+upload_id, payload, key=key_value, expected=expected,
                headers={'Content-Type':'application/octet-stream','Upload-Offset':str(offset),
                         'X-Chunk-SHA256':hash_value or hashlib.sha256(payload).hexdigest()})[0]


def create_file(key_value, payload, name='file-check.html', **extra):
    body = dict(requestId=str(uuid.uuid4()), originalName=name, size=len(payload), sha256=hashlib.sha256(payload).hexdigest(), **extra)
    result, _ = call('POST', '/api/v1/files/uploads', body, key=key_value, expected=201)
    state['uploads'].append(dict(id=result['uploadId'], key=key_value)); save()
    return result, body


def start():
    assert not STATE.exists(), 'Existing fixture state: finish/cleanup it before starting again.'
    config, _ = call('GET', '/api/v1/config'); assert config['mode'] == 'dev'
    state.update(projects=[], keys=[], uploads=[], fixture='files-check-'+secrets.token_hex(5))
    login(); save()
    environments = []
    for index in range(2):
        project, _ = call('POST', ADMIN+'/projects', dict(code=state['fixture']+'-'+str(index), name='File API verification'), auth=True, expected=201)
        state['projects'].append(project); save()
        env, _ = call('POST', ADMIN+'/projects/'+project['id']+'/environments', dict(code='dev', kind='DEV', registrationAllowed=False,
                    redirectUris=['http://localhost:3000/callback']), auth=True, expected=201)
        assert env['state'] == 'READY'; environments.append(env)
    prod, _ = call('POST', ADMIN+'/projects/'+state['projects'][0]['id']+'/environments', dict(code='prod', kind='PROD', registrationAllowed=False,
                    redirectUris=['https://example.invalid/callback']), auth=True, expected=201)
    assert prod['state'] == 'READY'; environments.append(prod)
    full = key(environments[0], ['files:read','files:write','files:delete'])
    foreign = key(environments[1], ['files:read','files:write','files:delete'])
    prod_key = key(prod, ['files:read','files:write','files:delete'])
    read = key(environments[0], ['files:read'])
    old = key(environments[0], ['integration:read'])
    state.update(full=full, foreign=foreign, prod_key=prod_key, read=read, old=old)
    spec, _ = call('GET', '/api/v1/files/openapi', key=full['apiKey']); validate(spec)
    assert sum(len(v) for v in spec['paths'].values()) == 41
    state['environments'] = environments; save()
    call('GET', '/api/v1/files', expected=401)
    call('GET', '/api/v1/files', key=old['apiKey'], expected=403)
    upload, body = create_file(full['apiKey'], PAYLOAD)
    again, _ = call('POST', '/api/v1/files/uploads', body, key=full['apiKey'], expected=201)
    assert again == upload
    state.update(upload=upload, create=body); save()
    call('POST', '/api/v1/files/uploads', body, key=read['apiKey'], expected=403)
    chunk(upload['uploadId'], full['apiKey'], 0, CHUNK)
    chunk(upload['uploadId'], full['apiKey'], 0, CHUNK, expected=409)
    for other in [foreign,prod_key]:
        call('GET', '/api/v1/files/uploads/'+upload['uploadId'], key=other['apiKey'], expected=404)
    listing, _ = call('GET','/api/v1/files',key=full['apiKey']); assert listing == []
    print('PASS start: scoped keys, isolated project/DEV/PROD, idempotent creation, chunk and duplicate offset')
    print('Pending persisted resume: restart only file-service, then --phase finish.')


def finish():
    state.update(json.loads(STATE.read_text(encoding='utf-8'))); login(state['refresh']); save()
    full=state['full']['apiKey']; upload=state['upload']['uploadId']
    check_duplicates()
    admin_path='/api/v1/files/admin/environments/'+state['environments'][0]['id']
    call('GET',admin_path,expected=401)
    call('GET',admin_path,key=full,expected=401)
    call('GET',admin_path+'/uploads/'+upload,auth=True,expected=404)
    result, _ = call('GET','/api/v1/files/uploads/'+upload,key=full)
    assert result['receivedBytes'] == len(CHUNK)
    tail=PAYLOAD[len(CHUNK):]
    chunk(upload, full, len(CHUNK), tail, expected=422, hash_value='0'*64)
    result, _ = call('GET','/api/v1/files/uploads/'+upload,key=full); assert result['receivedBytes'] == len(CHUNK)
    chunk(upload,full,len(CHUNK),tail)
    file, _ = call('POST','/api/v1/files/uploads/'+upload+'/complete',key=full)
    again, _ = call('POST','/api/v1/files/uploads/'+upload+'/complete',key=full); assert again == file
    ticket,_=call('POST',admin_path+'/'+file['fileId']+'/download-ticket',auth=True)
    body,_=call('GET',ticket['downloadUrl'],raw=True); assert body == PAYLOAD
    call('GET',ticket['downloadUrl'],expected=404)
    call('POST','/api/v1/files/admin/environments/'+state['environments'][1]['id']+'/'+file['fileId']+'/download-ticket',auth=True,expected=404)
    spec, _ = call('GET','/api/v1/files/openapi',key=full)
    Draft202012Validator(dict(spec['components']['schemas']['FileInfo'],components=spec['components']),format_checker=FormatChecker()).validate(file)
    body, hdr = call('GET',file['downloadUrl'],raw=True)
    assert body == PAYLOAD and hdr['Cache-Control'] == 'no-store' and hdr['Content-Disposition'].startswith('attachment;')
    assert hdr['X-Content-Type-Options'] == 'nosniff' and hdr['Content-Type'] == 'application/octet-stream'
    listing, _ = call('GET','/api/v1/files',key=full); assert any(item['fileId']==file['fileId'] for item in listing)
    path='/api/v1/files/'+file['fileId']
    call('DELETE',path,key=state['read']['apiKey'],expected=403)
    call('PUT',path+'/visibility',dict(visibility='PRIVATE'),key=full)
    call('GET',file['downloadUrl'],expected=404)
    ticket,_=call('POST',admin_path+'/'+file['fileId']+'/download-ticket',auth=True)
    call('HEAD',ticket['downloadUrl'],raw=True,expected=405)
    body,_=call('GET',ticket['downloadUrl'],raw=True); assert body == PAYLOAD
    call('GET',ticket['downloadUrl'],expected=404)
    body, _ = call('GET',file['downloadUrl'],key=state['read']['apiKey'],raw=True); assert body == PAYLOAD
    for other in [state['foreign'],state['prod_key']]:
        call('GET',path,key=other['apiKey'],expected=404)
        call('GET',file['downloadUrl'],key=other['apiKey'],expected=404)
        call('DELETE',path,key=other['apiKey'],expected=404)
    call('DELETE',ADMIN+'/credentials/'+state['read']['id'],auth=True,expected=204)
    call('GET',file['downloadUrl'],key=state['read']['apiKey'],expected=401)
    call('PUT',path+'/visibility',dict(visibility='PUBLIC'),key=full)
    project=state['projects'][0]
    updated,_=call('PUT',ADMIN+'/projects/'+project['id'],dict(name=project['name'],status='SUSPENDED',revision=project['revision']),auth=True)
    state['projects'][0]=updated; save()
    call('GET',file['downloadUrl'],expected=404)
    call('GET','/api/v1/files',key=full,expected=401)
    updated,_=call('PUT',ADMIN+'/projects/'+project['id'],dict(name=project['name'],status='ACTIVE',revision=updated['revision']),auth=True)
    state['projects'][0]=updated; save()
    call('DELETE',path,key=full,expected=204); call('DELETE',path,key=full,expected=204)
    call('GET',file['downloadUrl'],expected=404)
    empty,_=create_file(full,b'',retentionCode='영구')
    call('POST','/api/v1/files/uploads/'+empty['uploadId']+'/complete',key=full)
    oversized=dict(requestId=str(uuid.uuid4()),originalName='oversized',size=5000000001,sha256='0'*64)
    call('POST','/api/v1/files/uploads',oversized,key=full,expected=413)
    call('GET','/internal/v1/files/environments/'+str(uuid.uuid4()),expected=404,raw=True)
    print('PASS finish: persisted resume, checksum, complete retry, public/private, scopes, project/environment isolation, revoked key, suspension, delete, empty file, 5GB limit, admin JWT and owner boundaries, single-use private ticket, HEAD without consumption')


def check_duplicates():
    full=state['full']['apiKey']; payload=b'verified duplicate content'; ids=[]
    for index in range(3):
        upload,_=create_file(full,payload,'renamed-'+str(index)+'.txt',visibility='PRIVATE' if index else 'PUBLIC')
        chunk(upload['uploadId'],full,0,payload)
        file,_=call('POST','/api/v1/files/uploads/'+upload['uploadId']+'/complete',key=full); ids.append(file['fileId'])
    path='/api/v1/files/'+ids[0]+'/duplicates'
    call('GET',path,expected=401);call('GET',path,key=state['old']['apiKey'],expected=403)
    for key_name in ['foreign','prod_key']: call('GET',path,key=state[key_name]['apiKey'],expected=404)
    result,_=call('GET',path,key=state['read']['apiKey'])
    assert {x['fileId'] for x in result['files']}==set(ids[1:]) and not result['hasMore']
    first,_=call('GET',path+'?limit=1',key=full);second,_=call('GET',path+'?limit=1&offset=1',key=full)
    assert first['hasMore'] and not second['hasMore'] and first['files'][0]['fileId']!=second['files'][0]['fileId']
    call('GET',path+'?limit=101',key=full,expected=400)
    admin_path='/api/v1/files/admin/environments/'+state['environments'][0]['id']+'/'+ids[0]+'/duplicates'
    call('GET',admin_path,key=full,expected=401)
    admin_result,_=call('GET',admin_path,auth=True);assert admin_result==result
    call('DELETE','/api/v1/files/'+ids[1],key=full,expected=204)
    after,_=call('GET',path,key=full);assert [x['fileId'] for x in after['files']]==[ids[2]]
    print('PASS duplicates: verified renamed private/public files, pagination, scopes, DEV/PROD isolation, admin JWT, deleted-file exclusion')


def cleanup():
    errors=[]
    # All environments below were created by this run; remove browser-created fixtures too.
    for env in state.get('environments',[]):
        try:
            path='/api/v1/files/admin/environments/'+env['id']
            pending,_=call('GET',path+'/uploads',auth=True)
            for item in pending: call('DELETE',path+'/uploads/'+item['upload']['uploadId'],auth=True,expected=204)
            while True:
                rows,_=call('GET',path,auth=True)
                if not rows: break
                for item in rows: call('DELETE',path+'/'+item['fileId'],auth=True,expected=204)
        except Exception: errors.append('admin file cleanup: '+env['id'])
    for upload in state.get('uploads',[]):
        try:
            result,_=call('GET','/api/v1/files/uploads/'+upload['id'],key=upload['key'])
            if result['state']=='READY': call('DELETE','/api/v1/files/'+upload['id'],key=upload['key'],expected=204)
            elif result['state']=='UPLOADING': call('DELETE','/api/v1/files/uploads/'+upload['id'],key=upload['key'],expected=204)
        except Exception: errors.append('file cleanup: '+upload['id'])
    for item in state.get('keys',[]):
        try: call('DELETE',ADMIN+'/credentials/'+item['id'],auth=True,expected=204)
        except Exception: errors.append('credential cleanup: '+item['id'])
    for project in state.get('projects',[]):
        try:
            if project['status']!='SUSPENDED':
                call('PUT',ADMIN+'/projects/'+project['id'],dict(name=project['name'],status='SUSPENDED',revision=project['revision']),auth=True)
        except Exception: errors.append('project cleanup: '+project['id'])
    if state.get('refresh'):
        try: call('POST',OIDC+'/logout',urlencode(dict(client_id='platform-admin-cli',refresh_token=state['refresh'])).encode(),
                  headers={'Content-Type':'application/x-www-form-urlencoded'},expected=204)
        except Exception: errors.append('test session logout')
    if errors:
        save(); raise AssertionError(errors)
    if STATE.exists(): STATE.unlink()
    print('PASS cleanup: uploads deleted/cancelled, keys revoked, own projects suspended, test session logged out')


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--phase',choices=['start','finish','cleanup','all'],default='all')
    phase=parser.parse_args().phase
    try:
        if phase in ('start','all'): start()
        if phase in ('finish','all'): finish()
        if phase=='cleanup':
            state.update(json.loads(STATE.read_text(encoding='utf-8'))); login(state['refresh'])
    except Exception:
        if token: cleanup()
        raise
    else:
        if phase!='start': cleanup()
