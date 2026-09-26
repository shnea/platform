FROM node:24-alpine AS build
WORKDIR /workspace/packages/editor
COPY packages/editor/package*.json ./
RUN npm ci
COPY packages/editor/ ./
RUN npm run example:build
RUN mkdir -p /workspace/integrations && npm pack --pack-destination /workspace/integrations
COPY docs/SERVICE_INTEGRATION.md /workspace/docs/SERVICE_INTEGRATION.md
COPY docs/integration/AUTH.md /workspace/docs/integration/AUTH.md
COPY docs/integration/JOBS.md /workspace/docs/integration/JOBS.md
COPY docs/integration/LOGS.md /workspace/docs/integration/LOGS.md
COPY services/project-service/src/main/resources/openapi.json /workspace/services/project-service/src/main/resources/openapi.json
COPY services/file-service/src/main/resources/openapi.json /workspace/services/file-service/src/main/resources/openapi.json
COPY scripts/build-integration.mjs /workspace/scripts/build-integration.mjs
RUN node /workspace/scripts/build-integration.mjs /workspace/integrations
WORKDIR /workspace/apps/admin-web
COPY apps/admin-web/package*.json ./
RUN npm ci
COPY apps/admin-web/ ./
RUN npm run build
FROM nginx:1.30.5-alpine
COPY apps/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin-web/dist /usr/share/nginx/html
COPY --from=build /workspace/packages/editor/dist/examples /usr/share/nginx/html/examples/editor
COPY --from=build /workspace/integrations /usr/share/nginx/html/integrations
