#!/usr/bin/env bash
# 命令行签约开户工具（E2）。服务商在交付现场可不开网页直接开户。
#
# 用法：
#   ./open_account.sh "企业全称" "统一社会信用代码" [GENERAL|DANGER] [地区] [负责人] [电话]
# 环境覆盖：PROVIDER_USER（默认 bb_provider）、PROVIDER_PASS（默认 123456）、
#          BASE_URL（默认 http://localhost:8080）
#
# 产出：新租户 ID、主账号与初始密码、自动创建的岗位与检查项数量。
set -euo pipefail
cd "$(dirname "$0")"

NAME=${1:-}
CODE=${2:-}
BIZ=${3:-GENERAL}
REGION=${4:-}
CONTACT=${5:-}
PHONE=${6:-}
BASE=${BASE_URL:-http://localhost:8080}
USER=${PROVIDER_USER:-bb_provider}
PASS=${PROVIDER_PASS:-123456}

[ -n "$NAME" ] && [ -n "$CODE" ] || {
  echo "用法: ./open_account.sh \"企业全称\" \"统一社会信用代码\" [GENERAL|DANGER] [地区] [负责人] [电话]"
  exit 1
}

get_captcha_code() {
  docker exec safetrans-redis redis-cli get "captcha:$1" 2>/dev/null | tr -d '\r\n'
}

CAP=$(curl -fsS --max-time 10 "$BASE/api/auth/captcha")
CAPID=$(echo "$CAP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['captchaId'])")
CCODE=$(get_captcha_code "$CAPID")
LOGIN=$(curl -fsS --max-time 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\",\"captchaId\":\"$CAPID\",\"captcha\":\"$CCODE\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -n "$TOKEN" ] || { echo "❌ 服务商登录失败"; exit 1; }

PAYLOAD=$(python3 -c "
import json,sys
print(json.dumps({'name':sys.argv[1],'credit_code':sys.argv[2],'biz_type':sys.argv[3],
  'region':sys.argv[4],'contact':sys.argv[5],'phone':sys.argv[6]}, ensure_ascii=False))
" "$NAME" "$CODE" "$BIZ" "$REGION" "$CONTACT" "$PHONE")

RES=$(curl -fsS --max-time 20 -X POST "$BASE/api/onboarding/open" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$PAYLOAD")

echo "$RES" | python3 -c "
import sys,json
d=json.load(sys.stdin)
if d.get('code') != 0:
    print('❌ 开户失败：' + str(d.get('msg'))); sys.exit(1)
x=d['data']
print('✅ 开户成功')
print('  企业全称   :', x['tenantName'])
print('  租户 ID    :', x['tenantId'])
print('  主账号     :', x['username'])
print('  初始密码   :', x['initialPassword'], '（请提醒企业首次登录后立即修改）')
print('  营运类型   :', x['bizType'])
print('  默认岗位   :', x['defaultPosts'])
print('  交付检查项 :', x['checkItems'])
print('  下一步     :', x['nextStep'])
print('  接入填报   : /m/onboard（企业主账号登录）')
"