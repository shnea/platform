"""Prepare only empty NAS storage directories; never rewrite existing data."""
import os
from pathlib import Path

root = Path('/data')
assert os.geteuid() == 0 and root.is_dir(), 'Storage initialization requires the mounted data root'
owners = {'postgres': (0, 0), 'files': (100, 101), 'loki': (10001, 10001)}
for name in owners:
    path = root / name
    if path.is_symlink() or (path.exists() and not path.is_dir()):
        raise SystemExit('Refusing unexpected storage path: ' + name)
for name, (uid, gid) in owners.items():
    path = root / name
    path.mkdir(exist_ok=True)
    if not any(path.iterdir()):
        os.chown(path, uid, gid)
        path.chmod(0o700)
print('PASS storage directories ready; existing nonempty directories left unchanged')
