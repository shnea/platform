FROM python:3.13-alpine
WORKDIR /tools
COPY scripts/setup-identity.py .
USER nobody
ENTRYPOINT ["python", "/tools/setup-identity.py"]
