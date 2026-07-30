FROM node:20 AS build-stage
WORKDIR /frontend
COPY package*.json /frontend/
RUN npm install
COPY ./ /frontend/
# CONFIG selects the Vite mode (e.g. production) which loads the matching
# .env.<mode> file. Defaults to production for GKE builds.
ARG CONFIG=production
RUN npm run build -- --mode "$CONFIG"

FROM nginx:1.27
# BACKEND_UPSTREAM is the backend Service host:port that nginx proxies
# /api and /ws to in production (e.g. mongodb-spring-demo-api:8080).
ARG BACKEND_UPSTREAM=mongodb-spring-demo-api:8080
COPY --from=build-stage /frontend/dist /usr/share/nginx/html
COPY ./nginx-custom.conf /etc/nginx/templates/default.conf.template
ENV BACKEND_UPSTREAM=${BACKEND_UPSTREAM}
# Only substitute BACKEND_UPSTREAM in the template; leave nginx runtime
# variables ($host, $uri, ...) untouched.
ENV NGINX_ENVSUBST_FILTER=BACKEND_UPSTREAM
