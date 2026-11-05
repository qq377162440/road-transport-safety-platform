#!/usr/bin/env bash
# 从指定备份目录恢复全栈数据（覆盖式恢复，会清空当前 Redis / MinIO 数据卷）。
#
# 用法：./restore.sh backups/20261106_023000 [-y]
#   -y / ASSUME_YES=1 跳过交互确认，供自动化验收使用。
set -euo pipefail
cd "$(dirname "$0")"

BACKUP=${1:-}
[ -n "$BACKUP" ] || { echo "用法: ./restore.sh <备份目录> [-y]"; ls -1 backups/ 2>/dev/null | tail -10; exit 1; }
[ -d "$BACKUP" ] || { echo "❌ 备份目录不存在：$BACKUP"; exit 1; }
for f in mysql.sql.gz redis.tar.gz minio.tar.gz; do
  [ -f "$BACKUP/$f" ] || { echo "❌ 备份缺少文件：$f"; exit 1; }
done
BACKUP=$(cd "$BACKUP" && pwd)

MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root123}
ASSUME=${2:-${ASSUME_YES:-}}

if [ "$ASSUME" != "-y" ] && [ "$ASSUME" != "1" ]; then
  echo "⚠️  即将用 $BACKUP 覆盖当前全栈数据（MySQL / Redis / MinIO）"
  read -r -p "确认请输入 yes: " CONFIRM
  [ "$CONFIRM" = "yes" ] || { echo "已取消"; exit 1; }
fi

wipe_volume() {
  docker run --rm -v "$1":/data alpine:3.20 \
    sh -c "find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true" >/dev/null
}

echo "[1/5] 暂停写入方（backend / frontend / redis / minio）..."
docker compose stop backend frontend redis minio >/dev/null

echo "[2/5] 恢复 MySQL ..."
gunzip -c "$BACKUP/mysql.sql.gz" | \
  docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"

echo "[3/5] 恢复 Redis 数据卷 ..."
wipe_volume safetrans-redis-data
docker run --rm -v safetrans-redis-data:/data -v "$BACKUP":/backup:ro \
  alpine:3.20 tar xzf /backup/redis.tar.gz -C /data
docker compose start redis >/dev/null

echo "[4/5] 恢复 MinIO 数据卷 ..."
wipe_volume safetrans-minio-data
docker run --rm -v safetrans-minio-data:/data -v "$BACKUP":/backup:ro \
  alpine:3.20 tar xzf /backup/minio.tar.gz -C /data
docker compose start minio >/dev/null

echo "[5/5] 拉起 backend / frontend 并等待健康 ..."
docker compose start backend frontend >/dev/null
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 http://localhost:8080/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "✅ 恢复完成，后端已就绪（/health = UP）"
    exit 0
  fi
  sleep 2
done
echo "❌ 恢复后后端 120s 内未就绪，请查看 docker compose logs backend"
exit 1
