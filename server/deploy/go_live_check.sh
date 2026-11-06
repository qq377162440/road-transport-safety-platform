#!/bin/bash
# E4 上线验收正式复测（在 compose 网络内的 e2e 容器中运行）
#   docker compose --profile test run --rm e2e ./go_live_check.sh
#
# 内容：
#   0) 基线与上线前置（健康检查、接口文档、操作日志、前端页面）
#   1) 四条验收标准正式复测（需求文档 §十，带计时与 PASS/FAIL 断言）
#   2) 三套既有回归套件（10 项闭环 / 38 项全链路 / 29 项开户引导）
#   3) 汇总与退出码（0=可上线，1=存在阻断项）
set -uo pipefail

BASE=${BASE_URL:-http://localhost:8080}
REDIS_HOST=${REDIS_HOST:-localhost}
FRONTEND=${FRONTEND_URL:-http://frontend}
PASS=0; FAIL=0
ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
jget() { python3 -c "$1" 2>/dev/null || echo ""; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi; }
ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

captcha_code() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h "$REDIS_HOST" get "captcha:$1" 2>/dev/null | tr -d '\r\n'
  else
    docker exec safetrans-redis redis-cli get "captcha:$1" 2>/dev/null | tr -d '\r\n'
  fi
}
login_as() {
  local user=$1 pass=$2 cap capid code
  cap=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
  capid=$(echo "$cap" | jget "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
  code=$(captcha_code "$capid")
  curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\",\"captchaId\":\"$capid\",\"captcha\":\"$code\"}"
}

echo "=============================================="
echo "  E4 上线验收正式复测  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="
echo "BASE=$BASE"

# 对齐匿名接口限流窗口：上一轮复测遗留的固定窗口计数会让本轮登录被 429 误伤。
# 不删除计数键（不绕过限流），只等窗口自然过期，使上线复测可从任意时点重复运行。
wait_rate_window() {
  command -v redis-cli >/dev/null 2>&1 || return 0
  local max_ttl=0 ttl k
  while IFS= read -r k; do
    [ -n "$k" ] || continue
    ttl=$(redis-cli -h "$REDIS_HOST" ttl "$k" 2>/dev/null)
    case "$ttl" in
      ''|*[!0-9]*) continue ;;
      *) [ "$ttl" -gt "$max_ttl" ] && max_ttl=$ttl ;;
    esac
  done < <(redis-cli -h "$REDIS_HOST" --scan --pattern 'rate:auth:*' 2>/dev/null)
  if [ "$max_ttl" -gt 0 ]; then
    echo "  ⏳ 对齐限流窗口：等待 ${max_ttl}s（上一轮计数自然过期，不绕过限流）"
    sleep $((max_ttl + 1))
  fi
}

for i in $(seq 1 60); do
  curl -fsS --max-time 3 "$BASE/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 2
done
wait_rate_window

# ================= 0. 基线与上线前置 =================
echo "----- 0. 基线与上线前置 -----"
H=$(curl -fsS --max-time 5 "$BASE/health" 2>/dev/null || echo '')
echo "$H" | grep -q '"status":"UP"' && ok "后端 /health = UP" || bad "后端未就绪"

DOC=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE/v3/api-docs")
[ "$DOC" = "200" ] && ok "Swagger 接口文档可访问" || bad "接口文档不可达 HTTP=$DOC"

MH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$FRONTEND/m/check")
[ "$MH" = "200" ] && ok "适老化三检 H5 页面可达" || bad "H5 页面不可达 HTTP=$MH"
OH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$FRONTEND/m/onboard")
[ "$OH" = "200" ] && ok "企业接入引导页可达" || bad "接入引导页不可达 HTTP=$OH"

ENT=$(login_as aa_gaozhou 123456)
ET=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
PRO=$(login_as bb_provider 123456)
PT=$(echo "$PRO" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
ADM=$(login_as cc_admin 123456)
AT=$(echo "$ADM" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$ET" ] && [ -n "$PT" ] && [ -n "$AT" ] && ok "三角色登录正常" || bad "角色登录失败"
AE="Authorization: Bearer $ET"; AP="Authorization: Bearer $PT"; AA="Authorization: Bearer $AT"

LOG=$(curl -fsS "$BASE/api/biz/sys_oper_log?pageSize=1" -H "$AA")
LC=$(echo "$LOG" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$LC" = "0" ] && ok "操作日志可查询（审计留痕）" || bad "操作日志查询异常"

BAD=$(curl -fsS "$BASE/api/biz/__illegal_table__" -H "$AE")
BC=$(echo "$BAD" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$BC" = "2003" ] && ok "非法参数返回明确错误码（2003）" || bad "错误码不规范"

# ================= 1. 四条验收标准正式复测 =================
echo "----- 1. 四条验收标准（需求 §十）-----"

# 标准 1：30 秒三检（用演练专用 vehicle_id，避免与回归套件的任务冲突）
T0=$(ms)
CT=$(curl -fsS -X POST "$BASE/api/flow/check/task" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"vehicle_id\":999001,\"driver_id\":6001,\"start_date\":\"$(date +%F)\",\"status\":\"ongoing\"}")
CID=$(echo "$CT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
CR=$(curl -fsS -X POST "$BASE/api/flow/check/record" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"task_id\":$CID,\"phase\":\"out\",\"result\":\"pass\"}")
CRC=$(echo "$CR" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
T1=$(ms); E1=$((T1-T0))
if [ "$CRC" = "0" ] && [ "$E1" -lt 30000 ]; then
  ok "标准① 30 秒完成一次三检：${E1}ms（< 30000ms）"
else
  bad "标准① 三检复测失败：${E1}ms / code=$CRC"
fi

# 标准 2：5 分钟调出完整台账（只读接口，避免影响后续回归）
T0=$(ms)
OV=$(curl -fsS "$BASE/api/flow/archive/overview?bizType=GENERAL" -H "$AE")
BOX=$(echo "$OV" | jget "import sys,json; print(json.load(sys.stdin)['data']['boxCount'])")
MIS=$(curl -fsS "$BASE/api/flow/archive/missing?bizType=GENERAL" -H "$AE")
MC=$(echo "$MIS" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
RD=$(curl -fsS "$BASE/api/onboarding/readiness" -H "$AE")
RC=$(echo "$RD" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
T1=$(ms); E2=$((T1-T0))
if [ "$BOX" = "10" ] && [ "$MC" = "0" ] && [ "$RC" = "0" ] && [ "$E2" -lt 300000 ]; then
  ok "标准② 5 分钟调出完整台账：${E2}ms（10 盒全景 + 缺项 + 归档自检）"
else
  bad "标准② 调档复测失败：box=$BOX missing=$MC readiness=$RC ${E2}ms"
fi

# 标准 3：一眼看出最紧急风险
T0=$(ms)
DR=$(curl -fsS "$BASE/api/flow/dashboard/risk" -H "$AE")
DS=$(echo "$DR" | jget "import sys,json; d=json.load(sys.stdin)['data']; print(d.get('safetyScore','missing'))")
DC=$(echo "$DR" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
T1=$(ms); E3=$((T1-T0))
if [ "$DC" = "0" ] && [ "$DS" != "missing" ] && [ -n "$DS" ]; then
  ok "标准③ 一眼看出最紧急风险：${E3}ms（安全分=$DS）"
else
  bad "标准③ 驾驶舱复测失败：code=$DC score=$DS"
fi

# 标准 4：服务商代录补齐台账
T0=$(ms)
CLT=$(curl -fsS -X POST "$BASE/api/biz/collect_task" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"file_type":"xlsx","target_type":"person","status":0}')
CLID=$(echo "$CLT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
FILL=$(curl -fsS -X POST "$BASE/api/flow/collect/$CLID/fill" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"rows":[{"name":"上线复测代录员","telephone":"13700000008","person_type":"employee"}],"targetTable":"person"}')
FN=$(echo "$FILL" | jget "import sys,json; print(json.load(sys.stdin)['data']['filled'])")
T1=$(ms); E4=$((T1-T0))
if [ "$FN" = "1" ]; then
  ok "标准④ 服务商代录补齐台账：${E4}ms（跳过推送、直接落业务表）"
else
  bad "标准④ 代录复测失败：filled=$FN"
fi

# ================= 2. 三套既有回归套件 =================
echo "----- 2. 全量回归套件 -----"
run_suite() {
  local name=$1
  local script=$2
  local logf="/tmp/${script}.log"
  if ./"$script" > "$logf" 2>&1; then
    local line
    line=$(grep -E '===== 结果|===== E2 结果' "$logf" | tail -1 | sed 's/^[[:space:]]*//')
    ok "$name：$line"
  else
    bad "$name（失败项如下）"
    grep -E '❌' "$logf" | head -8 | sed 's/^/      /'
  fi
}
run_suite "9 项垂直切片闭环" "e2e_test.sh"
run_suite "A/B/C 全链路 38 项" "e2e_phase_test.sh"
run_suite "E2 开户引导 29 项" "e2e_onboarding_test.sh"

echo ""
echo "=============================================="
echo "  E4 复测结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 四条验收标准与全量回归全部通过，可进入上线检查清单签字环节。"
  echo "   注意：E1 标注的真实外部资源（域名/备案/CA 证书/告警 webhook/异地备份）"
  echo "   仍需运营方在生产机就位，属上线前置阻断项。"
  exit 0
else
  echo "❌ 存在阻断项，不建议上线。"
  exit 1
fi
