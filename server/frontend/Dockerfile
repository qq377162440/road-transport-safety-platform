# 前端多阶段构建：node 构建 → nginx 托管
FROM node:22-alpine AS build
WORKDIR /app

# 固定 pnpm 版本，与 pnpm-lock.yaml（lockfileVersion 9.0）保持一致。
# 不固定时 Corepack 会拉取最新 pnpm 12，其 minimumReleaseAge 供应链策略
# 会拒绝 lockfile 中「刚发布」的包（如 vite），导致镜像构建失败。
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate

COPY package.json pnpm-lock.yaml* ./
# 严格按 lockfile 安装：不一致时直接失败，不静默重新解析依赖
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:alpine
# 复制构建产物
COPY --from=build /app/dist /usr/share/nginx/html
# nginx 配置：反向代理 /api 到后端
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
