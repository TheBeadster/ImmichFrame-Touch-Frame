FROM node:22-alpine

WORKDIR /app
COPY package.json ./
COPY package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY server.js ./
COPY public ./public

ENV NODE_ENV=production \
    PORT=8081 \
    IMMICHFRAME_URL=http://immichframe:8080 \
    COMPOSER_CONFIG=/config/frame.yml

EXPOSE 8081
USER node
CMD ["node", "server.js"]

