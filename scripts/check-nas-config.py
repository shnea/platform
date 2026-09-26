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
args=['docker','compose','--env-file',str(envfile),'-f',str(root/'compose.yml'),'-f',str(root/'compose.nas.yml'),'--profile','setup','config','--format','json']
result=subprocess.run(args,cwd=root,capture_output=True,text=True,encoding='utf-8')
assert result.returncode==0,'Compose configuration failed; inspect with redaction'
config=json.loads(result.stdout)
for volume,path in [('postgres-data','postgres'),('file-data','files'),('log-data','loki')]:
    entry=config['volumes'][volume]
    assert entry['driver_opts']=={'type':'none','o':'bind','device':'/volume2/homes/platform/'+path}
for name,service in config['services'].items():
    assert 'build' not in service,name
    if name!='nginx':assert not service.get('ports'),name
assert config['services']['project-service']['environment']['PLATFORM_MODE']=='prod'
assert config['networks']['logs']['internal'] is True
assert {n for n,s in config['services'].items() if 'logs' in s.get('networks',{})}=={'loki','project-service'}
# Only ephemeral generated check files are removed, never the real .env.
assert envfile.resolve().parent==folder.resolve() and folder.resolve().parent==(root/'output/job-checks').resolve()
envfile.unlink();(folder/'.env.example').unlink();folder.rmdir()
print('PASS fresh production secrets, no overwrite, three volume2 bind volumes, private DB/Loki, production image-only Compose')
