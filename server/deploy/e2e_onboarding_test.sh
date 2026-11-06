#!/bin/bash
# E2 数据初始化与开户引导 · 全链路验收
# 在 compose 网络内的 e2e 容器中运行：
#   docker compose --profile test run --rm e2e ./e2e_onboarding_test.sh
#
# 覆盖：六阶段检查项字典、服务商签约开户、自动建号/建岗位、企业三步填报状态机、
#       手机号自动开通小程序账号、显式租户归档（铁律 6）、阶段推进、归档就位自检、越权拦截。
set -uo pipefail

BASE=${BASE_URL:-http://localhost:8080}
REDIS_HOST=${REDIS_HOST:-localhost}
PASS=0
FAIL=0

ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
jget() { python3 -c "$1" 2>/dev/null || echo ""; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi; }

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
login() { login_as "$1" "123456"; }

echo "===== E2 开户引导全链路验收 ====="
echo "BASE=$BASE"

for i in $(seq 1 60); do
  curl -fsS --max-time 3 "$BASE/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 2
done
curl -fsS --max-time 3 "$BASE/health" | grep -q '"status":"UP"' && ok "后端健康" || bad "后端未就绪"

# ---------- 平台账号登录 ----------
ADM=$(login cc_admin)
AT=$(echo "$ADM" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$AT" ] && ok "超管登录" || bad "超管登录失败"
AA="Authorization: Bearer $AT"

# ---------- 1. 六阶段检查项字典 ----------
CK=$(curl -fsS "$BASE/api/onboarding/checklist" -H "$AA")
CKN=$(echo "$CK" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${CKN:-0}" -ge 20 ] && ok "六阶段检查项字典（$CKN 项）" || bad "检查项字典异常"
STAGES=$(echo "$CK" | jget "import sys,json; print(len(set(m['stage'] for m in json.load(sys.stdin)['data'])))")
check "覆盖阶段 0~5" "$STAGES" "6"

# ---------- 2. 接入看板（演示企业已运营中） ----------
LST=$(curl -fsS "$BASE/api/onboarding/list" -H "$AA")
LSTN=$(echo "$LST" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${LSTN:-0}" -ge 1 ] && ok "企业接入看板（$LSTN 家）" || bad "看板为空"

# ---------- 3. 服务商签约开户 ----------
CODE="91$(python3 -c 'import random;print("".join(random.choice("0123456789") for _ in range(16)))')"
ENAME="验收测试运输有限公司"
OPEN=$(curl -fsS -X POST "$BASE/api/onboarding/open" -H "$AA" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$ENAME\",\"credit_code\":\"$CODE\",\"biz_type\":\"GENERAL\",\"region\":\"佛山市\",\"contact\":\"测试负责人\",\"phone\":\"13800000000\",\"contract_start\":\"2026-01-01\",\"contract_end\":\"2027-01-01\"}")
TID=$(echo "$OPEN" | jget "import sys,json; print(json.load(sys.stdin)['data']['tenantId'])")
USR=$(echo "$OPEN" | jget "import sys,json; print(json.load(sys.stdin)['data']['username'])")
POSTS=$(echo "$OPEN" | jget "import sys,json; print(json.load(sys.stdin)['data']['defaultPosts'])")
[ -n "$TID" ] && ok "签约开户成功（tenantId=$TID）" || { bad "开户失败：$OPEN"; }
check "自动创建 12 个默认岗位" "$POSTS" "12"
check "主账号=企业全称" "$USR" "$ENAME"

# ---------- 4. 初始密码登录（验证 BCrypt 与身份识别改读 user_type） ----------
ENT=$(login_as "$USR" "888888")
ET=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
EUT=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['userInfo']['userType'])")
[ -n "$ET" ] && ok "新企业主账号初始密码登录" || bad "主账号登录失败：$ENT"
check "身份识别为 enterprise" "$EUT" "enterprise"
AE="Authorization: Bearer $ET"

# ---------- 5. 初始状态 ----------
ST=$(curl -fsS "$BASE/api/onboarding/status" -H "$AE")
check "初始 step=0" "$(echo "$ST" | jget "import sys,json; print(json.load(sys.stdin)['data']['step'])")" "0"
LOCK=$(echo "$ST" | jget "import sys,json; print(json.load(sys.stdin)['data']['locked'])")
check "未完成前业务功能锁定" "$LOCK" "True"
ITN=$(echo "$ST" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['items']))")
[ "${ITN:-0}" -ge 20 ] && ok "检查项进度已初始化（$ITN 项）" || bad "检查项未初始化"

# ---------- 6. 第一步：企业基础资料 ----------
S1=$(curl -fsS -X POST "$BASE/api/onboarding/step1" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"legal_person":"测试法人","safety_charge":"测试负责人","charge_phone":"13800000000","region":"佛山市","address":"测试路 1 号","employee_count":12,"vehicle_scale":5,"license_file":"uploaded"}')
check "第一步后 step=1" "$(echo "$S1" | jget "import sys,json; print(json.load(sys.stdin)['data']['step'])")" "1"

# ---------- 7. 第二步：人档（手机号自动开通账号）+ 车档 ----------
PHONE1="139$(python3 -c 'import random;print(random.randint(10000000,99999999))')"
PHONE2="137$(python3 -c 'import random;print(random.randint(10000000,99999999))')"
PLATE="粤E$(python3 -c 'import random;print(random.randint(10000,99999))')"
S2=$(curl -fsS -X POST "$BASE/api/onboarding/step2/person" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"persons\":[{\"name\":\"张三\",\"telephone\":\"$PHONE1\",\"person_type\":\"driver\"},{\"name\":\"李四\",\"telephone\":\"$PHONE2\",\"person_type\":\"manager\"}]}")
check "第二步后 step=2" "$(echo "$S2" | jget "import sys,json; print(json.load(sys.stdin)['data']['step'])")" "2"
ACC=$(echo "$S2" | jget "import sys,json; print(json.load(sys.stdin)['data']['openedAccounts'])")
check "录入即开通 2 个小程序账号" "$ACC" "2"

DRV=$(login_as "$PHONE1" "123456")
DT=$(echo "$DRV" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
DUT=$(echo "$DRV" | jget "import sys,json; print(json.load(sys.stdin)['data']['userInfo']['userType'])")
[ -n "$DT" ] && ok "驾驶员手机号账号可登录" || bad "自动开通账号登录失败"
check "驾驶员岗位身份=driver" "$DUT" "driver"

S2V=$(curl -fsS -X POST "$BASE/api/onboarding/step2/vehicle" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"vehicles\":[{\"plate_no\":\"$PLATE\",\"plate_color\":\"黄\",\"vehicle_type\":\"货车\"}]}")
check "录入 1 台车辆" "$(echo "$S2V" | jget "import sys,json; print(json.load(sys.stdin)['data']['createdVehicles'])")" "1"

# ---------- 8. 铁律 6：人车档案已登记归档项（显式租户） ----------
# archive_item 不在通用 CRUD 白名单（设计上由档案柜专用接口消费），
# 因此走开户自检接口验证，它按 tenant_id + biz_module 精确统计。
RD8=$(curl -fsS "$BASE/api/onboarding/readiness" -H "$AE")
PAC=$(echo "$RD8" | jget "import sys,json; print(json.load(sys.stdin)['data']['personArchiveItems'])")
VAC=$(echo "$RD8" | jget "import sys,json; print(json.load(sys.stdin)['data']['vehicleArchiveItems'])")
[ "${PAC:-0}" -ge 2 ] && ok "铁律6：人员档案归档项挂到本租户（$PAC 项）" || bad "人员归档项缺失"
[ "${VAC:-0}" -ge 1 ] && ok "铁律6：车辆档案归档项挂到本租户（$VAC 项）" || bad "车辆归档项缺失"

# ---------- 9. 第三步 + 完成接入 ----------
S3=$(curl -fsS -X POST "$BASE/api/onboarding/step3" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"diagnosis":{"has_institution":"no","has_emergency_plan":"no","archive_complete":"no","note":"资料不齐"}}')
check "第三步后 step=3" "$(echo "$S3" | jget "import sys,json; print(json.load(sys.stdin)['data']['step'])")" "3"
SUB=$(curl -fsS -X POST "$BASE/api/onboarding/submit" -H "$AE" -H 'Content-Type: application/json' -d '{}')
check "完成接入 status=completed" "$(echo "$SUB" | jget "import sys,json; print(json.load(sys.stdin)['data']['status'])")" "completed"
check "完成接入进入阶段 2" "$(echo "$SUB" | jget "import sys,json; print(json.load(sys.stdin)['data']['stage'])")" "2"

# ---------- 10. 服务商推进阶段 2~4 检查项 ----------
for c in S2_ORG S2_INSTITUTION S2_EMERGENCY S2_PLAN S2_THREE_BOOKS S2_PREJOB \
         S3_ACCOUNTS S3_ROLES S4_PUBLISH S4_CABINET S4_PDF; do
  curl -fsS -X PUT "$BASE/api/onboarding/items/$c" -H "$AA" -H 'Content-Type: application/json' \
    -d "{\"tenant_id\":\"$TID\",\"done\":true}" >/dev/null
done
FIN=$(curl -fsS "$BASE/api/onboarding/status?tenantId=$TID" -H "$AA")
FSTAGE=$(echo "$FIN" | jget "import sys,json; print(json.load(sys.stdin)['data']['stage'])")
[ "${FSTAGE:-0}" -ge 4 ] && ok "检查项推进后阶段=$FSTAGE" || bad "阶段未推进（$FSTAGE）"

# ---------- 11. 归档就位自检 ----------
RD=$(curl -fsS "$BASE/api/onboarding/readiness?tenantId=$TID" -H "$AA")
RBOX=$(echo "$RD" | jget "import sys,json; print(json.load(sys.stdin)['data']['expectedBoxes'])")
check "普货期望 10 盒" "$RBOX" "10"
RMS=$(echo "$RD" | jget "import sys,json; print(json.load(sys.stdin)['data']['elapsedMs'])")
[ "${RMS:-9999}" -lt 300000 ] && ok "归档就位自检耗时 ${RMS}ms（<5 分钟）" || bad "自检超时"

# ---------- 12. 越权拦截 ----------
FORBID=$(curl -fsS "$BASE/api/onboarding/list" -H "$AE")
FC=$(echo "$FORBID" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
check "企业账号访问平台看板被拒（403）" "$FC" "403"
OPEN2=$(curl -fsS -X POST "$BASE/api/onboarding/open" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"name":"非法开户","credit_code":"910000000000000000"}')
OC=$(echo "$OPEN2" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
check "企业账号无权开户（403）" "$OC" "403"

echo ""
echo "===== E2 结果：$PASS 通过 / $FAIL 失败 ====="
[ "$FAIL" -eq 0 ] || exit 1
