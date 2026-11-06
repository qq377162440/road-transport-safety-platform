#!/usr/bin/env bash
# 告警发送公共函数（E1）。被 monitor.sh / backup_cron.sh / go_live_check.sh 复用。
#
# 支持企业微信 / 钉钉 / 飞书机器人，也支持原生 JSON 的自定义 webhook。
# 在 .env 中配置：
#   ALERT_WEBHOOK=https://...      留空则只落本地日志，不发送
#   ALERT_WEBHOOK_TYPE=wecom|dingtalk|feishu|generic
set -u

ALERT_LOG="${ALERT_LOG:-monitor/alerts.log}"

send_alert() {
  local level="${1:-WARN}" title="${2:-告警}" detail="${3:-}"
  local ts
  ts=$(date '+%Y-%m-%d %H:%M:%S %z')
  mkdir -p "$(dirname "$ALERT_LOG")"
  printf '[%s] [%s] %s | %s\n' "$ts" "$level" "$title" "$detail" >> "$ALERT_LOG"

  if [ -z "${ALERT_WEBHOOK:-}" ]; then
    echo "  ⚠️  ${title}（未配置 ALERT_WEBHOOK，仅记录本地日志）"
    return 0
  fi

  local payload
  case "${ALERT_WEBHOOK_TYPE:-generic}" in
    wecom)
      payload=$(printf '{"msgtype":"text","text":{"content":"[%s] %s\\n%s"}}' "$level" "$title" "$detail") ;;
    dingtalk)
      payload=$(printf '{"msgtype":"text","text":{"content":"[%s] %s\\n%s"}}' "$level" "$title" "$detail") ;;
    feishu)
      payload=$(printf '{"msg_type":"text","content":{"text":"[%s] %s\\n%s"}}' "$level" "$title" "$detail") ;;
    *)
      payload=$(printf '{"level":"%s","title":"%s","detail":"%s","time":"%s"}' "$level" "$title" "$detail" "$ts") ;;
  esac

  curl -sS --max-time 10 -X POST -H 'Content-Type: application/json' \
    -d "$payload" "$ALERT_WEBHOOK" >/dev/null 2>&1 \
    && echo "  📣 告警已推送（${ALERT_WEBHOOK_TYPE:-generic}）" \
    || echo "  ❌ 告警推送失败（webhook 不可达），详情见 $ALERT_LOG"
}

# 从 .env 加载变量（不覆盖已导出的值）
load_env() {
  local env_file="${1:-.env}"
  if [ -f "$env_file" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$env_file"
    set +a
  fi
}
