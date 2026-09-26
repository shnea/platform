"""DEV-only external Job/log end-to-end check. start -> restart services -> finish.
State holds only fixture credentials in ignored output/playwright. Never print them.
"""
import argparse
import importlib.util
import json
import logging
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode
from jsonschema import Draft202012Validator, FormatChecker

BASE='http://nginx:8080'
OIDC='http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect'
A='/api/v1/admin'
STATE=Path('/state/external-services.json')
session={}

def call(method,path,body=None,key=None,admin=False,expected=200,headers=None):
    hdr=dict(headers or {})
    if key:hdr['X-Platform-Key']=key
    if admin:hdr['Authorization']='Bearer '+session['access_token']
    if isinstance(body,dict):body=json.dumps(body).encode();hdr['Content-Type']='application/json'
    try:
        with urlopen(Request(path if path.startswith('http') else BASE+path,data=body,headers=hdr,method=method),timeout=15) as res:
            status,raw=res.status,res.read()
    except HTTPError as error:status,raw=error.code,error.read()
    assert status in (expected if isinstance(expected,tuple) else (expected,)),(method,path.split('?')[0],status,expected, json.loads(raw).get('code') if raw and status>=400 else '')
    return json.loads(raw) if raw else None

def login():
    global session
    session=call('POST',OIDC+'/token',urlencode(dict(grant_type='password',client_id='platform-admin-cli',username='admin',password=os.environ['PLATFORM_ADMIN_PASSWORD'])).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})

def save(state):
    STATE.parent.mkdir(parents=True,exist_ok=True)
    STATE.write_text(json.dumps(state),encoding='utf-8');STATE.chmod(0o600)

def example(name):
    spec=importlib.util.spec_from_file_location(name,'/examples/'+name+'-client.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def main():
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['start','finish','cleanup']);args=parser.parse_args()
    assert call('GET','/api/v1/config')['mode']=='dev','Only DEV is allowed'
    login()
    state=json.loads(STATE.read_text()) if STATE.exists() else {'projects':[],'keys':[]}
    try:
        if args.phase=='start':
            assert not state['projects'],'Existing fixture: finish or cleanup first'
            for index in range(2):
                code='external-check-'+uuid.uuid4().hex[:8]
                project=call('POST',A+'/projects',{'code':code,'name':f'외부 서비스 검증 {index+1}'},admin=True,expected=201)
                state['projects'].append(project);save(state)
                env=call('POST',A+'/projects/'+project['id']+'/environments',{'code':'dev','kind':'DEV','registrationAllowed':False,'redirectUris':['http://localhost:30140/callback']},admin=True,expected=201)
                assert env['state']=='READY';project['env']=env['id'];save(state)
                key=call('POST',A+'/environments/'+env['id']+'/credentials',{'scopes':['jobs:read','jobs:write','jobs:work','logs:read','logs:write']},admin=True,expected=201)
                state['keys'].append(key);project['key']=key['apiKey'];save(state)
            p,q=state['projects'];key=p['key'];env=p['env']
            old=call('POST',A+'/environments/'+env+'/credentials',{},admin=True,expected=201);state['keys'].append(old);save(state)
            for path in ['/api/v1/jobs','/api/v1/logs']:
                assert call('GET',path,expected=401)['code']=='INVALID_API_KEY'
                assert call('GET',path,key=old['apiKey'],expected=403)['code']=='INSUFFICIENT_SCOPE'
            for suffix in ['/external-jobs','/logs']:
                call('GET',A+'/environments/'+env+suffix,expected=401)
            spec=call('GET',A+'/openapi',admin=True)
            def verify(name,value):Draft202012Validator({'$ref':'#/components/schemas/'+name,'components':spec['components']},format_checker=FormatChecker()).validate(value)
            request=dict(requestId=str(uuid.uuid4()),queue='report',payload={'reportId':'demo-123'},maxAttempts=2)
            job=call('POST','/api/v1/jobs',request,key=key,expected=202);verify('ExternalJob',job)
            assert call('POST','/api/v1/jobs',request,key=key,expected=202)['id']==job['id']
            call('POST','/api/v1/jobs',dict(request,payload={'changed':True}),key=key,expected=409)
            call('GET','/api/v1/jobs/'+job['id'],key=q['key'],expected=404)
            with ThreadPoolExecutor(max_workers=4) as pool:
                claims=list(pool.map(lambda i:call('POST','/api/v1/jobs/claim',{'queue':'report','workerId':f'worker-{i}'},key=key,expected=(200,204)),range(4)))
            claims=[c for c in claims if c];assert len(claims)==1;claim=claims[0];verify('ExternalJobClaim',claim)
            lease={'X-Job-Lease':claim['leaseToken']};path='/api/v1/jobs/'+job['id']
            call('POST',path+'/complete',{'result':{}},key=key,expected=409)
            assert call('POST',path+'/heartbeat',{'progress':50},key=key,headers=lease)['progress']==50
            complete={'result':{'reportId':'demo-123'}}
            assert call('POST',path+'/complete',complete,key=key,headers=lease)['state']=='SUCCEEDED'
            call('POST',path+'/complete',complete,key=key,headers=lease)
            jobclient=example('jobs').Jobs(BASE,key)
            sample=jobclient.submit('example',{},str(uuid.uuid4()))
            assert jobclient.run_once('example','python-example',lambda j,lost:{'ok':True})
            assert jobclient.request('GET','/'+sample['id'])['job']['state']=='SUCCEEDED'
            failed=jobclient.submit('failed',{},str(uuid.uuid4()))
            claim=call('POST','/api/v1/jobs/claim',{'queue':'failed','workerId':'fixture'},key=key)
            call('POST','/api/v1/jobs/'+failed['id']+'/fail',{'errorCode':'HOST_JOB_FAILED','retryable':False},key=key,headers={'X-Job-Lease':claim['leaseToken']})
            queued=jobclient.submit('waiting',{},str(uuid.uuid4()));state['queued']=queued['id']
            recovery=jobclient.submit('recover',{},str(uuid.uuid4()),max_attempts=2)
            claim=call('POST','/api/v1/jobs/claim',{'queue':'recover','workerId':'before-restart'},key=key)
            state['recovery']=claim;save(state)
            # Logs through the real storage path, including forged tenant header.
            for project in [p,q]:
                line={'service':'order-api','level':'ERROR','message':'password=do-not-store Bearer secret-value user@example.com', 'requestId':'fixture-request','traceId':'fixture-trace','errorCode':'HOST_BUSY','attributes':{'password':'never-store','project':project['id']},'exception':'token=exception-secret'}
                verify('LogAccepted',call('POST','/api/v1/logs',{'entries':[line]},key=project['key'],headers={'X-Scope-OrgID':q['env']},expected=202))
            logclient=example('logs');handler=logclient.PlatformLogs(BASE,key,'python-example')
            logger=logging.getLogger('fixture');logger.setLevel(logging.INFO);logger.addHandler(handler)
            logger.info('Python async connection',extra={'requestId':'python-example','attributes':{'token':'never-store'}})
            deadline=time.monotonic()+10
            while handler.stats()['sent']<1 and time.monotonic()<deadline:time.sleep(.1)
            handler.close();logger.removeHandler(handler);assert handler.stats()['sent']==1,handler.stats()
            for project in [p,q]:
                page=call('GET','/api/v1/logs?requestId=fixture-request',key=project['key']);verify('LogPage',page)
                assert len(page['items'])==1 and page['items'][0]['attributes']['project']==project['id']
                raw=json.dumps(page);assert all(secret not in raw for secret in ['do-not-store','secret-value','user@example.com','never-store','exception-secret'])
            invalid={'entries':[{'service':'api','level':'INFO','message':'old','timestamp':(datetime.now(timezone.utc)-timedelta(hours=2)).isoformat()}]}
            call('POST','/api/v1/logs',invalid,key=key,expected=400)
            call('GET','/api/v1/logs?'+urlencode({'from':(datetime.now(timezone.utc)-timedelta(days=8)).isoformat()}),key=key,expected=400)
            call('GET','/api/v1/logs?service=bad%22service',key=key,expected=400)
            state['started']=True;save(state)
            print('PASS real HTTP scopes, two-project isolation, duplicate enqueue, concurrent claim, heartbeat, reports, Python worker, log sanitizer/query/schema, tenant spoof rejection')
            print('READY restart + UI fixtures:',p['code'],q['code'])
        elif args.phase=='finish':
            p=state['projects'][0];key=p['key'];claim=state['recovery'];path='/api/v1/jobs/'+claim['job']['id']
            page=call('GET','/api/v1/logs?requestId=fixture-request',key=key);assert len(page['items'])==1
            deadline=time.monotonic()+90
            while True:
                detail=call('GET',path,key=key)
                if detail['job']['state']=='RETRY_WAIT':break
                assert time.monotonic()<deadline,'Lease recovery did not run';time.sleep(1)
            assert detail['attempts'][0]['state']=='ABANDONED'
            call('POST',path+'/complete',{'result':{}},key=key,headers={'X-Job-Lease':claim['leaseToken']},expected=409)
            while True:
                next_claim=call('POST','/api/v1/jobs/claim',{'queue':'recover','workerId':'after-restart'},key=key,expected=(200,204))
                if next_claim:break
                assert time.monotonic()<deadline;time.sleep(1)
            assert next_claim['job']['attempts']==2
            call('POST',path+'/complete',{'result':{'recovered':True}},key=key,headers={'X-Job-Lease':next_claim['leaseToken']})
            print('PASS real restart: stored logs, lease expiry, stale worker refusal, second attempt completion')
            cleanup(state)
        else:cleanup(state)
    finally:
        if session:call('POST',OIDC+'/logout',urlencode(dict(client_id='platform-admin-cli',refresh_token=session['refresh_token'])).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'},expected=204)

def cleanup(state):
    for project in state['projects']:
        if project.get('env'):
            page=call('GET',A+'/environments/'+project['env']+'/external-jobs?limit=100',admin=True)
            for job in page['items']:
                if job['state'] in ('QUEUED','RETRY_WAIT'):call('POST',A+'/environments/'+project['env']+'/external-jobs/'+job['id']+'/cancel',admin=True)
        current=next(p for p in call('GET',A+'/projects?limit=100',admin=True) if p['id']==project['id'])
        if current['status']!='SUSPENDED':call('PUT',A+'/projects/'+project['id'],{'name':current['name'],'revision':current['revision'],'status':'SUSPENDED'},admin=True)
    for key in state['keys']:
        call('DELETE',A+'/credentials/'+key['id'],admin=True,expected=204)
        call('GET','/api/v1/logs',key=key['apiKey'],expected=401)
    STATE.unlink(missing_ok=True)
    print('PASS fixture keys revoked/projects suspended. Diagnostic logs expire under retention; admin session ends.')

if __name__=='__main__':main()
