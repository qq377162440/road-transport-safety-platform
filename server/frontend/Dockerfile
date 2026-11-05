# 前端多阶段构建：node 构建 → nginx 托管
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json pnpm-lock.yaml* ./
RUN corepack enable && pnpm install --frozen-lockfile || pnpm install
COPY . .
RUN pnpm build

FROM nginx:alpine
# 复制构建产物
COPY --from=build /app/dist /usr/share/nginx/html
# nginx 配置：反向代理 /api 到后端
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
