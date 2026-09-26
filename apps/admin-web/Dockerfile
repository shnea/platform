FROM node:24-alpine AS build
WORKDIR /workspace/packages/editor
COPY packages/editor/package*.json ./
RUN npm ci
COPY packages/editor/ ./
RUN npm run build
WORKDIR /workspace/apps/admin-web
COPY apps/admin-web/package*.json ./
RUN npm ci
COPY apps/admin-web/ ./
RUN npm run build
FROM nginx:1.30.5-alpine
COPY apps/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin-web/dist /usr/share/nginx/html
