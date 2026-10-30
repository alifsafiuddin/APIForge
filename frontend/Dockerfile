# syntax=docker/dockerfile:1
#
# APIForge AI frontend: the built single-page app served by nginx, which also forwards /api to the
# backend so the browser talks to one origin (session and CSRF cookies stay first-party).
# Build context: frontend/.   docker build -t apiforge-frontend frontend
# Runtime settings: APIFORGE_BACKEND_URL (default http://backend:8081), APIFORGE_PROXY_TRUSTED
# (true only when nginx is reachable solely through a trusted proxy) and APIFORGE_PROXY_READ_TIMEOUT
# (default 300s; must exceed twice the backend's AI request timeout). No secret is involved.

FROM node:24.22.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Only APIFORGE_* variables and the resolvers (read from /etc/resolv.conf by the image's entrypoint) are
# substituted into the template; nginx's own $variables stay untouched. APIFORGE_BACKEND_URL is
# scheme://host:port without a path.
ENV APIFORGE_BACKEND_URL=http://backend:8081 \
    APIFORGE_PROXY_TRUSTED=false \
    APIFORGE_PROXY_READ_TIMEOUT=300s \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER=^(APIFORGE_|NGINX_LOCAL_RESOLVERS)
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
