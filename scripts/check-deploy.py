"""Exercise deployment failure boundaries with a fake Docker CLI; no engine or real secrets."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

script = Path(__file__).with_name('deploy.sh')
docker = '''#!/bin/sh
set -eu
printf '%s\\n' "$*" >> "$TEST_LOG"
case "$*" in
  'compose version') echo 'Docker Compose version v2.39.0';;
  *'config --quiet') [ "${FAIL_AT:-}" != config ];;
  *'config --images') printf '%s\\n' "registry.shnea.kr/platform-tools:$IMAGE_TAG" "grafana/loki:3.7.0";;
  *' ps -a -q') echo old-container;;
  inspect*) echo 'registry.shnea.kr/platform-tools:0.1.3';;
  *' pull') [ "${FAIL_AT:-}" != pull ];;
  *' up -d '*) [ "${FAIL_AT:-}" != up ];;
  run*) [ "${FAIL_AT:-}" != health ];;
  'image inspect '*revision*)
    if [ "${FAIL_AT:-}" = revision ]; then echo deadbeefdead0000000000000000000000000000;
    else echo 012345abcdef0000000000000000000000000000; fi;;
  'image inspect '*) echo 'registry.shnea.kr/platform-tools@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';;
  *) exit 2;;
esac
'''
dotenv = '''#!/bin/sh
set -eu
[ "${FAIL_AT:-}" != decrypt ] || exit 3
while [ "$1" != -- ]; do shift; done
shift
export PLATFORM_MODE=prod COMPOSE_PROJECT_NAME=shnea-platform-prod PLATFORM_DATA_ROOT=/volume2/homes/platform
exec "$@"
'''

def case(failure='', tag='012345abcdef'):
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        for name in ('scripts', '.tools', '.secrets', '.deploy', 'bin'):
            (root / name).mkdir()
        shutil.copyfile(script, root / 'scripts/deploy.sh')
        for name, text in [('bin/docker', docker), ('.tools/dotenvx', dotenv)]:
            p = root / name
            p.write_text(text)
            p.chmod(0o700)
        (root / '.secrets/prod.keys').write_text('dummy-test-key')
        (root / 'compose.yml').write_text('services: {}\n')
        (root / '.env.prod').write_text('# test only\n')
        (root / '.deploy/current').write_text('0.1.3\n')
        (root / '.deploy/legacy-tag').write_text('0.1.3\n')
        env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                   FAIL_AT=failure, TEST_LOG=str(root / 'calls'))
        result = subprocess.run(['sh', str(root / 'scripts/deploy.sh'), tag], env=env, capture_output=True, text=True)
        calls = (root / 'calls').read_text() if (root / 'calls').exists() else ''
        current = (root / '.deploy/current').read_text().strip()
        if failure or tag != '012345abcdef':
            assert result.returncode != 0, (failure, result.stdout)
            assert current == '0.1.3', 'Failed deployment replaced last successful state'
            if failure in ('decrypt', 'config', 'pull', 'revision') or tag != '012345abcdef':
                assert ' up -d ' not in calls, 'Containers changed before successful preflight and pull'
            if failure == 'decrypt': assert not calls
        else:
            assert result.returncode == 0, result.stderr
            assert current == tag
            assert (root / '.deploy/previous').read_text().strip() == '0.1.3'
            assert 'sha256:' in (root / '.deploy/current-images').read_text()
            assert '--no-build' in calls and '--wait' in calls
        assert not (root / '.deploy/lock').exists(), 'Deployment lock leaked'

for failure in ('decrypt', 'config', 'pull', 'revision', 'up', 'health', ''):
    case(failure)
case(tag='latest')
case(tag='0.1.3')
print('PASS deployment success and decrypt/config/pull/start/health failures, tag protection, state preservation and lock cleanup')
