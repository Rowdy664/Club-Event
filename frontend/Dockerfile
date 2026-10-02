# syntax=docker/dockerfile:1

##############################################
# Stage 1 — Build the Vite production bundle
##############################################
FROM node:20-alpine AS build
WORKDIR /app

# Install dependencies from the lockfile for reproducible builds.
COPY package*.json ./
RUN npm ci

# Build-time public config. These are compiled into the bundle, so they must
# NOT contain secrets. Defaults keep the client same-origin, letting nginx
# proxy /api and /ws to the backend (see nginx.conf).
ARG VITE_API_BASE_URL=/api
ARG VITE_WS_URL=/ws
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_WS_URL=$VITE_WS_URL

COPY . .
RUN npm run build

##############################################
# Stage 2 — Serve static files with nginx
##############################################
FROM nginx:1.27-alpine AS runtime

# Render API_UPSTREAM_ORIGIN at container startup. Compose uses the internal service
# hostname; override this default for other deployment environments as needed.
ENV API_UPSTREAM_ORIGIN=https://club-event-production.up.railway.app
ENV NGINX_ENVSUBST_FILTER=^API_UPSTREAM_ORIGIN$
COPY nginx.conf /etc/nginx/templates/default.conf.template

# Static assets produced by Vite.
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ > /dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
