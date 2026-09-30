"""Package production Compose, encrypted settings and deployment script; never package private keys."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re
import io
import json
import subprocess
import tarfile

root = Path(__file__).resolve().parents[1]
files = ['compose.yml', '.env.prod', 'infra/loki/loki.yml', 'scripts/deploy.sh',
         'scripts/recovery-drill.sh',
         'docs/NAS_DEPLOYMENT.md', 'docs/REVERSE_PROXY.md', 'docs/CICD.md', '운영안내.html']
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

# CI transfers an allowlisted tar archive over the restricted SSH command.
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
manifest_path = root / f'output/releases/{sha[:12]}/release.json'
if manifest_path.exists():
    manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
    assert manifest['commit'] == sha and manifest['tag'] == sha[:12] and len(manifest['images']) == 8
    content = {name: (root / name).read_text(encoding='utf-8').replace('\r\n', '\n').encode() for name in files}
    content['SOURCE_COMMIT'] = (sha + '\n').encode()
    content['IMAGE_DIGESTS'] = ''.join(i['image'] + ' ' + i['digest'] + '\n' for i in manifest['images']).encode()
    with tarfile.open(out.with_suffix('.tar.gz'), 'w:gz', format=tarfile.USTAR_FORMAT) as archive:
        for name, data in content.items():
            entry = tarfile.TarInfo(name)
            entry.mode = 0o755 if name.endswith('.sh') else 0o644
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
    print('Created platform-nas-config.tar.gz with source identity and image digests.')
