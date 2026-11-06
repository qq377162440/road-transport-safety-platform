#!/usr/bin/env bash
# 渲染生产 nginx 配置：把模板中的 __DOMAIN__ 替换为实际域名。
#
# 用法：DOMAIN=example.com ./render_nginx_conf.sh
#       或先在 .env 中设置 DOMAIN 后直接 ./render_nginx_conf.sh
#
# 产物 nginx.prod.conf 已 gitignore（含实际域名），禁止入库。
set -euo pipefail
cd "$(dirname "$0")"

# 读取 .env 中的 DOMAIN（不覆盖命令行已导出的值）
if [ -z "${DOMAIN:-}" ] && [ -f .env ]; then
  DOMAIN=$(grep -E '^DOMAIN=' .env | head -1 | cut -d= -f2- | tr -d "\"'" | xargs || true)
fi
DOMAIN=${DOMAIN:-localhost}

if [ "$DOMAIN" = "localhost" ]; then
  echo "⚠️  DOMAIN 未设置，使用 localhost（仅限本地演练，不可用于生产）" >&2
fi

TPL=../frontend/nginx.prod.conf.tpl
[ -f "$TPL" ] || { echo "❌ 模板不存在：$TPL"; exit 1; }
sed "s/__DOMAIN__/${DOMAIN}/g" "$TPL" > nginx.prod.conf

echo "✅ 已生成 nginx.prod.conf（server_name=${DOMAIN}）"
grep -n "server_name" nginx.prod.conf
