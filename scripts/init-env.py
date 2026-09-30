"""Create a new encrypted environment; never replace an existing environment or its keys."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--environment', choices=['dev', 'prod'], default='dev')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
target = root / ('.env.' + args.environment)
if target.exists():
    raise SystemExit(f'{target.name} already exists. Edit with dotenvx; existing secrets were preserved.')
binary = root / '.tools' / ('dotenvx.exe' if os.name == 'nt' else 'dotenvx')
tool = str(binary) if binary.exists() else shutil.which('dotenvx')
if not tool:
    raise SystemExit('Install Dotenvx 2.24.0 first; see docs/NAS_DEPLOYMENT.md.')
values = {}
for line in (root / '.env.example').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        values[key] = value
for key in values:
    if (key.endswith('_PASSWORD') or key.startswith('PLATFORM_') and key.endswith('_SECRET') or key == 'KEYCLOAK_PROVISIONER_SECRET') and not values[key]:
        values[key] = secrets.token_hex(32)
if args.environment == 'prod':
    values.update(COMPOSE_PROJECT_NAME='shnea-platform-prod', PLATFORM_MODE='prod',
                  BIND_ADDRESS='192.168.0.93', PLATFORM_WEB_URL='https://platform.shnea.kr',
                  KEYCLOAK_PUBLIC_URL='https://platform.shnea.kr/auth')
    for key in values:
        if key.endswith('_CPUS'):
            values[key] = '0'
with os.fdopen(os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w', encoding='utf-8', newline='\n') as output:
    output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
try:
    result = subprocess.run([tool, 'encrypt', '--no-armor', '--no-native', '-f', str(target)], cwd=root, capture_output=True)
    if result.returncode:
        raise RuntimeError('Encryption failed')
    for line in target.read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.startswith(('#', 'DOTENV_PUBLIC_KEY')):
            value = line.split('=', 1)[1].strip().strip('"\'')
            if value and not value.startswith('encrypted:'):
                raise RuntimeError('Unencrypted value remains')
except (OSError, RuntimeError):
    private = root / '.secrets'
    private.mkdir(exist_ok=True)
    recovery = private / (target.name + '.' + secrets.token_hex(4) + '.recovery')
    target.replace(recovery)
    raise SystemExit('Encryption failed. Plaintext moved to ignored .secrets; do not commit it.')
print(f'Created encrypted {target.name}. Keep .env.keys separate from Git and images.')
