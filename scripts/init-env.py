"""Generate local development credentials without replacing an existing .env."""
from pathlib import Path
import os
import secrets
import sys

template_path = Path(".env.example")
template = template_path.read_text(encoding="utf-8")
for key in ("POSTGRES_PASSWORD", "PROJECT_DB_PASSWORD", "FILE_DB_PASSWORD",
            "NOTIFICATION_DB_PASSWORD", "IDENTITY_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
            "PLATFORM_ADMIN_PASSWORD", "KEYCLOAK_PROVISIONER_SECRET", "PLATFORM_MAIL_SECRET", "PLATFORM_EVENTS_SECRET", "PLATFORM_MONITORING_SECRET"):
    template = template.replace(f"{key}=\n", f"{key}={secrets.token_hex(32)}\n")
try:
    fd = os.open(".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    if "--upgrade" in sys.argv:
        existing = Path(".env").read_text(encoding="utf-8")
        keys = {line.split("=", 1)[0] for line in existing.splitlines() if "=" in line}
        additions = [line for line in template.splitlines()
                     if "=" in line and not line.startswith("#") and line.split("=", 1)[0] not in keys]
        with Path(".env").open("a", encoding="utf-8") as output:
            if additions:
                output.write("\n" + "\n".join(additions) + "\n")
        print(f"Added {len(additions)} missing settings; existing values unchanged.")
        raise SystemExit(0)
    raise SystemExit(".env already exists; left unchanged.")
with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
    # A root-run Linux container should leave the file owned by the host user.
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        owner = template_path.stat()
        os.fchown(output.fileno(), owner.st_uid, owner.st_gid)
    output.write(template)
print("Created .env with separate random development credentials.")
