"""Prepare dev/build/prod files from the private local .env without printing values."""
from pathlib import Path
import os
import re

root = Path(__file__).resolve().parents[1]
source = (root / '.env').read_text(encoding='utf-8-sig')
assert re.search(r'^PLATFORM_MODE=dev\s*$', source, re.M), 'Expected development .env as the source'

def changed(text, values):
    lines = text.splitlines()
    for key, value in values.items():
        indexes = [i for i, line in enumerate(lines) if line.startswith(key + '=')]
        assert len(indexes) <= 1, 'Duplicate configuration key: ' + key
        if indexes:
            lines[indexes[0]] = key + '=' + value
        else:
            lines.append(key + '=' + value)
    return '\n'.join(lines) + '\n'

def write(name, value):
    path = root / name
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w', encoding='utf-8', newline='\n') as output:
        output.write(value)
    path.chmod(0o600)

build = (root / '.env.build').read_text(encoding='utf-8-sig') if (root / '.env.build').exists() else (root / '.env.build.example').read_text(encoding='utf-8')
release = dict(line.split('=', 1) for line in build.splitlines() if line and not line.startswith('#') and '=' in line)
registry, tag = release['IMAGE_REGISTRY'], release['IMAGE_TAG']
assert re.fullmatch(r'[a-zA-Z0-9.-]+(?::[0-9]+)?', registry)
assert re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}', tag)
write('.env.dev', changed(source, {'COMPOSE_FILE':'compose.yml|compose.dev.yml', 'COMPOSE_PATH_SEPARATOR':'|'}))
write('.env.build', changed(build, {'COMPOSE_FILE':'compose.build.yml'}))
production = changed(source, {
    'COMPOSE_FILE':'compose.yml|compose.nas.yml', 'COMPOSE_PATH_SEPARATOR':'|',
    'COMPOSE_PROJECT_NAME':'shnea-platform-prod', 'IMAGE_REGISTRY':registry, 'IMAGE_TAG':tag,
    'PLATFORM_MODE':'prod', 'BIND_ADDRESS':'192.168.0.93', 'HTTP_PORT':'30140',
    'PLATFORM_WEB_URL':'https://platform.shnea.kr', 'KEYCLOAK_PUBLIC_URL':'https://platform.shnea.kr/auth',
    'PLATFORM_DATA_ROOT':'/volume2/homes/platform', 'NGINX_TRUSTED_PROXY':'127.0.0.1',
})
# NAS kernel lacks CFS quota support; retain development and memory limits.
write('.env.prod', changed(production, {key+'_CPUS':'0' for key in ['DB','KEYCLOAK','PROJECT','LOKI','FILE','NOTIFICATION','NGINX','ADMIN_WEB']}))
print('Prepared .env.dev/.env.build/.env.prod from .env; private values were not displayed')
