"""Create an allowlisted NAS configuration ZIP. No .env, data or source code."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
root=Path(__file__).resolve().parents[1]
files=['compose.yml','compose.nas.yml','.env.example','infra/loki/loki.yml','scripts/init-env.py','scripts/prepare-nas.sh','docs/NAS_DEPLOYMENT.md','docs/REVERSE_PROXY.md']
out=root/'output/releases/platform-nas-config.zip';out.parent.mkdir(parents=True,exist_ok=True)
with ZipFile(out,'w',ZIP_DEFLATED) as archive:
    for name in files:
        assert name!='.env' and '..' not in Path(name).parts
        archive.writestr(name,(root/name).read_text(encoding='utf-8').replace('\r\n','\n'))
print('Created output/releases/platform-nas-config.zip (configuration only; images must be published separately)')
