"""Create an allowlisted NAS configuration ZIP. No .env, data or source code."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED,ZipInfo
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file',help='Explicitly include a private production environment as .env.prod')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
files=['compose.yml','compose.nas.yml','.env.example','infra/loki/loki.yml','scripts/init-env.py','docs/NAS_DEPLOYMENT.md','docs/REVERSE_PROXY.md']
environment=None
if args.env_file:
    environment=Path(args.env_file).read_text(encoding='utf-8-sig')
    values=dict(line.split('=',1) for line in environment.splitlines() if '=' in line and not line.startswith('#'))
    assert values.get('PLATFORM_MODE')=='prod' and values.get('COMPOSE_FILE')=='compose.yml|compose.nas.yml','Requires production NAS configuration'
out=root/'output/releases'/('platform-nas-deploy.zip' if environment else 'platform-nas-config.zip');out.parent.mkdir(parents=True,exist_ok=True)
with ZipFile(out,'w',ZIP_DEFLATED) as archive:
    for name in files:
        assert name!='.env' and '..' not in Path(name).parts
        archive.writestr(name,(root/name).read_text(encoding='utf-8').replace('\r\n','\n'))
    if environment:
        entry=ZipInfo('.env.prod');entry.create_system=3;entry.external_attr=0o100600<<16
        archive.writestr(entry,environment.replace('\r\n','\n'))
print('Created '+out.name+(' (PRIVATE: includes production .env.prod; do not publish)' if environment else ' (configuration only)'))
