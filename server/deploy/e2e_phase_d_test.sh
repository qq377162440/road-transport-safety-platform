#!/bin/bash
# D 阶段（三期 v1.2，D1~D5）全链路验收脚本
# 在 compose 网络内的 e2e 容器中运行：
#   docker compose --profile test run --rm e2e ./e2e_phase_d_test.sh
#
# 覆盖：动态监控台账（D1）、安全生产费用（D2）、风险分级管控（D3）、
#       危货专项深化（D4）、平台侧能力（D5），以及铁律 6 归档、
#       归档 PDF 下载、三角色越权、状态机与幂等重跑。
#
# 可重复运行约定：
#   - 预算按固定年度 2031/2032 幂等 upsert；电子运单用固定单号；
#   - 累计型数据（支出/台账/违规）一律用 >= 断言，不用精确值；
#   - D5 模块授权放在最后，结束时恢复「18 模块全量开通」，不污染其他套件。
set -uo pipefail

BASE=${BASE_URL:-http://localhost:8080}
REDIS_HOST=${REDIS_HOST:-localhost}
PASS=0
FAIL=0

ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
jget() { python3 -c "$1" 2>/dev/null || echo ""; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi; }
code_of() { echo "$1" | jget "import sys,json; print(json.load(sys.stdin)['code'])"; }

captcha_code() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h "$REDIS_HOST" get "captcha:$1" 2>/dev/null | tr -d '\r\n'
  else
    docker exec safetrans-redis redis-cli get "captcha:$1" 2>/dev/null | tr -d '\r\n'
  fi
}

login() {
  local user=$1 cap capid code
  cap=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
  capid=$(echo "$cap" | jget "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
  code=$(captcha_code "$capid")
  curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"123456\",\"captchaId\":\"$capid\",\"captcha\":\"$code\"}"
}

# 归档 PDF 通用校验：生成 → 取 downloadUrl → 带 JWT 下载 → %PDF 魔数
check_pdf() {
  local name=$1 resp=$2 file=$3 url code
  url=$(echo "$resp" | jget "import sys,json; print(json.load(sys.stdin)['data']['downloadUrl'])")
  if [ -z "$url" ]; then bad "$name：未返回 downloadUrl"; return; fi
  code=$(curl -s -o "$file" -w "%{http_code}" "$BASE$url" -H "$AE")
  if [ "$code" = "200" ] && head -c 4 "$file" 2>/dev/null | grep -q '%PDF'; then
    ok "$name（$(wc -c < "$file") bytes）"
  else
    bad "$name：下载 HTTP=$code 或非 PDF"
  fi
}

echo "===== D 阶段（三期 v1.2）全链路验收 ====="
echo "BASE=$BASE"

# 0. 等待后端
for i in $(seq 1 60); do
  curl -fsS --max-time 3 "$BASE/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 2
done
curl -fsS --max-time 3 "$BASE/health" | grep -q '"status":"UP"' && ok "后端健康" || bad "后端未就绪"

ENT=$(login aa_gaozhou)
ET=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$ET" ] && ok "企业账号登录（tenant 5001）" || bad "企业账号登录失败"
AE="Authorization: Bearer $ET"

PRO=$(login bb_provider)
PT=$(echo "$PRO" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$PT" ] && ok "服务商账号登录" || bad "服务商登录失败"
AP="Authorization: Bearer $PT"

ADM=$(login cc_admin)
AT=$(echo "$ADM" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$AT" ] && ok "平台超管账号登录" || bad "超管登录失败"
AD="Authorization: Bearer $AT"

echo "----- 基线：迁移与元数据 -----"
META=$(curl -fsS "$BASE/api/biz/meta" -H "$AE")
MC=$(echo "$META" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
check "V8/V9 后元数据 18 模块" "$MC" "18"
NEWF=$(curl -fsS "$BASE/api/biz/monitor_ledger/fields" -H "$AE")
NFC=$(echo "$NEWF" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['fields']))")
[ "${NFC:-0}" -ge 8 ] && ok "V8 新表纳入通用引擎白名单（monitor_ledger $NFC 字段）" || bad "V8 新表不可访问"
D5F=$(curl -fsS "$BASE/api/biz/service_billing/fields" -H "$AE")
D5C=$(echo "$D5F" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['fields']))")
[ "${D5C:-0}" -ge 8 ] && ok "V9 新表纳入通用引擎白名单（service_billing $D5C 字段）" || bad "V9 新表不可访问"

# ==================================================================
echo "----- D1 动态监控台账（盒六）-----"
ML=$(curl -fsS -X POST "$BASE/api/monitor/ledger" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"ledger_name":"D验收-监控台账","ledger_type":"工作记录","source":"平台导出","period":"2026-11"}')
MLID=$(echo "$ML" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$MLID" ] && ok "D1 台账上传留存（id=$MLID）" || bad "D1 台账上传失败"
MLCAT=$(echo "$ML" | jget "import sys,json; print(json.load(sys.stdin)['data']['catalog'])")
check "D1 台账归档归属盒六" "$MLCAT" "BOX6"

MLL=$(curl -fsS "$BASE/api/monitor/ledger/list" -H "$AE")
MLLN=$(echo "$MLL" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${MLLN:-0}" -ge 1 ] && ok "D1 台账列表（$MLLN 条，含违规识别数）" || bad "D1 台账列表异常"
MLD=$(curl -fsS "$BASE/api/monitor/ledger/$MLID" -H "$AE")
check "D1 台账详情含违规明细" "$(echo "$MLD" | jget "import sys,json; print('ok' if 'violations' in json.load(sys.stdin)['data'] else 'bad')")" "ok"

V1=$(curl -fsS -X POST "$BASE/api/monitor/violation" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"ledger_id\":$MLID,\"vehicle_plate\":\"粤E·D0001\",\"violation_type\":\"超速行驶\",\"driver_id\":6001}")
V1ID=$(echo "$V1" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$V1ID" ] && ok "D1 违规登记" || bad "D1 违规登记失败"
V1C=$(echo "$V1" | jget "import sys,json; print(json.load(sys.stdin)['data']['violationCount'])")
[ "${V1C:-0}" -ge 1 ] && ok "D1 按车牌归并违规次数（$V1C）" || bad "D1 归并统计异常"
V2=$(curl -fsS -X POST "$BASE/api/monitor/violation" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"ledger_id\":$MLID,\"vehicle_plate\":\"粤E·D0001\",\"violation_type\":\"疲劳驾驶\",\"driver_id\":6001}")
V2C=$(echo "$V2" | jget "import sys,json; print(json.load(sys.stdin)['data']['violationCount'])")
[ "${V2C:-0}" -gt "${V1C:-0}" ] && ok "D1 同车牌二次违规累计递增（$V1C → $V2C）" || bad "D1 累计未递增"

VS=$(curl -fsS "$BASE/api/monitor/violation/stats" -H "$AE")
VST=$(echo "$VS" | jget "import sys,json; print(json.load(sys.stdin)['data']['totalViolations'])")
[ "${VST:-0}" -ge 2 ] && ok "D1 违规统计（总数 $VST / 按车牌分组 / 教育落实率）" || bad "D1 统计异常"

EDU=$(curl -fsS -X POST "$BASE/api/monitor/violation/$V1ID/edu" -H "$AE")
EPLAN=$(echo "$EDU" | jget "import sys,json; print(json.load(sys.stdin)['data']['planId'])")
[ -n "$EPLAN" ] && ok "D1 靶向教育联动生成培训计划（planId=$EPLAN）" || bad "D1 靶向教育失败"
EDU2=$(curl -fsS -X POST "$BASE/api/monitor/violation/$V1ID/edu" -H "$AE")
check "D1 靶向教育幂等（重复点击不重复建计划）" "$(echo "$EDU2" | jget "import sys,json; print(json.load(sys.stdin)['data']['idempotent'])")" "True"

ES1=$(curl -fsS -X PUT "$BASE/api/monitor/violation/$V1ID/edu-status" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"archived"}')
check "D1 教育落实推进到 archived" "$(echo "$ES1" | jget "import sys,json; print(json.load(sys.stdin)['data']['to'])")" "archived"
ES2=$(curl -fsS -X PUT "$BASE/api/monitor/violation/$V1ID/edu-status" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"pushed"}')
check "D1 教育状态机不可回退（3002）" "$(code_of "$ES2")" "3002"
ES3=$(curl -fsS -X PUT "$BASE/api/monitor/violation/$V1ID/edu-status" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"bogus"}')
check "D1 非法教育状态被拒（400）" "$(code_of "$ES3")" "400"

OS1=$(curl -fsS -X POST "$BASE/api/monitor/outsource" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"outsource_type":"monitor","outsource_org":"第三方监控平台A","enabled":1}')
OS2=$(curl -fsS -X POST "$BASE/api/monitor/outsource" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"outsource_type":"monitor","outsource_org":"第三方监控平台A","enabled":1}')
check "D1 第三方监控委托 upsert 幂等" "$(echo "$OS1" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")" "$(echo "$OS2" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")"

MPV=$(curl -fsS "$BASE/api/monitor/archive" -H "$AE")
check "D1 归档预览 HTML 可用" "$(echo "$MPV" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d['code']==0 and len(d['data']['html'])>200 else 'bad')")" "ok"
MPDF=$(curl -fsS -X POST "$BASE/api/monitor/archive/pdf?ledgerId=$MLID" -H "$AE")
check_pdf "D1 盒六归档 PDF 生成/下载" "$MPDF" /tmp/d_monitor.pdf

# ==================================================================
echo "----- D2 安全生产费用（盒五 / 财资〔2022〕136 号）-----"
CATS=$(curl -fsS "$BASE/api/budget/category" -H "$AE")
CATN=$(echo "$CATS" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${CATN:-0}" -ge 11 ] && ok "D2 11 类费用字典对企业可见（tenant_id=0 共享，当前 $CATN 类）" || bad "D2 费用字典异常"
CATSH=$(echo "$CATS" | jget "import sys,json; d=json.load(sys.stdin)['data']; print('ok' if any(str(x['id']) in ('6001','6011') for x in d) else 'bad')")
check "D2 字典含系统预置 11 类" "$CATSH" "ok"
CATADD=$(curl -fsS -X POST "$BASE/api/budget/category" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"category_name":"D验收自定义类别","category_code":"CUSTOM"}')
check "D2 企业可自定义费用类别" "$(code_of "$CATADD")" "0"

# 普货 1%（种子企业 biz_type=freight），固定年度保证重跑幂等
# 金额以 Decimal 做数值等价比较：Jackson 会把 JSON number 的尾随零规范化
# （DB 里仍是 DECIMAL(14,2)/(5,4)，plan_amount=100000.00、rate=0.0100），
# 用字符串精确比较会误报，故统一按数值断言。
PL1=$(curl -fsS -X POST "$BASE/api/budget/plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"year":2031,"revenue":10000000,"cargo_type":"general","revenue_file":"minio://d/revenue.pdf"}')
PL1A=$(echo "$PL1" | jget "import sys,json; print(json.load(sys.stdin)['data']['planAmount'])")
check "D2 普货按 1% 计提（1000 万 → 10 万，比例 0.0100）" \
  "$(echo "$PL1" | jget "import sys,json; from decimal import Decimal; d=json.load(sys.stdin)['data']; print('ok' if Decimal(str(d['planAmount']))==Decimal('100000.00') and Decimal(str(d['rate']))==Decimal('0.0100') else 'bad')")" "ok"
PL1ID=$(echo "$PL1" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")

PL2=$(curl -fsS -X POST "$BASE/api/budget/plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"year":2032,"revenue":10000000,"cargo_type":"danger"}')
check "D2 危货按 1.5% 计提（1000 万 → 15 万，比例 0.0150）" \
  "$(echo "$PL2" | jget "import sys,json; from decimal import Decimal; d=json.load(sys.stdin)['data']; print('ok' if Decimal(str(d['planAmount']))==Decimal('150000.00') and Decimal(str(d['rate']))==Decimal('0.0150') else 'bad')")" "ok"
PL3=$(curl -fsS -X POST "$BASE/api/budget/plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"year":2033,"revenue":10000000,"cargo_type":"passenger"}')
check "D2 客运计提按设计冻结（3002）" "$(code_of "$PL3")" "3002"
PL0=$(curl -fsS -X POST "$BASE/api/budget/plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"year":2031,"cargo_type":"general"}')
check "D2 缺上年度营收必填校验（2001）" "$(code_of "$PL0")" "2001"
PL1B=$(curl -fsS -X POST "$BASE/api/budget/plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"year":2031,"revenue":10000000,"cargo_type":"general"}')
check "D2 同年重复计提幂等（id 不变）" "$(echo "$PL1B" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")" "$PL1ID"

EX0=$(curl -fsS -X POST "$BASE/api/budget/expense" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"plan_id\":$PL1ID,\"amount\":0,\"category_id\":6001}")
check "D2 支出金额必须大于 0（2001）" "$(code_of "$EX0")" "2001"
EX=$(curl -fsS -X POST "$BASE/api/budget/expense" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"plan_id\":$PL1ID,\"category_id\":6001,\"expense_date\":\"2031-03-15\",\"expense_desc\":\"D验收-灭火器采购\",\"amount\":12000,\"receipt_file\":\"minio://d/fapiao.pdf\"}")
check "D2 支出台账登记并留发票" "$(code_of "$EX")" "0"
EXU=$(echo "$EX" | jget "import sys,json; print(json.load(sys.stdin)['data']['usedAmount'])")
EXUOK=$(echo "$EX" | jget "import sys,json; from decimal import Decimal; print('ok' if Decimal(str(json.load(sys.stdin)['data']['usedAmount']))>=Decimal('12000') else 'bad')")
check "D2 支出后实时汇总已用金额（$EXU）" "$EXUOK" "ok"

SUM=$(curl -fsS "$BASE/api/budget/summary/2031" -H "$AE")
SUMU=$(echo "$SUM" | jget "import sys,json; print(json.load(sys.stdin)['data']['usedAmount'])")
SUMM=$(echo "$SUM" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['matrix']))")
[ "${SUMM:-0}" -ge 11 ] && ok "D2 11 类 × 12 月使用台账矩阵（$SUMM 行）" || bad "D2 矩阵异常"
SUMT=$(echo "$SUM" | jget "import sys,json; from decimal import Decimal; d=json.load(sys.stdin)['data']; D=lambda x:Decimal(str(x)); print('ok' if D(d['usageRate'])>0 and D(d['balance'])==D(d['planAmount'])-D(d['usedAmount']) and d['complianceTip'] else 'bad')")
check "D2 使用率/结余/达标提示自洽" "$SUMT" "ok"
SUMUOK=$(echo "$SUM" | jget "import sys,json; from decimal import Decimal; print('ok' if Decimal(str(json.load(sys.stdin)['data']['usedAmount']))>=Decimal('12000') else 'bad')")
check "D2 年度汇总口径一致（已用 $SUMU）" "$SUMUOK" "ok"
EXL=$(curl -fsS "$BASE/api/budget/expense/list?planId=$PL1ID" -H "$AE")
EXLN=$(echo "$EXL" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${EXLN:-0}" -ge 1 ] && ok "D2 支出明细可查（$EXLN 条）" || bad "D2 明细异常"
BPV=$(curl -fsS "$BASE/api/budget/archive?year=2031" -H "$AE")
check "D2 归档预览 HTML 可用" "$(echo "$BPV" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d['code']==0 and len(d['data']['html'])>200 else 'bad')")" "ok"
BPDF=$(curl -fsS -X POST "$BASE/api/budget/archive/pdf?year=2031" -H "$AE")
check_pdf "D2 盒五归档 PDF 生成/下载" "$BPDF" /tmp/d_budget.pdf

# ==================================================================
echo "----- D3 风险分级管控（盒八/盒九）-----"
PD=$(curl -fsS "$BASE/api/risk/point-dict" -H "$AE")
PDN=$(echo "$PD" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
[ "${PDN:-0}" -ge 21 ] && ok "D3 通用风险点库（$PDN 条，五类场景）" || bad "D3 风险点库异常"
PDC=$(echo "$PD" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['byCategory']))")
[ "${PDC:-0}" -ge 5 ] && ok "D3 风险点覆盖 5 类场景（$PDC 类）" || bad "D3 分类异常"
PDFOUR=$(echo "$PD" | jget "import sys,json; d=json.load(sys.stdin)['data']['list']; s={str(x['risk_level']) for x in d}; print('ok' if {'红','橙','黄','蓝'}<=s else 'bad')")
check "D3 四色风险等级齐全" "$PDFOUR" "ok"
PDFLT=$(curl -fsS "$BASE/api/risk/point-dict?category=driver" -H "$AE")
check "D3 按分类过滤" "$(echo "$PDFLT" | jget "import sys,json; d=json.load(sys.stdin)['data']['list']; print('ok' if d and all(x['risk_category']=='driver' for x in d) else 'bad')")" "ok"
PADD=$(curl -fsS -X POST "$BASE/api/risk/point-dict" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"risk_name":"D验收自定义风险点","risk_category":"manage","risk_level":"黄"}')
check "D3 企业可增补自定义风险点" "$(code_of "$PADD")" "0"
PBAD=$(curl -fsS -X POST "$BASE/api/risk/point-dict" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"risk_name":"非法等级","risk_level":"紫"}')
check "D3 非法风险等级被拒（400）" "$(code_of "$PBAD")" "400"

GA=$(curl -fsS -X POST "$BASE/api/risk/generate" -H "$AE" -H 'Content-Type: application/json' -d '{"year":2031}')
GAL=$(echo "$GA" | jget "import sys,json; d=json.load(sys.stdin)['data']['list']; print(d['generated']+d['skipped'])")
[ "${GAL:-0}" -ge 15 ] && ok "D3 一键生成清单覆盖适用风险点（$GAL 条）" || bad "D3 一键生成清单异常"
GAP=$(echo "$GA" | jget "import sys,json; print(json.load(sys.stdin)['data']['cards']['posts'])")
[ "${GAP:-0}" -ge 1 ] && ok "D3 按组织架构岗位生成告知卡（$GAP 岗）" || bad "D3 未读取到启用岗位"
GAM=$(echo "$GA" | jget "import sys,json; print(json.load(sys.stdin)['data']['manuals'])")
check "D3 手册 + 无重大风险说明（普货 2 份）" "$GAM" "2"
RL=$(curl -fsS "$BASE/api/risk/list?year=2031" -H "$AE")
RLN=$(echo "$RL" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${RLN:-0}" -ge 15 ] && ok "D3 两级风险辨识清单（$RLN 条）" || bad "D3 清单异常"
FC=$(curl -fsS "$BASE/api/risk/four-color?year=2031" -H "$AE")
FCL=$(echo "$FC" | jget "import sys,json; d=json.load(sys.stdin)['data']; print('ok' if len(d['levels'])==4 and d['total']>=15 and d['cards']>=1 and d['manuals']>=2 else 'bad')")
check "D3 四色统计 / 告知卡 / 手册齐备" "$FCL" "ok"
OPC=$(curl -fsS "$BASE/api/biz/org_post_content" -H "$AE" | jget "import sys,json; d=json.load(sys.stdin)['data']; rows=d.get('records',d) if isinstance(d,dict) else d; print(len([x for x in rows if x.get('content_code')=='hazard']))")
[ "${OPC:-0}" -ge 1 ] && ok "D3 告知卡联动岗位内容块（$OPC 条）" || bad "D3 岗位内容联动异常"
GA2=$(curl -fsS -X POST "$BASE/api/risk/generate" -H "$AE" -H 'Content-Type: application/json' -d '{"year":2031}')
check "D3 一键生成幂等（二次清单 0 条）" "$(echo "$GA2" | jget "import sys,json; print(json.load(sys.stdin)['data']['list']['generated'])")" "0"
DNG=$(curl -fsS -X POST "$BASE/api/risk/manual" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"doc_type":"safety_eval","year":2031}')
check "D3 危货专属文档对普货企业拒绝（3002）" "$(code_of "$DNG")" "3002"
RPV=$(curl -fsS "$BASE/api/risk/archive?year=2031" -H "$AE")
check "D3 归档预览 HTML 可用" "$(echo "$RPV" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d['code']==0 and len(d['data']['html'])>200 else 'bad')")" "ok"
RPDF=$(curl -fsS -X POST "$BASE/api/risk/archive/pdf?year=2031" -H "$AE")
check_pdf "D3 风险辨识资料汇编 PDF 生成/下载" "$RPDF" /tmp/d_risk.pdf

# ==================================================================
echo "----- D4 危货专项深化（盒六）-----"
LT=$(curl -fsS "$BASE/api/danger/ledger-types" -H "$AE")
check "D4 六项强制专项台账字典" "$(echo "$LT" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")" "6"
LBAD=$(curl -fsS -X POST "$BASE/api/danger/ledger" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"ledger_type":"not_exist"}')
check "D4 非法台账类型被拒（400）" "$(code_of "$LBAD")" "400"
for t in waybill load_check transport escort long_line safety_card; do
  curl -fsS -X POST "$BASE/api/danger/ledger" -H "$AE" -H 'Content-Type: application/json' \
    -d "{\"ledger_type\":\"$t\",\"biz_date\":\"2026-11-06\",\"plate_no\":\"粤E·D0001\",\"content\":\"D验收-$t\"}" >/dev/null 2>&1
done
CMP=$(curl -fsS "$BASE/api/danger/completeness" -H "$AE")
check "D4 六项台账齐备判定" "$(echo "$CMP" | jget "import sys,json; print(json.load(sys.stdin)['data']['qualified'])")" "True"
CMPR=$(echo "$CMP" | jget "import sys,json; print(json.load(sys.stdin)['data']['ready'])")
check "D4 齐备 6/6" "$CMPR" "6"

WB0=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"cargo_name":"液氯"}')
check "D4 运单三项强制匹配校验（2001）" "$(code_of "$WB0")" "2001"
WB1=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"cargo_name":"液氯","vehicle_id":1,"driver_id":6001,"escort_id":7011}')
check "D4 押运员未确认在岗禁止发车（3002）" "$(code_of "$WB1")" "3002"
WB2=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"cargo_name":"液氯","vehicle_id":1,"driver_id":6001,"escort_id":7011,"escort_on_duty":1}')
check "D4 车辆资质未查验禁止发车（3002）" "$(code_of "$WB2")" "3002"
WB3=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"cargo_name":"液氯","vehicle_id":1,"driver_id":6001,"escort_id":7011,"escort_on_duty":1,"vehicle_checked":1,"distance_km":900}')
check "D4 800 公里以上未安排双押双驾禁止发车（3002）" "$(code_of "$WB3")" "3002"
WB=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"waybill_no":"DWB-D-E2E-0001","cargo_name":"液氯","danger_class":"毒性气体","un_no":"UN1017","quantity":20,"origin":"佛山","destination":"广州","vehicle_id":1,"plate_no":"粤E·D0001","driver_id":6001,"escort_id":7011,"escort_on_duty":1,"vehicle_checked":1,"distance_km":900,"double_driver":1}')
WBID=$(echo "$WB" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$WBID" ] && ok "D4 合法电子运单生成并自动补台账" || bad "D4 运单生成失败"
# 首跑返回 doubleDriverRequired；重跑命中幂等分支无该字段，故统一以落库记录为准。
WB2X=$(curl -fsS -X POST "$BASE/api/danger/waybill" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"waybill_no":"DWB-D-E2E-0001","cargo_name":"液氯","vehicle_id":1,"driver_id":6001,"escort_id":7011,"escort_on_duty":1,"vehicle_checked":1}')
check "D4 同单号运单幂等" "$(echo "$WB2X" | jget "import sys,json; print(json.load(sys.stdin)['data']['idempotent'])")" "True"
WBL=$(curl -fsS "$BASE/api/danger/waybill/list" -H "$AE")
WBLN=$(echo "$WBL" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${WBLN:-0}" -ge 1 ] && ok "D4 运单列表（$WBLN 条）" || bad "D4 运单列表异常"
WBDD=$(echo "$WBL" | jget "import sys,json; d=[x for x in json.load(sys.stdin)['data'] if str(x.get('waybill_no'))=='DWB-D-E2E-0001']; print('ok' if d and str(d[0].get('double_driver'))=='1' and int(d[0].get('distance_km') or 0)>=800 else 'bad')")
check "D4 超长线路双押双驾标记（运单落库）" "$WBDD" "ok"
WBS=$(curl -fsS -X PUT "$BASE/api/danger/waybill/$WBID/status" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"archived"}')
check "D4 运单状态流转到归档" "$(echo "$WBS" | jget "import sys,json; print(json.load(sys.stdin)['data']['status'])")" "archived"
WBSB=$(curl -fsS -X PUT "$BASE/api/danger/waybill/$WBID/status" -H "$AE" -H 'Content-Type: application/json' -d '{"status":"bogus"}')
check "D4 非法运单状态被拒（400）" "$(code_of "$WBSB")" "400"
PK=$(curl -fsS "$BASE/api/danger/parking" -H "$AE")
check "D4 危货停车场管理查询可用" "$(echo "$PK" | jget "import sys,json; print('ok' if json.load(sys.stdin)['code']==0 else 'bad')")" "ok"
DPV=$(curl -fsS "$BASE/api/danger/archive" -H "$AE")
check "D4 归档预览 HTML 可用" "$(echo "$DPV" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d['code']==0 and len(d['data']['html'])>200 else 'bad')")" "ok"
DPDF=$(curl -fsS -X POST "$BASE/api/danger/archive/pdf" -H "$AE")
check_pdf "D4 危货专项归档 PDF 生成/下载" "$DPDF" /tmp/d_danger.pdf

# ==================================================================
echo "----- D5 平台侧能力（超管/服务商）-----"
MODS=$(curl -fsS "$BASE/api/platform/modules" -H "$AP")
MODN=$(echo "$MODS" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
check "D5 平台功能模块字典 18 个" "$MODN" "18"
MODVA=$(echo "$MODS" | jget "import sys,json; print(json.load(sys.stdin)['data']['valueAdded'])")
check "D5 增值模块 5 个（D1~D5）" "$MODVA" "5"
FBD=$(curl -fsS "$BASE/api/platform/modules" -H "$AE")
check "D5 企业越权访问平台模块字典被拒（403）" "$(code_of "$FBD")" "403"
FBB=$(curl -fsS "$BASE/api/platform/billing" -H "$AE")
check "D5 企业越权访问计费被拒（403）" "$(code_of "$FBB")" "403"
CFG=$(curl -fsS -X PUT "$BASE/api/platform/modules/monitor" -H "$AD" -H 'Content-Type: application/json' \
  -d '{"monthly_fee":199,"enabled":1}')
check "D5 超管可配置模块月费/上下架" "$(code_of "$CFG")" "0"
CFGE=$(curl -fsS -X PUT "$BASE/api/platform/modules/monitor" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"monthly_fee":199}')
check "D5 服务商不可改模块配置（仅 admin，403）" "$(code_of "$CFGE")" "403"
TNT=$(curl -fsS "$BASE/api/platform/tenants" -H "$AP")
check "D5 企业开通总览可查" "$(echo "$TNT" | jget "import sys,json; print('ok' if json.load(sys.stdin)['data']['total']>=1 else 'bad')")" "ok"

MM0=$(curl -fsS "$BASE/api/platform/my-modules" -H "$AE")
MM0B=$(echo "$MM0" | jget "import sys,json; d=json.load(sys.stdin)['data']; print('ok' if 'person' in d['enabled'] else 'bad')")
check "D5 基础模块对企业恒可用" "$MM0B" "ok"
GR=$(curl -fsS -X POST "$BASE/api/platform/tenant/5001/modules" -H "$AD" -H 'Content-Type: application/json' \
  -d '{"modules":[{"code":"monitor","enabled":1},{"code":"budget","enabled":1},{"code":"risk","enabled":1},{"code":"dangerspecial","enabled":1}]}')
check "D5 按租户授权增值模块" "$(code_of "$GR")" "0"
TM=$(curl -fsS "$BASE/api/platform/tenant/5001/modules" -H "$AP")
TMC=$(echo "$TM" | jget "import sys,json; print(json.load(sys.stdin)['data']['configured'])")
[ "${TMC:-0}" -ge 4 ] && ok "D5 授权记录可查（$TMC 条）" || bad "D5 授权记录异常"
MM1=$(curl -fsS "$BASE/api/platform/my-modules" -H "$AE")
MMD=$(echo "$MM1" | jget "import sys,json; d=json.load(sys.stdin)['data']; print('ok' if d['trialAllOpen']==False and 'person' in d['enabled'] else 'bad')")
check "D5 授权后基础模块仍可用 / 试运行全量开放标记关闭" "$MMD" "ok"

SSO=$(curl -fsS -X PUT "$BASE/api/platform/tenant/5001/sso" -H "$AD" -H 'Content-Type: application/json' \
  -d '{"protocol":"OIDC","idp_url":"https://idp.example.com/realms/safetrans","client_id":"safetrans-web","client_secret":"D-E2E-SECRET-XYZ","enabled":1}')
check "D5 SSO 配置登记" "$(code_of "$SSO")" "0"
SSOG=$(curl -fsS "$BASE/api/platform/tenant/5001/sso" -H "$AP")
if echo "$SSOG" | grep -q 'D-E2E-SECRET-XYZ'; then bad "D5 SSO 客户端密钥未脱敏"; else ok "D5 SSO 客户端密钥接口不回显明文"; fi
check "D5 SSO 密钥已登记标记" "$(echo "$SSOG" | jget "import sys,json; print(json.load(sys.stdin)['data']['clientSecretSet'])")" "True"
SSOB=$(curl -fsS -X PUT "$BASE/api/platform/tenant/5001/sso" -H "$AD" -H 'Content-Type: application/json' \
  -d '{"protocol":"BAD"}')
check "D5 非法 SSO 协议被拒（400）" "$(code_of "$SSOB")" "400"

PERIOD=$(date +%Y-%m)
B1=$(curl -fsS -X POST "$BASE/api/platform/billing" -H "$AD" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"tenantId\":5001}")
B1N=$(echo "$B1" | jget "import sys,json; d=json.load(sys.stdin)['data']; print(d['generated']+d['skipped'])")
[ "${B1N:-0}" -ge 1 ] && ok "D5 增值服务按账期计费（$B1N 个模块）" || bad "D5 计费无目标模块"
B2=$(curl -fsS -X POST "$BASE/api/platform/billing" -H "$AD" -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\",\"tenantId\":5001}")
check "D5 计费幂等（二次生成 0 条）" "$(echo "$B2" | jget "import sys,json; print(json.load(sys.stdin)['data']['generated'])")" "0"
BILLS=$(curl -fsS "$BASE/api/platform/billing?period=$PERIOD" -H "$AP")
BILLID=$(echo "$BILLS" | jget "import sys,json; d=json.load(sys.stdin)['data']['list']; print(d[0]['id'] if d else '')")
[ -n "$BILLID" ] && ok "D5 账单列表可查" || bad "D5 账单列表为空"
if [ -n "$BILLID" ]; then
  SET=$(curl -fsS -X PUT "$BASE/api/platform/billing/$BILLID/settle" -H "$AD")
  check "D5 账单结清" "$(echo "$SET" | jget "import sys,json; print(json.load(sys.stdin)['data']['status'])")" "paid"
else
  bad "D5 账单结清（无账单可结）"
fi
PSUM=$(curl -fsS "$BASE/api/platform/summary" -H "$AP")
check "D5 平台运营汇总可查" "$(echo "$PSUM" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d['code']==0 and 'unpaidAmount' in d['data'] else 'bad')")" "ok"

# 恢复全量开通：避免本套件的授权状态影响其他回归套件的前端菜单
ALLMOD=$(echo "$MODS" | jget "import sys,json; d=json.load(sys.stdin)['data']['list']; print(json.dumps({'modules':[{'code':m['module_code'],'enabled':1} for m in d]},ensure_ascii=False))")
RESTORE=$(curl -fsS -X POST "$BASE/api/platform/tenant/5001/modules" -H "$AD" -H 'Content-Type: application/json' -d "$ALLMOD")
check "D5 收尾恢复 18 模块全量开通" "$(echo "$RESTORE" | jget "import sys,json; print(json.load(sys.stdin)['data']['opened'])")" "18"

# ==================================================================
echo "----- 铁律 6 归档归属 -----"
FIN=$(curl -fsS "$BASE/api/flow/health/modules" -H "$AE")
DMARC=$(echo "$FIN" | jget "import sys,json; d=json.load(sys.stdin)['data']['modules']; ms=[m for m in d if m['code'] in ('monitor','budget','risk','dangerspecial')]; print(len([m for m in ms if m['archiveItems']>0]))")
check "D 阶段 4 个业务模块均自动登记归档项" "$DMARC" "4"
PLARC=$(echo "$FIN" | jget "import sys,json; d=json.load(sys.stdin)['data']['modules']; print([m['archiveItems'] for m in d if m['code']=='platform'][0])")
check "D5 平台配置按设计不产生企业档案盒资料" "$PLARC" "0"

echo ""
echo "===== 结果：$PASS 通过，$FAIL 失败 ====="
[ "$FAIL" = "0" ] && echo "🎉 D 阶段（三期 v1.2）全链路验收通过" || echo "⚠️ 有失败项"
exit $FAIL
