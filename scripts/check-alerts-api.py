"""DEV-only read/contract check; repeats acknowledgement only on an already acknowledged owned Job fixture."""
import argparse,importlib.util,json,os,uuid
from pathlib import Path
from urllib.parse import urlencode
from jsonschema import Draft202012Validator,FormatChecker

loader=importlib.util.spec_from_file_location('contracts',Path(__file__).with_name('check-openapi.py'))
contracts=importlib.util.module_from_spec(loader);loader.loader.exec_module(contracts)
call=contracts.call

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--environment',required=True,type=uuid.UUID);parser.add_argument('--alert',required=True,type=uuid.UUID)
    args=parser.parse_args();env=str(args.environment);alert=str(args.alert)
    config,_=call('GET','/api/v1/config');assert config['mode']=='dev'
    path='/api/v1/admin/environments/'+env+'/operational-alerts'
    call('GET',path,expected=401);call('POST',path+'/'+alert+'/acknowledge',expected=401)
    form={'Content-Type':'application/x-www-form-urlencoded'}
    session,_=call('POST',contracts.OIDC+'/token',form,urlencode(dict(grant_type='password',client_id='platform-admin-cli',username='admin',password=os.environ['PLATFORM_ADMIN_PASSWORD'])).encode())
    headers={'Authorization':'Bearer '+session['access_token'],'Content-Type':'application/json'}
    try:
        rows,_=call('GET',path+'?acknowledged=true&limit=100',headers)
        value=next(row for row in rows if row['id']==alert);assert value['acknowledgedAt'] and value['acknowledgedBy']
        projects,_=call('GET','/api/v1/admin/projects',headers)
        project=next(p for p in projects if p['id']==value['projectId'])
        assert project['code'].startswith('job-check-') and project['status']=='SUSPENDED','Owned suspended Job fixture required'
        spec,_=call('GET','/api/v1/admin/openapi',headers)
        Draft202012Validator({'$ref':'#/components/schemas/OperationalAlert','components':spec['components']},format_checker=FormatChecker()).validate(value)
        duplicate,_=call('POST',path+'/'+alert+'/acknowledge',headers,json.dumps({'actor':'ignored-client-actor'}).encode())
        assert duplicate==value,'First acknowledgement must remain immutable'
        unread,_=call('GET',path+'?acknowledged=false',headers);assert all(row['id']!=alert for row in unread)
        call('GET',path+'?limit=0',headers,expected=400)
        call('POST',path+'/'+str(uuid.uuid4())+'/acknowledge',headers,expected=404)
        wrong='/api/v1/admin/environments/'+str(uuid.uuid4())+'/operational-alerts/'+alert+'/acknowledge'
        call('POST',wrong,headers,expected=404)
        print('PASS alert response schema, filters, immutable acknowledgement, 400/401/404')
    finally:
        call('POST',contracts.OIDC+'/logout',form,urlencode(dict(client_id='platform-admin-cli',refresh_token=session['refresh_token'])).encode(),204)
    print('PASS own admin session ended; no external sending')

if __name__=='__main__':main()
