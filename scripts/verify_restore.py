"""Compare COPY data and sequence positions from a backup and a restored database."""
import hashlib
import json
from pathlib import Path
import sys


def inventory(path):
    tables, sequences = {}, []
    table, digest, rows = None, None, 0
    with Path(path).open('rb') as source:
        for line in source:
            if table is not None:
                if line.rstrip(b'\r\n') == b'\\.':
                    if table in tables: raise ValueError('Duplicate COPY table in dump')
                    tables[table] = (rows, digest.hexdigest())
                    table = None
                else:
                    digest.update(line)
                    rows += 1
            elif line.startswith(b'COPY ') and line.rstrip().endswith(b' FROM stdin;'):
                table, digest, rows = line.decode().strip(), hashlib.sha256(), 0
            elif line.startswith(b'SELECT pg_catalog.setval('):
                sequences.append(line.rstrip())
            elif b'pg_catalog.lo_create(' in line or line.startswith(b'INSERT INTO '):
                raise ValueError('Unsupported data format; do not claim restore verification')
    if table is not None: raise ValueError('Truncated COPY data')
    if not tables: raise ValueError('No restored table data to verify')
    return tables, sorted(sequences)


def verify(backup, restored):
    original = inventory(backup)
    result = inventory(restored)
    if original != result: raise ValueError('Restored table data or sequence values differ from backup')
    return dict(tables=len(original[0]), rows=sum(value[0] for value in original[0].values()),
                sequences=len(original[1]), dataHashesMatch=True)


if __name__ == '__main__':
    print(json.dumps(verify(sys.argv[1], sys.argv[2])))
