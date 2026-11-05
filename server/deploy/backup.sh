#!/usr/bin/env bash
# 全栈数据备份：MySQL（逻辑全备）+ Redis（RDB 快照）+ MinIO（数据卷快照）
# 输出到 backups/<时间戳>/，含 MANIFEST.txt。
#
# 用法：cd server/deploy && ./backup.sh
# 默认热备（不停服）；生产环境建议在业务低峰期执行，或加 --cold 先停写入方。
set -euo pipefail
cd "$(dirname "$0")"

MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root123}
COLD=${1:-}
TS=$(date +%Y%m%d_%H%M%S)
OUT="backups/$TS"
mkdir -p "$OUT"

if [ "$COLD" = "--cold" ]; then
  echo "[0/3] 冷备模式：暂停 frontend / backend 写入"
  docker compose stop frontend backend
fi

echo "[1/3] 备份 MySQL ..."
docker compose exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines --triggers \
  --add-drop-database --default-character-set=utf8mb4 \
  --databases safetrans | gzip > "$OUT/mysql.sql.gz"

echo "[2/3] 备份 Redis（SAVE 后打包 RDB） ..."
docker compose exec -T redis redis-cli SAVE >/dev/null
docker run --rm \
  -v safetrans-redis-data:/data:ro \
  -v "$PWD/$OUT":/out \
  alpine:3.20 tar czf /out/redis.tar.gz -C /data .

echo "[3/3] 备份 MinIO 数据卷 ..."
docker run --rm \
  -v safetrans-minio-data:/data:ro \
  -v "$PWD/$OUT":/out \
  alpine:3.20 tar czf /out/minio.tar.gz -C /data .

if [ "$COLD" = "--cold" ]; then
  echo "[*] 恢复 frontend / backend"
  docker compose start frontend backend
fi

GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
cat > "$OUT/MANIFEST.txt" <<EOF
备份时间     : $(date '+%Y-%m-%d %H:%M:%S %z')
Git 提交     : $GIT_COMMIT
卷名         : safetrans-mysql-data / safetrans-redis-data / safetrans-minio-data
mysql.sql.gz : MySQL 逻辑全备（含 drop database，导入即覆盖）
redis.tar.gz : Redis RDB 快照
minio.tar.gz : MinIO 数据卷快照
恢复命令     : ./restore.sh $OUT
EOF

echo ""
echo "✅ 备份完成：$OUT"
ls -lh "$OUT"
