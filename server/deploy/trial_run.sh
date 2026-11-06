#!/usr/bin/env bash
# E3 种子客户试运行演练（宿主机执行）。
#
# 演练内容：
#   1) 模拟企业 / 服务商提交试运行反馈
#   2) 验证反馈的租户隔离（企业只能看自己、平台看全量）
#   3) 平台受理 → 解决 / 驳回，形成闭环
#   4) 四条验收标准逐条实操并计时（需求文档 §十）
#   5) 生成《种子客户试运行演练报告》
#
# 用法：cd server/deploy && ./trial_run.sh
set -uo pipefail
cd "$(dirname "$0")"

BASE=${BASE_URL:-http://localhost:8080}
DOCS=$(cd ../.. && pwd)/docs
mkdir -p "$DOCS"
TRIAL_JSON=/tmp/trial_result.json
PASS=0; FAIL=0
ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
jget() { python3 -c "$1" 2>/dev/null || echo ""; }
ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

captcha_code() { docker exec safetrans-redis redis-cli get "captcha:$1" 2>/dev/null | tr -d '\r\n'; }
login_as() {
  local user=$1 pass=$2 cap capid code
  cap=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
  capid=$(echo "$cap" | jget "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
  code=$(captcha_code "$capid")
  curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\",\"captchaId\":\"$capid\",\"captcha\":\"$code\"}"
}

echo "===== E3 种子客户试运行演练 ====="
# 幂等：仅清理本脚本上一轮构造的演练反馈，不触碰真实客户反馈
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q '^safetrans-mysql$'; then
  docker exec safetrans-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root123}" \
    -e "DELETE FROM safetrans.sys_trial_feedback WHERE \`contact\`='TRIAL_RUN_SIMULATED';" 2>/dev/null || true
fi
ENT=$(login_as aa_gaozhou 123456); ET=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
PRO=$(login_as bb_provider 123456); PT=$(echo "$PRO" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
ADM=$(login_as cc_admin 123456); AT=$(echo "$ADM" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$ET" ] && [ -n "$PT" ] && [ -n "$AT" ] && ok "三角色登录" || bad "角色登录失败"
AE="Authorization: Bearer $ET"; AP="Authorization: Bearer $PT"; AA="Authorization: Bearer $AT"

# ---------- 1. 提交反馈 ----------
echo "----- 1. 反馈提交 -----"
post_fb() {
  curl -fsS -X POST "$BASE/api/feedback" -H "$1" -H 'Content-Type: application/json' -d "$2"
}
F1=$(post_fb "$AE" '{"module":"一日三检","feedback_type":"bug","severity":2,"content":"[试运行演练] 手机上点合格按钮后没有明显提示，希望加个绿色成功提示","contact":"TRIAL_RUN_SIMULATED"}')
I1=$(echo "$F1" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
F2=$(post_fb "$AE" '{"module":"电子档案柜","feedback_type":"suggestion","severity":3,"content":"[试运行演练] 建议缺项清单支持按盒导出 Excel","contact":"TRIAL_RUN_SIMULATED"}')
I2=$(echo "$F2" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
F3=$(post_fb "$AE" '{"module":"范本套管理","feedback_type":"question","severity":2,"content":"[试运行演练] 年度更新时历史版本还能下载吗？","contact":"TRIAL_RUN_SIMULATED"}')
I3=$(echo "$F3" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
F4=$(post_fb "$AP" '{"module":"服务商代录","feedback_type":"bug","severity":2,"content":"[试运行演练] 批量代录时希望支持直接粘贴 Excel 区域","contact":"TRIAL_RUN_SIMULATED"}')
I4=$(echo "$F4" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$I1" ] && [ -n "$I2" ] && [ -n "$I3" ] && [ -n "$I4" ] \
  && ok "提交 4 条试运行反馈（企业 3 / 服务商 1）" || bad "反馈提交失败"

# ---------- 2. 租户隔离 ----------
echo "----- 2. 反馈隔离 -----"
EL=$(curl -fsS "$BASE/api/feedback?pageSize=100" -H "$AE")
ETOTAL=$(echo "$EL" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
AL=$(curl -fsS "$BASE/api/feedback?pageSize=100" -H "$AA")
ATOTAL=$(echo "$AL" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
[ "${ETOTAL:-0}" -ge 3 ] && [ "${ATOTAL:-0}" -ge 4 ] \
  && ok "企业可见 $ETOTAL 条 / 平台可见 $ATOTAL 条（隔离生效）" || bad "隔离异常：企业=$ETOTAL 平台=$ATOTAL"
FB_FORBID=$(curl -fsS -X PUT "$BASE/api/feedback/$I1" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"resolved"}')
FC=$(echo "$FB_FORBID" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$FC" = "403" ] && ok "企业无权处理反馈（403）" || bad "越权处理未被拦截（code=$FC）"

# ---------- 3. 平台处理闭环 ----------
echo "----- 3. 受理与闭环 -----"
H1=$(curl -fsS -X PUT "$BASE/api/feedback/$I1" -H "$AA" -H 'Content-Type: application/json' -d '{"status":"processing","handle_note":"已复现，安排前端修复"}')
H1S=$(echo "$H1" | jget "import sys,json; print(json.load(sys.stdin)['data']['to'])")
[ "$H1S" = "processing" ] && ok "反馈受理 → processing" || bad "受理失败"
H2=$(curl -fsS -X PUT "$BASE/api/feedback/$I1" -H "$AA" -H 'Content-Type: application/json' -d '{"status":"resolved","handle_note":"已加成功提示，v1.0.0-prod 修复"}')
H2S=$(echo "$H2" | jget "import sys,json; print(json.load(sys.stdin)['data']['to'])")
[ "$H2S" = "resolved" ] && ok "缺陷修复 → resolved" || bad "解决失败"
H3=$(curl -fsS -X PUT "$BASE/api/feedback/$I3" -H "$AA" -H 'Content-Type: application/json' -d '{"status":"rejected","handle_note":"历史版本可在范本套详情页下载，非缺陷"}')
H3S=$(echo "$H3" | jget "import sys,json; print(json.load(sys.stdin)['data']['to'])")
[ "$H3S" = "rejected" ] && ok "咨询转说明 → rejected" || bad "驳回失败"
H4=$(curl -fsS -X PUT "$BASE/api/feedback/$I1" -H "$AA" -H 'Content-Type: application/json' -d '{"status":"resolved"}')
H4C=$(echo "$H4" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$H4C" != "0" ] && ok "已闭环反馈不可重复处理" || bad "状态机可被绕过"

SUM=$(curl -fsS "$BASE/api/feedback/summary" -H "$AA")
ST=$(echo "$SUM" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
SR=$(echo "$SUM" | jget "import sys,json; print(json.load(sys.stdin)['data']['byStatus']['resolved'])")
SRR=$(echo "$SUM" | jget "import sys,json; print(json.load(sys.stdin)['data']['byStatus']['rejected'])")
[ "${ST:-0}" -ge 4 ] && [ "${SR:-0}" -ge 1 ] && [ "${SRR:-0}" -ge 1 ] \
  && ok "汇总统计正确（total=$ST resolved=$SR rejected=$SRR）" || bad "汇总统计异常"

# ---------- 4. 四条验收标准演练（计时） ----------
echo "----- 4. 四条验收标准实操计时 -----"
STANDARDS="[]"
add_std() { # name path elapsed result
  STANDARDS=$(python3 -c "
import json,sys
d=json.loads(sys.argv[1]); d.append({'name':sys.argv[2],'path':sys.argv[3],'elapsedMs':int(sys.argv[4]),'result':sys.argv[5]})
print(json.dumps(d,ensure_ascii=False))
" "$STANDARDS" "$1" "$2" "$3" "$4")
}

# 标准 1：30 秒内完成一次三检
T0=$(ms)
CT=$(curl -fsS -X POST "$BASE/api/flow/check/task" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"vehicle_id":1,"driver_id":6001,"start_date":"2026-11-06","status":"ongoing"}')
CID=$(echo "$CT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
CR=$(curl -fsS -X POST "$BASE/api/flow/check/record" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"task_id\":$CID,\"phase\":\"out\",\"result\":\"abnormal\",\"abnormal_note\":\"演练：制动异响\"}")
CRC=$(echo "$CR" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
T1=$(ms); E1=$((T1-T0))
if [ "$CRC" = "0" ] && [ "$E1" -lt 30000 ]; then R1="PASS"; else R1="FAIL"; fi
[ "$R1" = "PASS" ] && ok "标准1 三检链路 ${E1}ms < 30s" || bad "标准1 三检链路 ${E1}ms / code=$CRC"
add_std "① 中老年司机 30 秒完成一次三检" "H5 /m/check → check/task + check/record" "$E1" "$R1"

# 标准 2：5 分钟内调出完整台账
T0=$(ms)
OV=$(curl -fsS "$BASE/api/flow/archive/overview?bizType=GENERAL" -H "$AE")
BOX=$(echo "$OV" | jget "import sys,json; print(json.load(sys.stdin)['data']['boxCount'])")
MIS=$(curl -fsS "$BASE/api/flow/archive/missing?bizType=GENERAL" -H "$AE")
RD=$(curl -fsS "$BASE/api/onboarding/readiness" -H "$AE")
T1=$(ms); E2=$((T1-T0))
if [ "$BOX" = "10" ] && [ "$E2" -lt 300000 ]; then R2="PASS"; else R2="FAIL"; fi
[ "$R2" = "PASS" ] && ok "标准2 调档 ${E2}ms < 5min（10 盒全景 + 缺项 + 自检）" || bad "标准2 调档异常 box=$BOX ${E2}ms"
add_std "② 政府检查 5 分钟调出完整台账" "archive/overview + archive/missing + onboarding/readiness" "$E2" "$R2"

# 标准 3：老板一眼看出最紧急风险
T0=$(ms)
DR=$(curl -fsS "$BASE/api/flow/dashboard/risk" -H "$AE")
DS=$(echo "$DR" | jget "import sys,json; print(json.load(sys.stdin)['data'].get('safetyScore','missing'))")
T1=$(ms); E3=$((T1-T0))
if [ "$DS" != "missing" ] && [ "$DS" != "" ]; then R3="PASS"; else R3="FAIL"; fi
[ "$R3" = "PASS" ] && ok "标准3 风险驾驶舱 ${E3}ms（安全分=$DS）" || bad "标准3 驾驶舱异常"
add_std "③ 一眼看出当前最紧急的安全风险" "flow/dashboard/risk" "$E3" "$R3"

# 标准 4：服务商代录补齐台账
T0=$(ms)
CLT=$(curl -fsS -X POST "$BASE/api/biz/collect_task" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"file_type":"xlsx","target_type":"person","status":0}')
CLID=$(echo "$CLT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
FILL=$(curl -fsS -X POST "$BASE/api/flow/collect/$CLID/fill" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"rows":[{"name":"演练代录司机","telephone":"13700000009","person_type":"employee"}],"targetTable":"person"}')
FN=$(echo "$FILL" | jget "import sys,json; print(json.load(sys.stdin)['data']['filled'])")
T1=$(ms); E4=$((T1-T0))
if [ "$FN" = "1" ]; then R4="PASS"; else R4="FAIL"; fi
[ "$R4" = "PASS" ] && ok "标准4 服务商代录 ${E4}ms（跳过推送、直接落业务表）" || bad "标准4 代录异常 filled=$FN"
add_std "④ 小微个体户由服务商代录补齐台账" "collect_task + collect/{id}/fill" "$E4" "$R4"

python3 -c "
import json,sys
json.dump({'standards':json.loads(sys.argv[1]),'generatedAt':'$(date '+%Y-%m-%d %H:%M:%S')'},
          open(sys.argv[2],'w',encoding='utf-8'),ensure_ascii=False,indent=2)
" "$STANDARDS" "$TRIAL_JSON"

# ---------- 5. 生成报告 ----------
echo "----- 5. 生成试运行报告 -----"
if python3 trial_report.py --base "$BASE" --token "$AT" --trial "$TRIAL_JSON" \
      --out "$DOCS/试运行演练报告.md"; then
  ok "报告已生成：$DOCS/试运行演练报告.md"
else
  bad "报告生成失败"
fi

echo ""
echo "===== E3 演练结果：$PASS 通过 / $FAIL 失败 ====="
[ "$FAIL" -eq 0 ] || exit 1
