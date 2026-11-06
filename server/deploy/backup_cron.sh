#!/usr/bin/env bash
# 定时备份策略（E1）：冷备 → 完整性校验 → 异地同步钩子 → 过期清理 → 失败告警
#
# crontab 样例（每日 02:30，业务低峰）：
#   30 2 * * * cd /opt/safetrans/server/deploy && ./backup_cron.sh >> backups/cron.log 2>&1
#
# 备份策略（写入运维手册）：每日 1 次全备、保留 14 天、异地留存 1 份；
# RPO ≤ 24h（若启用 binlog 可降至分钟级），RTO ≤ 30min。
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck disable=SC1091
source ./alert.sh
load_env .env

RETENTION=${BACKUP_RETENTION_DAYS:-14}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
LOG=backups/cron.log
mkdir -p backups
TS=$(date '+%Y-%m-%d %H:%M:%S')

echo "===== [$TS] 定时备份开始（保留 ${RETENTION} 天，编排 ${COMPOSE_FILE}）====="

fail() {
  echo "❌ $1"
  send_alert ERROR "定时备份失败" "$1"
  exit 1
}

# 1) 执行冷备（先停 frontend/backend 保证一致性，backup.sh 内部会自动拉回）
COLD_FLAG=${BACKUP_COLD:---cold}
if ! ./backup.sh "$COLD_FLAG" >> "$LOG" 2>&1; then
  fail "backup.sh 执行失败，详见 backups/cron.log"
fi

# 2) 定位本次产物并校验完整性
DIR=$(ls -1td backups/20*/ 2>/dev/null | head -1 | sed 's:/$::')
[ -n "$DIR" ] || fail "未找到备份目录"
for f in mysql.sql.gz redis.tar.gz minio.tar.gz MANIFEST.txt; do
  [ -s "$DIR/$f" ] || fail "备份文件缺失或为空：$DIR/$f"
done
# gzip 完整性校验（防止把损坏的备份当作可用）
gzip -t "$DIR/mysql.sql.gz" || fail "mysql.sql.gz 校验失败"
tar tzf "$DIR/redis.tar.gz" >/dev/null || fail "redis.tar.gz 校验失败"
tar tzf "$DIR/minio.tar.gz" >/dev/null || fail "minio.tar.gz 校验失败"
SIZE=$(du -sh "$DIR" | cut -f1)
echo "  ✓ 备份完整：$DIR（$SIZE），gzip/tar 校验通过"

# 3) 异地同步钩子（可选）：命令自行实现 rclone / aws s3 / rsync
if [ -n "${BACKUP_REMOTE_HOOK:-}" ]; then
  echo "  → 执行异地同步：$BACKUP_REMOTE_HOOK $DIR"
  if eval "$BACKUP_REMOTE_HOOK \"$DIR\"" >> "$LOG" 2>&1; then
    echo "  ✓ 异地同步完成"
  else
    fail "异地同步失败（本地备份仍有效）"
  fi
else
  echo "  ⚠️  未配置 BACKUP_REMOTE_HOOK，备份仅存本机（生产必须配置异地留存）"
fi

# 4) 过期清理（只删 backups/20* 目录，不触碰其他文件）
DELETED=0
while IFS= read -r old; do
  rm -rf "$old" && DELETED=$((DELETED + 1))
done < <(find backups -maxdepth 1 -type d -name '20*' -mtime "+${RETENTION}" 2>/dev/null)
echo "  ✓ 清理过期备份 ${DELETED} 份（> ${RETENTION} 天）"

echo "===== [$(date '+%Y-%m-%d %H:%M:%S')] 定时备份完成 ====="
exit 0
