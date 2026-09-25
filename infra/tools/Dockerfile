FROM python:3.13-alpine
WORKDIR /tools
COPY scripts/setup-identity.py scripts/identity_admin.py scripts/admin_security.py .
USER nobody
ENTRYPOINT ["python", "/tools/setup-identity.py"]
