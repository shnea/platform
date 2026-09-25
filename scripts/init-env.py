"""Generate local development credentials without replacing an existing .env."""
from pathlib import Path
import os
import secrets

template_path = Path(".env.example")
template = template_path.read_text(encoding="utf-8")
for key in ("POSTGRES_PASSWORD", "PROJECT_DB_PASSWORD", "FILE_DB_PASSWORD",
            "NOTIFICATION_DB_PASSWORD", "IDENTITY_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD"):
    template = template.replace(f"{key}=\n", f"{key}={secrets.token_hex(32)}\n")
try:
    fd = os.open(".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit(".env already exists; left unchanged.")
with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
    # A root-run Linux container should leave the file owned by the host user.
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        owner = template_path.stat()
        os.fchown(output.fileno(), owner.st_uid, owner.st_gid)
    output.write(template)
print("Created .env with separate random development credentials.")
