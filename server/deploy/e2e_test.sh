#!/bin/bash
# 端到端集成测试：登录 → 企业信息 → 组织架构 → 范本套 → 岗位改名联动
#                 → 责任制生成 → PDF → 归档 → 下载 → 强制岗位拦截（9 项）
#
# 两种运行方式（脚本自动适配）：
#   1) 宿主机：        bash e2e_test.sh
#   2) compose 容器内： docker compose --profile test run --rm e2e
#      容器内通过 REDIS_HOST=redis 直连 redis-cli；宿主机通过 docker exec 取验证码。
set -uo pipefail

BASE=${BASE_URL:-http://localhost:8080}
REDIS_HOST=${REDIS_HOST:-localhost}
PASS=0
FAIL=0

ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

# python3 解析 JSON，失败返回空串，不让脚本因单点格式问题崩掉
jget() { python3 -c "$1" 2>/dev/null || echo ""; }

# 读取验证码：容器内 redis-cli 直连 / 宿主机 docker exec
read_captcha() {
  local key="captcha:$1"
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h "$REDIS_HOST" get "$key" 2>/dev/null | tr -d '\r\n'
  else
    docker exec safetrans-redis redis-cli get "$key" 2>/dev/null | tr -d '\r\n'
  fi
}

echo "===== 端到端集成测试 ====="
echo "BASE=$BASE  REDIS_HOST=$REDIS_HOST"

# 0. 等待后端真正就绪（/health 返回 UP）
echo "----- 等待后端就绪 -----"
READY=0
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/health" 2>/dev/null | grep -q '"status":"UP"'; then
    READY=1; break
  fi
  sleep 2
done
if [ "$READY" = "1" ]; then ok "后端健康检查 /health = UP"; else bad "后端 120s 内未就绪"; fi
[ "$READY" = "1" ] || { echo "===== 结果：$PASS 通过，$FAIL 失败 ====="; exit 1; }

echo "----- 业务闭环 -----"

# 1. 登录（真实验证码：从 Redis 读取）
CAP=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
CAPID=$(echo "$CAP" | jget "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
CODE=$(read_captcha "$CAPID")
LOGIN=$(curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"aa_gaozhou\",\"password\":\"123456\",\"captchaId\":\"$CAPID\",\"captcha\":\"$CODE\"}")
TOKEN=$(echo "$LOGIN" | jget "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$TOKEN" ] && ok "登录成功（验证码取自 Redis）" || bad "登录失败：captchaId=$CAPID code=$CODE"
AUTH="Authorization: Bearer $TOKEN"

# 2. 企业信息
INFO=$(curl -fsS --max-time 10 "$BASE/api/tenant/info" -H "$AUTH")
LEGAL=$(echo "$INFO" | jget "import sys,json; print(json.load(sys.stdin)['data'].get('legalPerson',''))")
[ -n "$LEGAL" ] && ok "企业信息（法人=$LEGAL）" || bad "企业信息为空"

# 3. 组织架构（启用岗位）
ENABLED=$(curl -fsS --max-time 10 "$BASE/api/org/posts/enabled" -H "$AUTH")
COUNT=$(echo "$ENABLED" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "$COUNT" = "4" ] && ok "启用岗位 4 个" || bad "启用岗位数量=${COUNT:-无响应}"

# 4. 岗位改名（用临时名验收，末尾改回「安全员」，避免污染交付态）
TMP_NAME='安全员（改名验收）'
SAFE_ID=$(curl -fsS --max-time 10 "$BASE/api/org/posts" -H "$AUTH" | jget "import sys,json; d=json.load(sys.stdin)['data']; print([p['id'] for p in d if p['funcKey']=='safe'][0])")
RENAME=$(curl -fsS --max-time 10 -X PUT "$BASE/api/org/post/$SAFE_ID/rename" -H "$AUTH" -H "Content-Type: application/json" -d "{\"name\":\"$TMP_NAME\"}")
NEWNAME=$(echo "$RENAME" | jget "import sys,json; print(json.load(sys.stdin)['data']['postName'])")
[ "$NEWNAME" = "$TMP_NAME" ] && ok "岗位改名成功" || bad "改名失败=${NEWNAME:-无响应}"

# 5. 责任制联动（含改名后的岗位）
RESP=$(curl -fsS --max-time 10 "$BASE/api/tpl/responsibility" -H "$AUTH")
HAS_NEW=$(echo "$RESP" | jget "import sys,json; d=json.load(sys.stdin)['data']; print('$TMP_NAME' in [r['name'] for r in d])")
[ "$HAS_NEW" = "True" ] && ok "责任制联动（含改名后岗位）" || bad "责任制未联动"

# 6. 范本套列表
SETS=$(curl -fsS --max-time 10 "$BASE/api/tpl/set" -H "$AUTH")
SET_COUNT=$(echo "$SETS" | jget "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "${SET_COUNT:-0}" -ge 2 ] 2>/dev/null && ok "范本套列表（$SET_COUNT 套）" || bad "范本套数量=${SET_COUNT:-无响应}"

# 7. 生成责任制 PDF 并归档
GEN=$(curl -fsS --max-time 60 -X POST "$BASE/api/archive/responsibility/generate" -H "$AUTH")
PDF_ID=$(echo "$GEN" | jget "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$PDF_ID" ] && ok "生成归档 PDF（id=$PDF_ID）" || bad "归档失败"

# 8. 下载 PDF
DL=$(curl -s -o /tmp/e2e_download.pdf -w "%{http_code}" --max-time 60 "$BASE/api/archive/pdf/$PDF_ID/download" -H "$AUTH")
SIZE=$(wc -c < /tmp/e2e_download.pdf 2>/dev/null || echo 0)
[ "$DL" = "200" ] && [ "${SIZE:-0}" -gt 1000 ] && ok "下载 PDF（$SIZE bytes）" || bad "下载失败 HTTP=$DL SIZE=$SIZE"

# 9. 禁用强制岗位（应被拦截）
GM_ID=$(curl -fsS --max-time 10 "$BASE/api/org/posts" -H "$AUTH" | jget "import sys,json; d=json.load(sys.stdin)['data']; print([p['id'] for p in d if p['funcKey']=='gm'][0])")
TOGGLE=$(curl -fsS --max-time 10 -X PUT "$BASE/api/org/post/$GM_ID/toggle" -H "$AUTH")
TOGGLE_CODE=$(echo "$TOGGLE" | jget "import sys,json; print(json.load(sys.stdin)['code'])")
[ -n "$TOGGLE_CODE" ] && [ "$TOGGLE_CODE" != "0" ] && ok "强制岗位禁用被拦截（code=$TOGGLE_CODE）" || bad "强制岗位未被拦截：code=${TOGGLE_CODE:-无响应}"

# 10. 恢复交付态岗位名「安全员」（改名验收用临时名，不得留在交付数据里）
RESTORE=$(curl -fsS --max-time 10 -X PUT "$BASE/api/org/post/$SAFE_ID/rename" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"安全员"}')
RESTORE_NAME=$(echo "$RESTORE" | jget "import sys,json; print(json.load(sys.stdin)['data']['postName'])")
[ "$RESTORE_NAME" = "安全员" ] && ok "岗位名恢复为「安全员」" || bad "岗位名恢复失败=${RESTORE_NAME:-无响应}"

echo ""
echo "===== 结果：$PASS 通过，$FAIL 失败 ====="
[ "$FAIL" = "0" ] && echo "🎉 全部通过" || echo "⚠️ 有失败项"
exit $FAIL
