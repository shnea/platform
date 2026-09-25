FROM python:3.13-alpine
RUN pip install --no-cache-dir openapi-spec-validator==0.9.0
WORKDIR /checks
CMD ["python", "/checks/check-openapi.py"]
