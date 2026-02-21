FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
RUN apk add --no-cache gettext
ENV API_UPSTREAM=http://backend.ml-platform.svc.cluster.local:8080
ENV MLFLOW_UPSTREAM=http://mlflow.ml-platform.svc.cluster.local:5000
ENV KEYCLOAK_UPSTREAM=http://keycloak.ml-platform.svc.cluster.local:8080
ENV JUPYTERHUB_UPSTREAM=http://proxy-public.ml-platform.svc.cluster.local:80
COPY nginx/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.sh /docker-entrypoint.d/10-ml-platform-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/10-ml-platform-runtime-config.sh
COPY --from=build /app/dist/ml-platform-frontend /usr/share/nginx/html
EXPOSE 8080
