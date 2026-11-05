#!/bin/bash
# 端到端集成测试：完整闭环
set -e
BASE=http://localhost:8080
PASS=0
FAIL=0

function ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
function bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

echo "===== 端到端集成测试 ====="

# 1. 登录
CAP=$(curl -s $BASE/api/auth/captcha)
CAPID=$(echo "$CAP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
CODE=$(docker exec safetrans-redis redis-cli get "captcha:$CAPID")
LOGIN=$(curl -s -X POST $BASE/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"aa_gaozhou\",\"password\":\"123456\",\"captchaId\":\"$CAPID\",\"captcha\":\"$CODE\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$TOKEN" ] && ok "登录成功" || bad "登录失败"
AUTH="Authorization: Bearer $TOKEN"

# 2. 企业信息
INFO=$(curl -s $BASE/api/tenant/info -H "$AUTH")
LEGAL=$(echo "$INFO" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('legalPerson',''))")
[ -n "$LEGAL" ] && ok "企业信息（法人=$LEGAL）" || bad "企业信息为空"

# 3. 组织架构（启用岗位）
ENABLED=$(curl -s $BASE/api/org/posts/enabled -H "$AUTH")
COUNT=$(echo "$ENABLED" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "$COUNT" = "4" ] && ok "启用岗位 4 个" || bad "启用岗位数量=$COUNT"

# 4. 岗位改名（安全员→安环主管）
SAFE_ID=$(curl -s $BASE/api/org/posts -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print([p['id'] for p in d if p['funcKey']=='safe'][0])")
RENAME=$(curl -s -X PUT $BASE/api/org/post/$SAFE_ID/rename -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"安环主管"}')
NEWNAME=$(echo "$RENAME" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['postName'])")
[ "$NEWNAME" = "安环主管" ] && ok "岗位改名成功" || bad "改名失败=$NEWNAME"

# 5. 责任制联动（含安环主管）
RESP=$(curl -s $BASE/api/tpl/responsibility -H "$AUTH")
HAS_NEW=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print('安环主管' in [r['name'] for r in d])")
[ "$HAS_NEW" = "True" ] && ok "责任制联动（含安环主管）" || bad "责任制未联动"

# 6. 范本套列表
SETS=$(curl -s $BASE/api/tpl/set -H "$AUTH")
SET_COUNT=$(echo "$SETS" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))")
[ "$SET_COUNT" -ge 2 ] && ok "范本套列表（$SET_COUNT 套）" || bad "范本套数量=$SET_COUNT"

# 7. 生成责任制 PDF 并归档
GEN=$(curl -s -X POST $BASE/api/archive/responsibility/generate -H "$AUTH")
PDF_ID=$(echo "$GEN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -n "$PDF_ID" ] && ok "生成归档 PDF（id=$PDF_ID）" || bad "归档失败"

# 8. 下载 PDF
DL=$(curl -s -o /tmp/e2e_download.pdf -w "%{http_code}" $BASE/api/archive/pdf/$PDF_ID/download -H "$AUTH")
SIZE=$(stat -c%s /tmp/e2e_download.pdf 2>/dev/null || echo 0)
[ "$DL" = "200" ] && [ "$SIZE" -gt 1000 ] && ok "下载 PDF（$SIZE bytes）" || bad "下载失败"

# 9. 禁用强制岗位（应报错）
GM_ID=$(curl -s $BASE/api/org/posts -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print([p['id'] for p in d if p['funcKey']=='gm'][0])")
TOGGLE=$(curl -s -X PUT $BASE/api/org/post/$GM_ID/toggle -H "$AUTH")
TOGGLE_CODE=$(echo "$TOGGLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")
[ "$TOGGLE_CODE" != "0" ] && ok "强制岗位禁用被拦截" || bad "强制岗位未被拦截"

echo ""
echo "===== 结果：$PASS 通过，$FAIL 失败 ====="
[ "$FAIL" = "0" ] && echo "🎉 全部通过" || echo "⚠️ 有失败项"
exit $FAIL
