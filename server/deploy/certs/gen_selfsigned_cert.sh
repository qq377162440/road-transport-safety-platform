#!/usr/bin/env bash
# 生成自签证书（仅用于本地/预发 HTTPS 链路演练，绝不可用于生产）。
# 生产请把真实 CA 签发的 fullchain.pem / privkey.pem 放到本目录。
#
# 用法：DOMAIN=example.com ./certs/gen_selfsigned_cert.sh
set -euo pipefail
cd "$(dirname "$0")"

DOMAIN=${DOMAIN:-localhost}
# 脚本本身位于 certs/ 内，cd "$(dirname "$0")" 后产物直接写当前目录
OUT=.

if [ -f "$OUT/fullchain.pem" ] && [ -f "$OUT/privkey.pem" ]; then
  echo "ℹ️  证书已存在，跳过生成（如需重建请先删除 certs/*.pem）"
else
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout "$OUT/privkey.pem" \
    -out "$OUT/fullchain.pem" \
    -subj "/C=CN/ST=Guangdong/L=Foshan/O=SafeTrans/CN=${DOMAIN}" \
    -addext "subjectAltName=DNS:${DOMAIN},DNS:localhost,IP:127.0.0.1" \
    >/dev/null 2>&1
  chmod 600 "$OUT/privkey.pem"
  echo "✅ 自签证书已生成（CN=${DOMAIN}，有效期 365 天）"
fi

echo "证书指纹："
openssl x509 -in "$OUT/fullchain.pem" -noout -subject -dates -fingerprint -sha256 | sed 's/^/  /'
echo ""
echo "⚠️  自签证书仅供演练。生产必须替换为真实证书，否则浏览器会报不安全。"
