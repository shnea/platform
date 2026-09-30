"""Package production Compose, encrypted settings and deployment script; never package private keys."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re

root = Path(__file__).resolve().parents[1]
files = ['compose.yml', '.env.prod', 'infra/loki/loki.yml', 'scripts/deploy.sh',
         'docs/NAS_DEPLOYMENT.md', 'docs/REVERSE_PROXY.md']
environment = (root / '.env.prod').read_text(encoding='utf-8')
assert 'DOTENV_PUBLIC_KEY_PROD=' in environment and 'DOTENV_PRIVATE_KEY' not in environment
for line in environment.splitlines():
    if re.match(r'^[A-Z_][A-Z0-9_]*=', line) and not line.startswith('DOTENV_PUBLIC_KEY'):
        value = line.split('=', 1)[1].strip().strip('"').strip("'")
        assert not value or value.startswith('encrypted:'), 'Refusing unencrypted production settings'
out = root / 'output/releases/platform-nas-config.zip'
out.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(out, 'w', ZIP_DEFLATED) as archive:
    for name in files:
        entry = ZipInfo(name)
        entry.create_system = 3
        mode = 0o100755 if name.endswith('.sh') else 0o100644
        entry.external_attr = mode << 16
        archive.writestr(entry, (root / name).read_text(encoding='utf-8').replace('\r\n', '\n'), compress_type=ZIP_DEFLATED)
print('Created platform-nas-config.zip (encrypted settings; no keys, tools or runtime data).')
