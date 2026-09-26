"""Generate local development credentials without replacing an existing .env."""
from pathlib import Path
import os
import secrets
import sys
import argparse
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--upgrade', action='store_true')
parser.add_argument('--nas', action='store_true', help='Generate a fresh NAS production environment')
parser.add_argument('--image-tag', help='Published release image tag for NAS production')
parser.add_argument('--output', default='.env', help='Destination environment file; existing values are never replaced')
args = parser.parse_args()
if args.nas and (args.upgrade or not args.image_tag):
    parser.error('--nas requires --image-tag and cannot be combined with --upgrade')
if args.image_tag and not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', args.image_tag):
    parser.error('Invalid image tag')

template_path = Path(".env.example")
template = template_path.read_text(encoding="utf-8")
if args.nas:
    replacements = {'COMPOSE_PROJECT_NAME':'shnea-platform-prod', 'IMAGE_TAG':args.image_tag,
        'COMPOSE_FILE':'compose.yml|compose.nas.yml',
        'BIND_ADDRESS':'192.168.0.93', 'PLATFORM_MODE':'prod',
        'PLATFORM_WEB_URL':'https://platform.shnea.kr', 'KEYCLOAK_PUBLIC_URL':'https://platform.shnea.kr/auth'}
    lines = template.splitlines()
    existing_keys = {line.split('=',1)[0] for line in lines if '=' in line and not line.startswith('#')}
    for index,line in enumerate(lines):
        key=line.split('=',1)[0]
        if key in replacements:
            lines[index]=key+'='+replacements[key]
    template = '\n'.join(lines)+'\n'
    template += ''.join(key+'='+value+'\n' for key,value in replacements.items() if key not in existing_keys)
for key in ("POSTGRES_PASSWORD", "PROJECT_DB_PASSWORD", "FILE_DB_PASSWORD",
            "NOTIFICATION_DB_PASSWORD", "IDENTITY_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
            "PLATFORM_ADMIN_PASSWORD", "KEYCLOAK_PROVISIONER_SECRET", "PLATFORM_MAIL_SECRET", "PLATFORM_EVENTS_SECRET", "PLATFORM_MONITORING_SECRET", "PLATFORM_FILES_SECRET"):
    template = template.replace(f"{key}=\n", f"{key}={secrets.token_hex(32)}\n")
try:
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    if args.upgrade:
        existing = Path(args.output).read_text(encoding="utf-8")
        keys = {line.split("=", 1)[0] for line in existing.splitlines() if "=" in line}
        additions = [line for line in template.splitlines()
                     if "=" in line and not line.startswith("#") and line.split("=", 1)[0] not in keys]
        with Path(args.output).open("a", encoding="utf-8") as output:
            if additions:
                output.write("\n" + "\n".join(additions) + "\n")
        print(f"Added {len(additions)} missing settings; existing values unchanged.")
        raise SystemExit(0)
    raise SystemExit(args.output + " already exists; left unchanged.")
with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
    # A root-run Linux container should leave the file owned by the host user.
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        owner = template_path.stat()
        os.fchown(output.fileno(), owner.st_uid, owner.st_gid)
    output.write(template)
print("Created " + args.output + " with separate random " + ("production" if args.nas else "development") + " credentials.")
