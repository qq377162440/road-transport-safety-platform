#!/bin/bash
# 阶段 A/B/C 全链路验收脚本（B10 / C7）
# 在 compose 网络内的 e2e 容器中运行：
#   docker compose --profile test run --rm e2e ./e2e_phase_test.sh
# 覆盖：元数据引擎、通用 CRUD + 归档登记、文件服务、操作日志、Swagger、
#       错误码、越权、JWT 刷新、密码策略、限流、以及 B/C 全部专用闭环。
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

login() {
  local user=$1
  local cap capid code
  cap=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
  capid=$(echo "$cap" | jget "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
  code=$(captcha_code "$capid")
  curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"123456\",\"captchaId\":\"$capid\",\"captcha\":\"$code\"}"
}

echo "===== 阶段 A/B/C 全链路验收 ====="
echo "BASE=$BASE"

# 0. 等待后端
for i in $(seq 1 60); do
  curl -fsS --max-time 3 "$BASE/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 2
done
curl -fsS --max-time 3 "$BASE/health" | grep -q '"status":"UP"' && ok "后端健康" || bad "后端未就绪"

ENT=$(login aa_gaozhou)
ET=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
RT=$(echo "$ENT" | jget "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])")
[ -n "$ET" ] && ok "企业账号登录" || bad "企业账号登录失败"
AE="Authorization: Bearer $ET"

PRO=$(login bb_provider)
PT=$(echo "$PRO" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$PT" ] && ok "服务商账号登录" || bad "服务商登录失败"
AP="Authorization: Bearer $PT"

echo "----- A2/A4/A5 基建 -----"

# 1. 元数据 18 模块（A/B/C 13 个 + 三期 D1~D5 5 个）
META=$(curl -fsS "$BASE/api/biz/meta" -H "$AE")
MC=$(echo "$META" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
check "元数据模块数" "$MC" "18"

# 2. 字段元数据
FLD=$(curl -fsS "$BASE/api/biz/person/fields" -H "$AE")
FC=$(echo "$FLD" | jget "import sys,json; print(len(json.load(sys.stdin)['data']['fields']))")
[ "${FC:-0}" -gt 5 ] && ok "person 字段元数据（$FC 字段）" || bad "字段元数据异常"

# 3. 通用 CRUD 新增 + 归档登记
NEW=$(curl -fsS -X POST "$BASE/api/biz/person" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"person_type":"employee","name":"验收测试员","telephone":"13900000000"}')
PID=$(echo "$NEW" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$PID" ] && ok "通用引擎新增人员（id=$PID）" || bad "新增失败"

ARC=$(curl -fsS "$BASE/api/flow/health/modules" -H "$AE")
AC=$(echo "$ARC" | jget "import sys,json; d=json.load(sys.stdin)['data']['modules']; print([m['archiveItems'] for m in d if m['code']=='person'][0])")
[ "${AC:-0}" -ge 1 ] && ok "铁律6：新增自动登记归档项（$AC 项）" || bad "归档项未登记"

# 4. 分页查询 + 关键字
LST=$(curl -fsS -G "$BASE/api/biz/person" --data-urlencode "keyword=验收测试员" -H "$AE")
LT=$(echo "$LST" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
[ "${LT:-0}" -ge 1 ] && ok "分页查询与关键字搜索" || bad "查询失败"

# 5. 编辑
UPD=$(curl -fsS -X PUT "$BASE/api/biz/person/$PID" -H "$AE" -H 'Content-Type: application/json' -d '{"name":"验收测试员2"}')
UC=$(echo "$UPD" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
check "通用引擎编辑" "$UC" "0"

# 6. 非法表 / 非法字段拒绝
ILL=$(curl -fsS "$BASE/api/biz/not_exist_table" -H "$AE")
IC=$(echo "$ILL" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$IC" != "0" ] && ok "非法业务表被拦截（code=$IC）" || bad "非法表未拦截"

# 7. 必填校验
REQ=$(curl -fsS -X POST "$BASE/api/biz/person" -H "$AE" -H 'Content-Type: application/json' -d '{}')
RC=$(echo "$REQ" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$RC" != "0" ] && ok "必填字段校验（code=$RC）" || bad "必填校验缺失"

# 8. 文件上传 + 下载
echo "全链路验收附件" > /tmp/abc_upload.txt
UPL=$(curl -fsS -X POST "$BASE/api/file/upload" -H "$AE" -F "file=@/tmp/abc_upload.txt" -F "bizModule=common")
FID=$(echo "$UPL" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
DLC=$(curl -s -o /tmp/abc_download.txt -w "%{http_code}" "$BASE/api/file/$FID/download" -H "$AE")
[ "$DLC" = "200" ] && [ -s /tmp/abc_download.txt ] && ok "通用文件上传/下载（id=$FID）" || bad "文件服务异常 HTTP=$DLC"

# 9. 操作日志
OPC=$(curl -fsS "$BASE/api/biz/sys_oper_log" -H "$AE" | jget "import sys,json; print(json.load(sys.stdin)['data']['total'])")
[ "${OPC:-0}" -ge 1 ] && ok "写操作自动记审计日志（$OPC 条）" || bad "操作日志未落地"

# 10. Swagger
SW=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/v3/api-docs")
check "Swagger 接口文档" "$SW" "200"

echo "----- A3 质量与安全 -----"

# 11. JWT 刷新
REF=$(curl -fsS -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}")
NT=$(echo "$REF" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$NT" ] && ok "JWT refresh 换取新 access token" || bad "刷新失败"
# refresh 会刷新 Redis 白名单（旧 access token 随即失效），后续用例改用新 token
AE="Authorization: Bearer $NT"

# 12. 密码策略（弱密码应拒绝，且不改动账号）
WEAK=$(curl -fsS -X POST "$BASE/api/auth/change-password" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"123456","newPassword":"abc123"}')
WC=$(echo "$WEAK" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$WC" != "0" ] && ok "弱密码被拒绝（code=$WC）" || bad "密码策略未生效"

# 13. 越权：企业账号访问服务商专属接口
FORB=$(curl -fsS "$BASE/api/flow/collect/signatures" -H "$AE")
FBC=$(echo "$FORB" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
check "三角色越权拦截（企业→服务商接口）" "$FBC" "403"

# 14. 限流（独立伪造 IP，避免影响主流程）
# 循环次数跟随实际阈值：默认 60，硬编码 22 次会在阈值调高后误报「限流未触发」。
# 阈值由 compose 通过 RATE_LIMIT_MAX 传入，与后端配置保持一致。
RATE_MAX=${RATE_LIMIT_MAX:-60}
RATE=0
RATE_HIT=0
for i in $(seq 1 $((RATE_MAX + 2))); do
  BODY=$(curl -s -H "X-Forwarded-For: 203.0.113.99" "$BASE/api/auth/captcha")
  if echo "$BODY" | grep -q '"code":429'; then RATE=1; RATE_HIT=$i; break; fi
done
[ "$RATE" = "1" ] && ok "匿名接口限流生效（第 $RATE_HIT 次触发 429，阈值 $RATE_MAX）" || bad "限流未触发（阈值 $RATE_MAX）"

echo "----- B 阶段业务闭环 -----"

# 15. B1 一人一档（危货强制体检）
PCHK=$(curl -fsS "$BASE/api/flow/person/$PID/archive-check?bizType=DANGER" -H "$AE")
PQ=$(echo "$PCHK" | jget "import sys,json; print(json.load(sys.stdin)['data']['qualified'])")
[ "$PQ" = "False" ] && ok "B1 危货职业健康检查缺项识别" || bad "B1 检查异常"

# 16. B4 三检任务 + 结果采集（异常联动隐患）
CT=$(curl -fsS -X POST "$BASE/api/flow/check/task" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"vehicle_id":1,"driver_id":6001,"start_date":"2026-11-06","status":"ongoing"}')
CID=$(echo "$CT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$CID" ] && ok "B4 三检任务下发（id=$CID）" || bad "任务下发失败"

CR=$(curl -fsS -X POST "$BASE/api/flow/check/record" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"task_id\":$CID,\"phase\":\"out\",\"result\":\"abnormal\",\"abnormal_note\":\"制动异响\"}")
DID=$(echo "$CR" | jget "import sys,json; print(json.load(sys.stdin)['data']['dangerId'])")
[ -n "$DID" ] && ok "B4 一次提交完成三检并联动隐患（danger=$DID）" || bad "三检采集失败"

# 17. B4 三检月报（只装月报）
MR=$(curl -fsS "$BASE/api/flow/check/monthly-report?year=2026&month=11" -H "$AE")
MA=$(echo "$MR" | jget "import sys,json; print(json.load(sys.stdin)['data']['archived'])")
check "B4 月报汇总归档" "$MA" "True"

# 18. B5 隐患整改验收闭环
R1=$(curl -fsS -X POST "$BASE/api/flow/danger/$DID/rectify" -H "$AE" -H 'Content-Type: application/json' -d '{"rectify_require":"立即检修"}')
R1S=$(echo "$R1" | jget "import sys,json; print(json.load(sys.stdin)['data']['status'])")
check "B5 整改下发" "$R1S" "rectifying"
R2=$(curl -fsS -X POST "$BASE/api/flow/danger/$DID/accept" -H "$AE" -H 'Content-Type: application/json' -d '{"accept_result":"pass"}')
R2S=$(echo "$R2" | jget "import sys,json; print(json.load(sys.stdin)['data']['status'])")
check "B5 验收闭环" "$R2S" "closed"

# 19. B6 会议四段式
MT=$(curl -fsS -X POST "$BASE/api/biz/meeting" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"meeting_type":"monthly","title":"验收测试会议","start_time":"2026-11-06 10:00:00","end_time":"2026-11-06 11:00:00","host_id":6001,"recorder_id":6001}')
MID=$(echo "$MT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
MS=$(curl -fsS -X POST "$BASE/api/flow/meeting/$MID/section" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"section":"notice","notice_title":"会议通知","notice_content":"议题：安全生产"}')
MSC=$(echo "$MS" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
check "B6 会议四段式（通知段）" "$MSC" "0"

# 20. B7 档案柜全景 + 缺项 + 批量生成
OV=$(curl -fsS "$BASE/api/flow/archive/overview?bizType=GENERAL" -H "$AE")
BOX=$(echo "$OV" | jget "import sys,json; print(json.load(sys.stdin)['data']['boxCount'])")
check "B7 普货 10 盒目录树" "$BOX" "10"
OVD=$(curl -fsS "$BASE/api/flow/archive/overview?bizType=DANGER" -H "$AE")
BOXD=$(echo "$OVD" | jget "import sys,json; print(json.load(sys.stdin)['data']['boxCount'])")
check "B7 危货 11 盒目录树" "$BOXD" "11"
# 可重跑断言：批量生成为「幂等补齐」，首次 generated>0、补齐后为 0，两种状态都正确。
# 旧断言硬编码 >0，导致验收脚本重复运行时误报失败。
MIS=$(curl -fsS "$BASE/api/flow/archive/missing?bizType=GENERAL" -H "$AE")
MISOK=$(echo "$MIS" | jget "import sys,json; d=json.load(sys.stdin); print('ok' if d.get('code')==0 and isinstance(d.get('data'),list) else 'bad')")
MISN=$(echo "$MIS" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
check "B7 缺项提示接口可用（当前 $MISN 项）" "$MISOK" "ok"
BG=$(curl -fsS -X POST "$BASE/api/flow/archive/batch-generate?bizType=GENERAL" -H "$AE")
BGC=$(echo "$BG" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
BGN=$(echo "$BG" | jget "import sys,json; print(json.load(sys.stdin)['data']['generated'])")
BG2=$(curl -fsS -X POST "$BASE/api/flow/archive/batch-generate?bizType=GENERAL" -H "$AE")
BG2N=$(echo "$BG2" | jget "import sys,json; print(json.load(sys.stdin)['data']['generated'])")
# code=0 + generated 为非负整数且不超过缺项数 + 二次调用不重复生成
if [ "$BGC" = "0" ] && echo "$BGN" | grep -qE '^[0-9]+$' \
   && [ "${BGN:-9999}" -le "${MISN:-0}" ] && [ "$BG2N" = "0" ]; then
  ok "B7 批量生成待归档项（generated=$BGN / 缺项=$MISN，二次幂等=0）"
else
  bad "批量生成失败（generated=$BGN / 缺项=$MISN / 二次=$BG2N / code=$BGC）"
fi

# 21. B8 服务商代录（provider 角色）
CLT=$(curl -fsS -X POST "$BASE/api/biz/collect_task" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"file_type":"xlsx","target_type":"person","status":0}')
CLID=$(echo "$CLT" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
FILL=$(curl -fsS -X POST "$BASE/api/flow/collect/$CLID/fill" -H "$AP" -H 'Content-Type: application/json' \
  -d '{"rows":[{"name":"代录司机","telephone":"13700000000","person_type":"employee"}],"targetTable":"person"}')
FN=$(echo "$FILL" | jget "import sys,json; print(json.load(sys.stdin)['data']['filled'])")
check "B8 服务商代录（跳过推送）" "$FN" "1"

# 22. B9 小程序 H5 页面存在
MH=$(curl -s -o /dev/null -w "%{http_code}" "${FRONTEND_URL:-http://frontend}/m/check")
[ "$MH" = "200" ] && ok "B9 适老化三检 H5 页面" || bad "B9 页面不可达 HTTP=$MH"

echo "----- C 阶段 P1 -----"

# 23. C1 培训学时 + 考试留痕
TP=$(curl -fsS -X POST "$BASE/api/biz/training_plan" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"plan_name":"验收培训计划","plan_type":"on_job","force_learn":0,"pass_score":60}')
TPID=$(echo "$TP" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
ST=$(curl -fsS -X POST "$BASE/api/flow/training/study" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"plan_id\":$TPID,\"user_id\":6001,\"minutes\":45}")
ASID=$(echo "$ST" | jget "import sys,json; print(json.load(sys.stdin)['data']['assignId'])")
[ -n "$ASID" ] && ok "C1 线上学习学时记录" || bad "学时记录失败"
EX=$(curl -fsS -X POST "$BASE/api/flow/training/exam" -H "$AE" -H 'Content-Type: application/json' \
  -d "{\"plan_id\":$TPID,\"assign_id\":$ASID,\"user_id\":6001,\"score\":88}")
EXR=$(echo "$EX" | jget "import sys,json; print(json.load(sys.stdin)['data']['result'])")
check "C1 考试留痕" "$EXR" "pass"

# 24. C2/C3/C4/C6 表可读写（通用引擎已覆盖，验证元数据可见）
HC=$(curl -fsS "$BASE/api/flow/health/modules" -H "$AE")
HM=$(echo "$HC" | jget "import sys,json; print(json.load(sys.stdin)['data']['moduleCount'])")
check "C2/C3/C4/C6 + D 模块注册" "$HM" "18"
HT=$(echo "$HC" | jget "import sys,json; print(json.load(sys.stdin)['data']['tables'])")
[ "${HT:-0}" -ge 99 ] && ok "全量业务表可访问（$HT 表）" || bad "表数量异常"

# 25. C5 驾驶舱
DR=$(curl -fsS "$BASE/api/flow/dashboard/risk" -H "$AE")
DS=$(echo "$DR" | jget "import sys,json; print(json.load(sys.stdin)['data']['safetyScore'])")
[ -n "$DS" ] && ok "C5 驾驶舱安全分（$DS 分）" || bad "驾驶舱异常"

# 26. C6 自定义签署 PDF 合成
SG=$(curl -fsS -X POST "$BASE/api/biz/sign_task" -H "$AE" -H 'Content-Type: application/json' \
  -d '{"title":"验收签署任务","anchors":"[{\"type\":\"seal\",\"page\":1,\"x\":100,\"y\":200}]"}')
SGID=$(echo "$SG" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
SR=$(curl -fsS -X POST "$BASE/api/flow/sign/$SGID/render" -H "$AE")
SS=$(echo "$SR" | jget "import sys,json; print(json.load(sys.stdin)['data']['size'])")
[ "${SS:-0}" -gt 1000 ] && ok "C6 签署凭证 PDF 合成（$SS bytes）" || bad "签署 PDF 生成失败"

# 27. 归档归属完整性（B10/C7 收口）
# common（系统运维）与 platform（D5 平台配置）不产生企业档案盒资料，按设计豁免；
# 其余业务模块必须配置 catalog，否则铁律 6 的自动归档会漏项。
FIN=$(curl -fsS "$BASE/api/flow/health/modules" -H "$AE")
NOARC=$(echo "$FIN" | jget "import sys,json; d=json.load(sys.stdin)['data']['modules']; print(len([m for m in d if not m['catalog'] and m['code'] not in ('common','platform')]))")
[ "${NOARC:-99}" -eq 0 ] && ok "归档归属完整性：所有业务模块均配置了归属档案盒" || bad "有 $NOARC 个模块未配置归档归属"

# 28. 清理测试记录（逻辑删除，保持种子状态可复现）
curl -fsS -X DELETE "$BASE/api/biz/person/$PID" -H "$AE" >/dev/null 2>&1
echo "  ℹ️  测试主记录已逻辑删除"

echo ""
echo "===== 结果：$PASS 通过，$FAIL 失败 ====="
[ "$FAIL" = "0" ] && echo "🎉 阶段 A/B/C 全链路验收通过" || echo "⚠️ 有失败项"
exit $FAIL
