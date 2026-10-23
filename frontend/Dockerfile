# syntax=docker/dockerfile:1
#
# APIForge AI frontend: the built single-page app served by nginx, which also forwards /api to the
# backend so the browser talks to one origin (session and CSRF cookies stay first-party).
# Build context: frontend/.   docker build -t apiforge-frontend frontend
# Runtime settings: APIFORGE_BACKEND_URL (default http://backend:8081) and APIFORGE_PROXY_TRUSTED
# (true only when nginx is reachable solely through a trusted proxy). No secret is involved.

FROM node:24.22.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Only APIFORGE_* variables are substituted into the template; nginx's own $variables stay untouched.
ENV APIFORGE_BACKEND_URL=http://backend:8081 \
    APIFORGE_PROXY_TRUSTED=false \
    NGINX_ENVSUBST_FILTER=^APIFORGE_
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
