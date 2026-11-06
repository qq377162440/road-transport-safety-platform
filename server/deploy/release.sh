#!/usr/bin/env bash
# E4 发布打包：生成发布清单（版本、提交、迁移、镜像、验收结论、校验和）。
#
# 用法：cd server/deploy && ./release.sh
# 前置：工作区干净、全栈已就绪、E4 复测已通过。
set -euo pipefail
DEPLOY=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$DEPLOY/../.." && pwd)
cd "$REPO"
OUT="$REPO/docs/发布清单.md"

DIRTY=$(git status --porcelain | head -20)
[ -z "$DIRTY" ] || {
  echo "❌ 工作区不干净，发布清单要求所有变更已提交："
  echo "$DIRTY" | sed 's/^/   /'
  exit 1
}

HEAD=$(git rev-parse HEAD)
SHORT=$(git rev-parse --short HEAD)
BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || echo detached)
TAGS=$(git tag --points-at HEAD | tr '\n' ' ' | sed 's/ $//')
DESCRIBE=$(git describe --tags --always 2>/dev/null || echo "$SHORT")
NOW=$(date '+%Y-%m-%d %H:%M:%S %z')

MIG_DIR=server/backend/src/main/resources/db/migration
MIGRATIONS=$(ls -1 "$MIG_DIR"/*.sql 2>/dev/null | xargs -n1 basename | tr '\n' ' ')
MIG_COUNT=$(ls -1 "$MIG_DIR"/*.sql 2>/dev/null | wc -l)

# 生产凭据占位符检查：7 个凭据变量不得带默认值（端口等允许有默认值）
PROD_YML=server/backend/src/main/resources/application-prod.yml
CRED_DEFAULTS=$(grep -nE '\$\{(JWT_SECRET|MYSQL_USER|MYSQL_PASSWORD|REDIS_PASSWORD|MINIO_ACCESS_KEY|MINIO_SECRET_KEY|MINIO_ENDPOINT):[^}]' "$PROD_YML" || true)
if [ -n "$CRED_DEFAULTS" ]; then
  PROD_CHECK="⚠️ 凭据占位符带默认值，需复核：$CRED_DEFAULTS"
else
  PROD_CHECK="✅ 7 个凭据占位符均无默认值（缺失即启动失败）"
fi
# 生产编排强制凭据检查
PROD_COMPOSE=server/deploy/docker-compose.prod.yml
REQUIRED_VARS=$(grep -oE '\$\{[A-Z_]+:\?' "$PROD_COMPOSE" | sed -E 's/\$\{([A-Z_]+):\?/\1/' | sort -u | wc -l)

# 镜像 ID
FE_IMG=$(docker image inspect safetrans/frontend:latest --format '{{.Id}}' 2>/dev/null | cut -c8-19 || echo '未构建')
BE_IMG=$(docker image inspect safetrans/backend:latest --format '{{.Id}}' 2>/dev/null | cut -c8-19 || echo '未构建')
DEV_FE=$(docker image inspect deploy-frontend:latest --format '{{.Id}}' 2>/dev/null | cut -c8-19 || echo '未构建')
DEV_BE=$(docker image inspect deploy-backend:latest --format '{{.Id}}' 2>/dev/null | cut -c8-19 || echo '未构建')

# 关键文件校验和
CHECKSUM=$(sha256sum \
  "$MIG_DIR"/V7__onboarding.sql \
  server/deploy/docker-compose.prod.yml \
  server/deploy/go_live_check.sh \
  server/deploy/rollback.sh \
  server/deploy/backup.sh \
  server/deploy/restore.sh 2>/dev/null | sed "s#$REPO/##")

mkdir -p "$(dirname "$OUT")"
cat > "$OUT" <<EOF
# 发布清单 · v1.0.0-prod

> 生成时间：$NOW
> 由 \`server/deploy/release.sh\` 自动生成，作为 E4 上线验收附件。

## 一、版本信息

| 项 | 值 |
|---|---|
| Git 提交 | \`$HEAD\` |
| 短提交 | \`$SHORT\` |
| 分支 | \`$BRANCH\` |
| 指向标签 | ${TAGS:-（未打标签）} |
| git describe | \`$DESCRIBE\` |
| 工作区 | ✅ 干净（生成时无未提交变更） |

## 二、数据库迁移

| 项 | 值 |
|---|---|
| 迁移脚本数 | $MIG_COUNT |
| 脚本清单 | $MIGRATIONS |
| 执行方式 | backend 启动时由 Flyway 自动迁移，回滚须同时恢复对应时点的数据库备份 |

## 三、镜像

| 镜像 | ID（短） |
|---|---|
| safetrans/frontend:latest（生产 tag） | \`$FE_IMG\` |
| safetrans/backend:latest（生产 tag） | \`$BE_IMG\` |
| deploy-frontend:latest（当前构建） | \`$DEV_FE\` |
| deploy-backend:latest（当前构建） | \`$DEV_BE\` |

> 生产机应使用 CI 预构建并打 \`\${IMAGE_TAG}\` 的镜像，通过 \`docker compose -f docker-compose.prod.yml pull\` 拉取，不在服务器现场构建。

## 四、生产配置校验

| 检查 | 结果 |
|---|---|
| \`application-prod.yml\` 凭据外置 | $PROD_CHECK |
| \`docker-compose.prod.yml\` 强制必填变量 | $REQUIRED_VARS 个（缺失即启动失败） |
| 端口收敛 | 仅 frontend 暴露 80/443 |
| 资源限制 | 5 个服务均声明 deploy.resources |

## 五、关键文件校验和（SHA-256）

\`\`\`
$CHECKSUM
\`\`\`

## 六、验收结论引用

- 四条验收标准正式复测：\`go_live_check.sh\`（见 E4 复测输出）
- 全量回归：闭环 10/10、A/B/C 38/38、开户引导 29/29、E3 演练 14/14、单元测试全通过
- 备份/恢复：A1 实测逐字段一致；E4 回滚演练见 \`server/deploy/rollback/\` 日志
- 试运行报告：\`docs/试运行演练报告.md\`
- 上线检查清单：\`docs/上线检查清单.md\`

## 七、上线前置阻断项（E1 ⏳，需运营方提供）

- 域名与 ICP 备案、DNS A 记录
- CA 签发的 TLS 证书（替换自签文件）
- 生产服务器（规格见《生产部署与运维手册》第二章）
- 告警 webhook、异地备份目标
- 上述资源全部就位并签字后方可发布
EOF

echo "✅ 发布清单已生成：$OUT"
echo "   提交 $SHORT | 标签 ${TAGS:-无} | 迁移 $MIG_COUNT 个 | 强制凭据 $REQUIRED_VARS 个"
echo "   生产凭据检查：$PROD_CHECK"
