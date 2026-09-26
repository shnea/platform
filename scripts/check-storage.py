"""Run in the tools image with --user 0:0 --tmpfs /data, never real volumes."""
import os
import subprocess
import sys
from pathlib import Path

root = Path('/data')
assert root.is_dir() and not any(root.iterdir()), 'Requires empty disposable /data'
command = [sys.executable, '/tools/prepare-storage.py']
subprocess.run(command, check=True)
for name, owner in [('files', (100, 101)), ('loki', (10001, 10001))]:
    item = root / name
    assert (item.stat().st_uid, item.stat().st_gid) == owner
    assert item.stat().st_mode & 0o777 == 0o700
    (item / 'existing-data').write_text('preserve')
    os.chown(item, 1234, 1234)
    item.chmod(0o750)
subprocess.run(command, check=True)
for name in ['files', 'loki']:
    item = root / name
    assert (item / 'existing-data').read_text() == 'preserve'
    assert item.stat().st_uid == 1234 and item.stat().st_mode & 0o777 == 0o750
(root / 'postgres').rmdir()  # Empty fixture directory only.
(root / 'postgres').symlink_to('/tmp', target_is_directory=True)
assert subprocess.run(command, capture_output=True).returncode != 0
print('PASS empty ownership, repeated startup preserves content/ownership/mode, symbolic link rejected')
