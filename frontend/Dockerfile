FROM node:26-alpine AS build
WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
ARG VITE_BUSINESS_ZONE=Asia/Shanghai
ARG VITE_LEGACY_TIMESTAMP_OFFSET=Z
ENV VITE_BUSINESS_ZONE=${VITE_BUSINESS_ZONE}
ENV VITE_LEGACY_TIMESTAMP_OFFSET=${VITE_LEGACY_TIMESTAMP_OFFSET}
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
