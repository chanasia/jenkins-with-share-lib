FROM node:22-alpine AS build-stage

ARG BUILD_CONFIGURATION=production

WORKDIR /app

COPY package*.json ./

RUN npm install --legacy-peer-deps

COPY . .

RUN npm run build -- --configuration ${BUILD_CONFIGURATION} || (echo "Build failed" && tail -n 100 /root/.npm/_logs/*-debug.log)

FROM nginx:alpine AS production-stage

COPY --from=build-stage /app/dist/kingpac-adm-app/browser /usr/share/nginx/html

EXPOSE 80

# Start Nginx server
CMD ["nginx", "-g", "daemon off;"]