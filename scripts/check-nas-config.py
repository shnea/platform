"""Validate NAS bootstrap/config without touching a running stack or printing secrets."""
import json
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

root=Path(__file__).resolve().parents[1]
folder=root/'output/job-checks'/('nas-'+uuid.uuid4().hex)
folder.mkdir(parents=True)
shutil.copyfile(root/'.env.example',folder/'.env.example')
command=[sys.executable,str(root/'scripts/init-env.py'),'--nas','--image-tag','configuration-check-only']
result=subprocess.run(command,cwd=folder,capture_output=True,text=True);assert result.returncode==0
envfile=folder/'.env';before=envfile.read_bytes()
assert subprocess.run(command,cwd=folder,capture_output=True).returncode!=0
assert envfile.read_bytes()==before,'Existing environment must never be replaced'
values=dict(line.split('=',1) for line in before.decode().splitlines() if '=' in line and not line.startswith('#'))
assert values['PLATFORM_MODE']=='prod' and values['COMPOSE_PROJECT_NAME']=='shnea-platform-prod'
assert values['PLATFORM_WEB_URL']=='https://platform.shnea.kr' and values['BIND_ADDRESS']=='192.168.0.93'
secrets=[value for key,value in values.items() if key.endswith('_PASSWORD') or key in ['PLATFORM_FILES_SECRET','PLATFORM_MAIL_SECRET','PLATFORM_EVENTS_SECRET','KEYCLOAK_PROVISIONER_SECRET']]
assert all(len(value)==64 for value in secrets) and len(set(secrets))==len(secrets)
assert values['COMPOSE_FILE']=='compose.yml|compose.nas.yml' and values['COMPOSE_PATH_SEPARATOR']=='|'
args=['docker','compose','--env-file',str(envfile),'config','--format','json']
result=subprocess.run(args,cwd=root,capture_output=True,text=True,encoding='utf-8')
assert result.returncode==0,'Compose configuration failed; inspect with redaction'
config=json.loads(result.stdout)
for service,path,target in [('db','postgres','/var/lib/postgresql/data'),('file-service','files','/app/storage'),('loki','loki','/loki')]:
    entry=next(v for v in config['services'][service]['volumes'] if v['target']==target)
    assert entry['type']=='bind' and entry['source'].replace('\\','/').endswith('/volume2/homes/platform/'+path)
for name,service in config['services'].items():
    assert 'build' not in service,name
    if name!='nginx':assert not service.get('ports'),name
assert config['services']['project-service']['environment']['PLATFORM_MODE']=='prod'
assert len(config['services'])==10 and 'storage-init' in config['services']
assert not config['services']['identity-setup'].get('profiles')
assert config['services']['identity-setup']['depends_on']['keycloak']['condition']=='service_healthy'
assert config['services']['project-service']['depends_on']['identity-setup']['condition']=='service_completed_successfully'
for name in ['db','file-service','loki']:
    assert config['services'][name]['depends_on']['storage-init']['condition']=='service_completed_successfully'
assert config['services']['storage-init']['network_mode']=='none'
assert config['networks']['logs']['internal'] is True
assert {n for n,s in config['services'].items() if 'logs' in s.get('networks',{})}=={'loki','project-service'}
# The same commands select local build configuration using only .env values.
envfile.write_text(before.decode().replace('compose.yml|compose.nas.yml','compose.yml|compose.dev.yml').replace('IMAGE_REGISTRY=registry.shnea.kr','IMAGE_REGISTRY=registry.example.invalid').replace('IMAGE_TAG=configuration-check-only','IMAGE_TAG=tag-check'),encoding='utf-8')
result=subprocess.run(args,cwd=root,capture_output=True,text=True,encoding='utf-8')
assert result.returncode==0
local=json.loads(result.stdout)
expected={'admin-web','db','keycloak','project-service','file-service','notification-service','nginx','identity-setup'}
assert {name for name,s in local['services'].items() if 'build' in s}==expected
for name in expected:
    assert local['services'][name]['image'].startswith('registry.example.invalid/platform-')
    assert local['services'][name]['image'].endswith(':tag-check')
assert 'build' not in local['services']['loki']
envfile.write_text((root/'.env.build.example').read_text(encoding='utf-8'),encoding='utf-8')
result=subprocess.run(args,cwd=root,capture_output=True,text=True,encoding='utf-8')
assert result.returncode==0
build=json.loads(result.stdout)
assert set(build['services'])==expected
assert all('build' in s and not any(key in s for key in ['environment','ports','volumes','profiles']) for s in build['services'].values())
# Only ephemeral generated check files are removed, never the real .env.
assert envfile.resolve().parent==folder.resolve() and folder.resolve().parent==(root/'output/job-checks').resolve()
envfile.unlink();(folder/'.env.example').unlink();folder.rmdir()
print('PASS .env-only local/NAS selection, eight build images, registry/tag overrides, private NAS volumes, automatic storage/identity startup, fresh secrets/no overwrite')
