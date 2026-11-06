#!/usr/bin/env bash
# E4 回滚预案脚本。
#
# 用法：
#   ./rollback.sh <git-tag|commit> [备份目录] [选项]
#   ./rollback.sh --dry-run v1.1.0 backups/20261106_023000
#
# 选项：
#   --dry-run        只打印执行计划，不做任何变更
#   --no-rebuild     只切代码，不重建镜像（演练/同版本数据回滚用）
#   --no-backup      跳过「回滚前保险备份」（不推荐）
#   --no-data        只切代码，不恢复数据
#
# 流程：记录当前版本 → 回滚前保险备份 → git 切到目标版本 → 重建镜像 →
#       恢复指定备份的数据 → 等待健康 → 冒烟验证 → 写回滚日志。
#
# ⚠️ Flyway 约束：跨数据库迁移版本回滚时，必须同时恢复「该版本时点」的备份
#    （含 flyway_schema_history），否则新版库的迁移记录会让旧版代码启动失败。
#    脚本会检测迁移文件数量变化并给出警告。
set -uo pipefail
DEPLOY=$(cd "$(dirname "$0")" && pwd)
cd "$DEPLOY"
REPO=$(cd ../.. && pwd)
LOGDIR="$DEPLOY/rollback"
mkdir -p "$LOGDIR"

COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml}
HEALTH_URL=${HEALTH_URL:-http://localhost:8080/health}
HEALTH_CURL_OPTS=${HEALTH_CURL_OPTS:-}

DRY=0; REBUILD=1; DO_BACKUP=1; DO_DATA=1
REF=""; BACKUP=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --no-rebuild) REBUILD=0 ;;
    --no-backup) DO_BACKUP=0 ;;
    --no-data) DO_DATA=0 ;;
    *)
      if [ -z "$REF" ]; then REF="$arg"
      elif [ -z "$BACKUP" ]; then BACKUP="$arg"
      else echo "❌ 多余参数：$arg"; exit 1; fi ;;
  esac
done

[ -n "$REF" ] || { echo "用法: ./rollback.sh <git-tag|commit> [备份目录] [--dry-run|--no-rebuild|--no-backup|--no-data]"; exit 1; }

cd "$REPO"
CURRENT=$(git rev-parse --short HEAD)
CURRENT_REF=$(git symbolic-ref --short HEAD 2>/dev/null || echo "detached")
TARGET=$(git rev-parse --short "$REF" 2>/dev/null || echo "")
[ -n "$TARGET" ] || { echo "❌ 目标版本不存在：$REF"; exit 1; }

DIRTY=$(git status --porcelain | head -5)
[ -z "$DIRTY" ] || {
  echo "❌ 工作区有未提交变更，请先提交或清理后再回滚："
  echo "$DIRTY" | sed 's/^/   /'
  exit 1
}

# Flyway 跨版本警告
CUR_MIG=$(git ls-tree -r --name-only HEAD -- server/backend/src/main/resources/db/migration | wc -l)
TGT_MIG=$(git ls-tree -r --name-only "$REF" -- server/backend/src/main/resources/db/migration | wc -l)

echo "===== 回滚计划 ====="
echo "  当前版本   : $CURRENT ($CURRENT_REF)"
echo "  目标版本   : $TARGET ($REF)"
echo "  迁移文件   : 当前 $CUR_MIG 个 → 目标 $TGT_MIG 个"
echo "  数据恢复   : ${BACKUP:-（不恢复）}"
echo "  重建镜像   : $([ "$REBUILD" = 1 ] && echo 是 || echo 否)"
echo "  保险备份   : $([ "$DO_BACKUP" = 1 ] && echo 是 || echo 否)"
if [ "$CUR_MIG" != "$TGT_MIG" ]; then
  echo "  ⚠️  检测到跨迁移版本回滚：必须恢复『$REF 时点』的数据库备份（含 flyway_schema_history），"
  echo "      否则旧版代码会因存在未解析的新迁移而启动失败。"
fi
[ "$DRY" = 1 ] && { echo "  （--dry-run，未执行任何变更）"; exit 0; }

TS=$(date +%Y%m%d_%H%M%S)
LOG="$LOGDIR/rollback_${TS}.log"   # 绝对路径：下方已 cd 到仓库根
exec > >(tee -a "$LOG") 2>&1
echo "回滚日志：$LOG"
echo "开始时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
echo "操作人  ：$(whoami)@$(hostname)"

# 1) 回滚前保险备份
if [ "$DO_BACKUP" = 1 ]; then
  echo "[1/6] 生成回滚前保险备份 ..."
  (cd "$DEPLOY" && ./backup.sh) || { echo "❌ 保险备份失败，已中止回滚"; exit 1; }
  PRE=$(ls -1td "$DEPLOY"/backups/20*/ 2>/dev/null | head -1)
  echo "  保险备份：$PRE"
else
  PRE="（跳过）"
fi

# 2) 切换代码版本
echo "[2/6] 切换代码到 $TARGET ..."
git checkout --detach "$REF" || { echo "❌ 版本切换失败"; exit 1; }
echo "  当前 HEAD：$(git rev-parse --short HEAD)"

# 3) 重建镜像并重启
if [ "$REBUILD" = 1 ]; then
  echo "[3/6] 重建镜像并重启 ..."
  (cd "$DEPLOY" && docker compose -f "$COMPOSE_FILE" up -d --build) || {
    echo "❌ 重建失败，可尝试切回 $CURRENT_REF 恢复"; exit 1; }
else
  echo "[3/6] 跳过重建（--no-rebuild）"
fi

# 4) 恢复数据
if [ "$DO_DATA" = 1 ] && [ -n "$BACKUP" ]; then
  echo "[4/6] 恢复数据：$BACKUP ..."
  (cd "$DEPLOY" && ./restore.sh "$BACKUP" -y) || { echo "❌ 数据恢复失败"; exit 1; }
else
  echo "[4/6] 跳过数据恢复"
fi

# 5) 等待健康
echo "[5/6] 等待后端就绪 ..."
READY=0
for i in $(seq 1 60); do
  if curl -fsS $HEALTH_CURL_OPTS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
    READY=1; break
  fi
  sleep 2
done
[ "$READY" = 1 ] || { echo "❌ 回滚后 120s 内后端未就绪"; exit 1; }

# 6) 冒烟验证
echo "[6/6] 冒烟验证 ..."
SMOKE=0
HTTP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://localhost/ 2>/dev/null || echo 000)
[ "$HTTP" = "200" ] && SMOKE=1
[ "$SMOKE" = 1 ] && echo "  ✅ 前端 HTTP 200、后端 /health = UP" || echo "  ⚠️  前端 HTTP=$HTTP"

echo ""
echo "===== 回滚完成 ====="
echo "  原版本     : $CURRENT ($CURRENT_REF)"
echo "  现版本     : $TARGET"
echo "  保险备份   : $PRE"
echo "  恢复目标   : ${BACKUP:-（无）}"
echo "  结果       : $([ "$SMOKE" = 1 ] && echo 成功 || echo 部分成功（需人工复核）)"
echo "  切回原版本 : git checkout $CURRENT_REF && cd server/deploy && docker compose up -d --build"
echo "完成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
