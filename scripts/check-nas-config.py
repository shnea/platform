"""Check encrypted dev/prod Compose and optional pre-migration snapshots without logging secrets."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', type=Path, help='Private directory containing before-dev.json and before-nas.json')
args = parser.parse_args()
tool = root / '.tools' / ('dotenvx.exe' if os.name == 'nt' else 'dotenvx')

def run(command, env=None):
    result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise RuntimeError('Command failed (output suppressed to protect secrets): ' + str(command[0]))
    return result.stdout

def configuration(mode, profiles=False):
    file = root / ('.env.' + mode)
    for line in file.read_text(encoding='utf-8').splitlines():
        if re.match(r'^[A-Z_][A-Z0-9_]*=', line) and not line.startswith('DOTENV_PUBLIC_KEY'):
            value = line.split('=', 1)[1].strip().strip('"').strip("'")
            assert not value or value.startswith('encrypted:'), 'Plaintext setting in ' + file.name
    values = json.loads(run([str(tool), 'get', '--strict', '--overload', '--no-armor', '--no-native', '-f', str(file), '--format', 'json']))
    assert 'IMAGE_TAG' not in values and 'IMAGE_REGISTRY' not in values
    env = dict(os.environ)
    for key in ('COMPOSE_FILE', 'COMPOSE_PATH_SEPARATOR', 'COMPOSE_PROFILES'):
        env.pop(key, None)
    env.update(values)
    env['IMAGE_TAG'] = 'dev' if mode == 'dev' else '012345abcdef'
    command = ['docker', 'compose', '--env-file', '.env.example', '-f', 'compose.yml']
    if mode == 'dev': command += ['-f', 'compose.dev.yml']
    if profiles: command += ['--profile', 'test']
    command += ['config', '--format', 'json', '--no-path-resolution']
    return json.loads(run(command, env))

dev, prod = configuration('dev'), configuration('prod')
assert dev['name'] == 'shnea-platform-dev' and prod['name'] == 'shnea-platform-prod'
assert len(prod['services']) == 10 and 'storage-init' not in dev['services']
assert len({s['image'] for s in prod['services'].values() if '/platform-' in s['image']}) == 8
for name, service in prod['services'].items():
    assert 'build' not in service and float(service.get('cpus', 0)) == 0, name
    if name != 'nginx': assert not service.get('ports'), name
for service, target, directory in [('db', '/var/lib/postgresql/data', 'postgres'), ('file-service', '/app/storage', 'files'), ('loki', '/loki', 'loki')]:
    mount = next(v for v in prod['services'][service]['volumes'] if v['target'] == target)
    assert mount['type'] == 'bind' and mount['source'] == '/volume2/homes/platform/' + directory
    assert prod['services'][service]['depends_on']['storage-init']['condition'] == 'service_completed_successfully'
    local = next(v for v in dev['services'][service]['volumes'] if v['target'] == target)
    assert local['type'] == 'volume'
for service in dev['services'].values():
    assert 'storage-init' not in service.get('depends_on', {})
    assert all('/volume2/' not in v.get('source', '') for v in service.get('volumes', []))
assert all(not n.get('ipam') for n in dev['networks'].values())
assert prod['services']['identity-setup']['depends_on']['keycloak']['condition'] == 'service_healthy'
assert prod['services']['project-service']['depends_on']['identity-setup']['condition'] == 'service_completed_successfully'
assert prod['networks']['database']['internal'] and prod['networks']['logs']['internal']
assert {p['image'].split(':')[0] for p in configuration('dev', True)['services'].values() if '/platform-' in p['image']}
for mode, filename in [('dev', 'before-dev.json'), ('prod', 'before-nas.json')]:
    if not args.baseline: continue
    old = json.loads((args.baseline / filename).read_text(encoding='utf-8'))
    current = dev if mode == 'dev' else prod
    assert old['name'] == current['name']
    for name, service in old['services'].items():
        now = current['services'][name]
        for field in ('environment', 'volumes', 'depends_on', 'networks', 'ports', 'cpus', 'mem_limit', 'command', 'entrypoint', 'healthcheck'):
            assert service.get(field) == now.get(field), f'Changed runtime contract: {mode}/{name}/{field}'
    assert old['networks'] == current['networks'], 'Changed network contract: ' + mode
    assert old.get('volumes', {}) == current.get('volumes', {}) or mode == 'prod', 'Changed development volumes'
print('PASS encrypted environments, eight production images, storage/identity ordering, dev isolation, runtime contract preservation')
