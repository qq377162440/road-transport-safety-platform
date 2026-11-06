#!/usr/bin/env bash
# 监控巡检与告警（E1）：容器健康 / 入口可达 / 磁盘 / 备份新鲜度 / 证书有效期 / 重启次数
#
# crontab 样例（每 5 分钟）：
#   */5 * * * * cd /opt/safetrans/server/deploy && ./monitor.sh >> monitor/cron.log 2>&1
#
# 退出码：0=全部正常；1=存在异常（已推送告警）。可直接接 Nagios/Zabbix 等外部监控。
set -uo pipefail
cd "$(dirname "$0")"
# shellcheck disable=SC1091
source ./alert.sh
load_env .env

BASE=${MONITOR_BASE_URL:-https://localhost}
INSECURE=${CURL_INSECURE:--k}          # 自签证书演练时 -k；生产真证书用 ""
DISK_WARN=${DISK_WARN_PERCENT:-85}
BACKUP_MAX_AGE=${BACKUP_MAX_AGE_HOURS:-30}
CERT_WARN_DAYS=${CERT_WARN_DAYS:-14}
RESTART_WARN=${RESTART_WARN_COUNT:-5}
START_GRACE=${START_GRACE_SECONDS:-60}   # 发版/备份重启后的健康检查宽限期
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}

mkdir -p monitor
REPORT=monitor/last_report.txt
: > "$REPORT"
PASS=0; FAILS=()

log() { echo "$1" | tee -a "$REPORT"; }
ok()  { log "  ✅ $1"; PASS=$((PASS + 1)); }
bad() { log "  ❌ $1"; FAILS+=("$1"); }

log "===== 巡检 $(date '+%Y-%m-%d %H:%M:%S %z') | 目标 ${BASE} ====="

# 1) 容器状态与健康
log "[1/6] 容器状态"
MAP=$(docker compose -f "$COMPOSE_FILE" ps -a --format '{{.Service}}|{{.State}}|{{.Status}}' 2>/dev/null || true)
if [ -z "$MAP" ]; then
  bad "无法读取 compose 服务状态（编排文件 ${COMPOSE_FILE} 或服务未启动）"
else
  for svc in frontend backend mysql redis minio; do
    line=$(echo "$MAP" | grep "^${svc}|" | head -1)
    if [ -z "$line" ]; then bad "$svc 容器不存在"; continue; fi
    state=$(echo "$line" | cut -d'|' -f2)
    if [ "$state" != "running" ]; then bad "$svc 未运行（$line）"; continue; fi
    if echo "$line" | grep -q '(healthy)'; then
      ok "$svc running / healthy"
    elif echo "$line" | grep -q 'health: starting'; then
      # 刚重启（发版或冷备）时探针处于 starting，给予宽限期，避免误告警
      started=$(docker inspect -f '{{.State.StartedAt}}' "safetrans-$svc" 2>/dev/null || echo '')
      started_ts=$(date -d "$started" +%s 2>/dev/null || echo 0)
      age=$(( $(date +%s) - started_ts ))
      if [ "$age" -le "$START_GRACE" ]; then
        ok "$svc 启动宽限期内（${age}s / ${START_GRACE}s，探针 starting）"
      else
        bad "$svc 健康检查超过 ${START_GRACE}s 仍未通过（$line）"
      fi
    else
      bad "$svc 运行中但健康检查未通过（$line）"
    fi
  done
fi

# 2) 入口可达性（HTTP 301 → HTTPS）
log "[2/6] 入口可达性"
HTTP_CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "http://127.0.0.1/" 2>/dev/null || echo 000)
[ "$HTTP_CODE" = "301" ] || [ "$HTTP_CODE" = "200" ] \
  && ok "HTTP 入口响应 ${HTTP_CODE}" || bad "HTTP 入口异常，code=${HTTP_CODE}"
HTTPS_CODE=$(curl -sS $INSECURE -o /dev/null -w '%{http_code}' --max-time 8 "${BASE}/" 2>/dev/null || echo 000)
[ "$HTTPS_CODE" = "200" ] && ok "HTTPS 首页 200" || bad "HTTPS 首页异常，code=${HTTPS_CODE}"

# 3) 后端健康（经 nginx 反代，验证全链路而非仅容器端口）
log "[3/6] 后端健康"
HEALTH=$(curl -sS $INSECURE --max-time 8 "${BASE}/health" 2>/dev/null || echo '')
echo "$HEALTH" | grep -q '"status":"UP"' \
  && ok "/health = UP" || bad "/health 未返回 UP（响应：${HEALTH:0:120}）"

# 4) 磁盘
log "[4/6] 磁盘使用率"
DISK=$(df -P / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
[ "${DISK:-0}" -lt "$DISK_WARN" ] \
  && ok "根分区 ${DISK}% < 阈值 ${DISK_WARN}%" \
  || bad "根分区 ${DISK}% 已达阈值 ${DISK_WARN}%"

# 5) 备份新鲜度
log "[5/6] 备份新鲜度"
LATEST=$(ls -1td backups/20*/ 2>/dev/null | head -1)
if [ -z "$LATEST" ]; then
  bad "尚未产生任何备份"
else
  AGE_SEC=$(( $(date +%s) - $(stat -c %Y "$LATEST") ))
  AGE_H=$(( AGE_SEC / 3600 ))
  [ "$AGE_H" -le "$BACKUP_MAX_AGE" ] \
    && ok "最近备份 ${AGE_H}h 前（$LATEST）" \
    || bad "最近备份已 ${AGE_H}h，超过 ${BACKUP_MAX_AGE}h（$LATEST）"
fi

# 6) 证书有效期 + 异常重启
log "[6/6] 证书与重启次数"
CERT=certs/fullchain.pem
if [ -f "$CERT" ]; then
  END=$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)
  END_TS=$(date -d "$END" +%s 2>/dev/null || echo 0)
  DAYS=$(( (END_TS - $(date +%s)) / 86400 ))
  [ "$DAYS" -ge "$CERT_WARN_DAYS" ] \
    && ok "证书剩余 ${DAYS} 天（≥ ${CERT_WARN_DAYS}）" \
    || bad "证书剩余 ${DAYS} 天，需尽快续期"
else
  bad "证书文件不存在：$CERT"
fi
for c in safetrans-frontend safetrans-backend safetrans-mysql safetrans-redis safetrans-minio; do
  RC=$(docker inspect -f '{{.RestartCount}}' "$c" 2>/dev/null || echo 0)
  if [ "${RC:-0}" -gt "$RESTART_WARN" ]; then bad "$c 重启次数 ${RC} > ${RESTART_WARN}"; fi
done
[ "${#FAILS[@]}" -eq 0 ] && ok "各容器重启次数均在阈值内" || true

log ""
if [ "${#FAILS[@]}" -eq 0 ]; then
  log "✅ 巡检通过：${PASS} 项全部正常"
  exit 0
else
  log "❌ 巡检发现 ${#FAILS[@]} 项异常："
  printf '%s\n' "${FAILS[@]}" | sed 's/^/    - /' | tee -a "$REPORT"
  DETAIL=$(printf '%s; ' "${FAILS[@]}")
  send_alert ERROR "生产巡检异常（${#FAILS[@]} 项）" "$DETAIL"
  exit 1
fi
