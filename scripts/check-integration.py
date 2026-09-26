"""Read-only checks for the public AI integration entry point and artifacts."""
import hashlib
import io
import json
import re
import sys
import tarfile
from urllib.error import HTTPError
from urllib.request import urlopen

base = (sys.argv[1] if len(sys.argv) > 1 else 'https://platform.shnea.kr').rstrip('/')

def get(path):
    with urlopen(base + path, timeout=30) as response:
        assert response.status == 200
        assert response.headers['X-Content-Type-Options'] == 'nosniff', path
        return response.read()

guide = get('/integrations/SERVICE_INTEGRATION.md').decode('utf-8')
assert len(guide.splitlines()) <= 70
assert not re.search(r'\]\((?!https://|#)', guide), 'Repository-relative document link'
links = set(re.findall(r'https://platform\.shnea\.kr(/[^\s|`]+)', guide))
for link in sorted(links):
    content = get(link)
    if link.endswith('.md'):
        assert not re.search(r'\]\((?!https://|#)', content.decode('utf-8')), link
        assert not re.search(r'`docs/[^`]+\.md`', content.decode('utf-8')), link
print(f'PASS public guide: {len(guide.splitlines())} lines, {len(links)} accessible links, no credentials supplied')

for kind in ['project', 'file']:
    spec = json.loads(get(f'/integrations/{kind}.openapi.json'))
    assert spec['openapi'].startswith('3.') and spec['paths']
    assert not any('/admin/' in path or '/internal/' in path or '/downloads/' in path for path in spec['paths'])
    assert 'Administrator' not in spec.get('components', {}).get('securitySchemes', {})
    if kind == 'project':
        assert set(spec['paths']) == {'/api/v1/integration/context', '/api/v1/dev/login'}
    def visit(value):
        if isinstance(value, dict):
            if '$ref' in value:
                assert value['$ref'].startswith('#/')
                target = spec
                for key in value['$ref'][2:].split('/'):
                    target = target[key]
            for security in value.get('security', []):
                for name in security:
                    assert name in spec['components']['securitySchemes']
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(spec)
    print(f"PASS {kind} specification: {len(spec['paths'])} external paths, references resolved")

checksums = json.loads(get('/integrations/checksums.json'))
assert checksums and len(checksums) == 1
for name, digest in checksums.items():
    assert re.fullmatch(r'shnea-editor-[a-zA-Z0-9.-]+\.tgz', name)
    artifact = get('/integrations/' + name)
    assert hashlib.sha256(artifact).hexdigest() == digest
    with tarfile.open(fileobj=io.BytesIO(artifact), mode='r:gz') as package:
        names = package.getnames()
        assert all(item.startswith('package/') and '..' not in item.split('/') for item in names)
        assert not any('/.env' in item or '/node_modules/' in item or '/examples/' in item or '/jsp/' in item for item in names)
        assert {'package/dist/bindings/react.js', 'package/dist/bindings/vue.js',
                'package/dist/browser/editor.js', 'package/dist/browser/editor.css',
                'package/dist/browser/THIRD-PARTY-NOTICES.txt', 'package/LICENSE-LUCIDE'} <= set(names)
    print(f'PASS package download: {len(names)} files, SHA-256 verified')

for path in ['/integrations/.env', '/integrations/STATUS.md', '/integrations/missing.json', '/integrations/']:
    try:
        get(path)
    except HTTPError as error:
        assert error.code in (403, 404), (path, error.code)
    else:
        raise AssertionError('Unexpected published material: ' + path)
print('PASS allowlisted static publication; no project data or API mutations')
