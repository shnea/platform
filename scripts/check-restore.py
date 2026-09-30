"""Detect changed/truncated restored COPY data without accessing a database."""
from pathlib import Path
import tempfile
from verify_restore import verify

sample = b"-- dump\nCOPY public.records (id, data) FROM stdin;\n1\tline\\nnext\n2\t--value\n\\.\nSELECT pg_catalog.setval('public.records_id_seq', 2, true);\n"
with tempfile.TemporaryDirectory() as directory:
    original, restored = Path(directory)/'original', Path(directory)/'restored'
    original.write_bytes(sample)
    restored.write_bytes(sample)
    assert verify(original, restored) == dict(tables=1, rows=2, sequences=1, dataHashesMatch=True)
    for broken in (sample.replace(b'--value', b'changed'), sample.replace(b', 2, true', b', 3, true'), sample.split(b'\\.')[0], b''):
        restored.write_bytes(broken)
        try:
            verify(original, restored)
            raise AssertionError('Corrupted/incomplete restore was accepted')
        except ValueError: pass
print('PASS restored row content/count, sequence positions and truncated backup rejection')
