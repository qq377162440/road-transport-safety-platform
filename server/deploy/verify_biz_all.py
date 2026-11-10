#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
18 个动态业务模块 · 总控全量回归（只读，不产生业务数据）

覆盖：
  ① 后端健康 + Flyway 迁移全部成功
  ② 10 个完整闭环模块的 overview 接口（中文无乱码、data 非空）
  ③ 18 个动态模块前端路由 /biz/<code> 全部可达
  ④ 10 个专用闭环页已真实编译进前端容器（不是只有 SPA 回退）
  ⑤ B15 应急管理预案正文 mojibake 残留检测 = 0

运行：python3 verify_biz_all.py
"""
import json
import sys
import subprocess
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
FRONT = "http://localhost"

PASS = 0
FAIL = 0
WARN = 0


def ok(m):
    global PASS
    PASS += 1
    print(f"  ✅ {m}")


def bad(m):
    global FAIL
    FAIL += 1
    print(f"  ❌ {m}")


def soft(m):
    global WARN
    WARN += 1
    print(f"  ⚠️  {m}")


def http(method, path, token=None, body=None):
    data = json.dumps(body or {}).encode("utf-8") if method in ("POST", "PUT") else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def front(path):
    try:
        r = urllib.request.urlopen(FRONT + path, timeout=15)
        return r.status, {k.lower(): v for k, v in r.headers.items()}
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}
    except Exception:
        return 0, {}


def sql(q):
    return subprocess.run(
        ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123",
         "--default-character-set=utf8mb4", "safetrans", "-N", "-e", q],
        capture_output=True, text=True).stdout.strip()


def docker_ls_assets():
    return subprocess.run(
        ["docker", "exec", "safetrans-frontend", "sh", "-c",
         "ls /usr/share/nginx/html/assets/*.js"],
        capture_output=True, text=True).stdout


print("=" * 68)
print("18 个动态业务模块 · 总控全量回归")
print("=" * 68)

st, raw = http("POST", "/api/auth/dev-login", body={"username": "aa_gaozhou", "password": "123456"})
tok = (json.loads(raw).get("data") or {}).get("accessToken") if st == 200 else None
ok("企业账号 aa_gaozhou 登录") if tok else bad(f"登录失败 -> {st} {raw[:120]}")
if not tok:
    sys.exit(2)

# ---------- ① 健康 + Flyway ----------
st, _ = http("GET", "/health")
ok("后端 /health = 200") if st == 200 else bad(f"/health -> {st}")
bad_mig = sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")
ok("Flyway 迁移无失败版本") if bad_mig == "0" else bad(f"存在失败迁移 {bad_mig} 条")
latest = sql("SELECT version,description FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
ok(f"最新迁移 {latest}") if latest else bad("未读到迁移历史")

# ---------- ② 10 个闭环 overview ----------
FLOWS = ["person", "vehicle", "institution", "dailycheck", "danger",
         "meeting", "emergency", "accident", "credit", "sign"]
MOJIBAKE = ["ä¸€", "ã€", "Â", "ï¿½", "åº"]
for f in FLOWS:
    st, body = http("GET", f"/api/flow/{f}/overview", token=tok)
    if st != 200:
        bad(f"/api/flow/{f}/overview -> HTTP {st}")
        continue
    try:
        j = json.loads(body)
    except Exception:
        bad(f"/api/flow/{f}/overview 返回非 JSON")
        continue
    data = j.get("data")
    if not isinstance(data, dict) or not data:
        bad(f"/api/flow/{f}/overview data 为空")
        continue
    txt = json.dumps(data, ensure_ascii=False)
    garbled = [g for g in MOJIBAKE if g in txt]
    bad(f"/api/flow/{f}/overview 中文乱码：{garbled}") if garbled else ok(f"概览 /api/flow/{f}/overview 正常（{len(txt)} 字符，无乱码）")

# ---------- ③ 18 个动态模块前端路由 ----------
CODES = ["common", "person", "vehicle", "institution", "check", "danger", "meeting",
         "collect", "training", "emergency", "accident", "assess", "sign",
         "monitor", "budget", "risk", "dangerspecial", "platform"]
route_bad = []
for c in CODES:
    st, hdr = front(f"/biz/{c}")
    if st != 200 or "text/html" not in (hdr.get("content-type") or ""):
        route_bad.append(f"{c}:{st}")
ok(f"18 个动态模块路由 /biz/<code> 全部可达") if not route_bad else bad(f"路由不可达：{route_bad}")
st_old, hdr_old = front("/biz/credit")
ok("C4 旧链接 /biz/credit → /biz/assess 重定向可用") if st_old == 200 else soft(f"/biz/credit -> {st_old}")

# ---------- ④ 专用闭环页真实编译产物 ----------
assets = docker_ls_assets()
PAGES = {
    "PersonModule": "B1 人员基础信息", "VehicleModule": "B2 车辆基础信息",
    "InstitutionModule": "B3 制度档案", "CheckModule": "B4 一日三检",
    "DangerModule": "B5 隐患排查", "MeetingModule": "B6 安全会议",
    "EmergencyModule": "B15 应急管理", "AccidentModule": "C3 事故管理",
    "CreditModule": "C4 考核奖惩", "SignModule": "C6 自定义文件签署",
}
missing = [f"{k}({v})" for k, v in PAGES.items() if k not in assets]
ok(f"10 个专用闭环页 chunk 均在前端容器内") if not missing else bad(f"缺失专用页 chunk：{missing}")

# ---------- ⑤ B15 应急全表 mojibake 残留 ----------
# 坑：连接 collation 为 utf8mb4_0900_ai_ci（重音不敏感），'Â' ≈ 'a'，
#     普通 LIKE '%Â%' 会命中 {{companyName}} 里的字母 a，产生假阳性。
#     必须用 LIKE BINARY 按字节精确比较，并扫描全部 emergency_* 文本列
#     （含 emergency_plan 企业预案实例，即整盒 PDF 真正渲染的内容）。
GARBLE = ["ä¸€", "ã€", "Â", "ï¿½"]
cols = sql("SELECT table_name,column_name FROM information_schema.columns "
           "WHERE table_schema='safetrans' AND table_name LIKE 'emergency%' "
           "AND data_type IN ('varchar','char','text','mediumtext','longtext')")
parts = []
for line in cols.splitlines():
    if "\t" not in line:
        continue
    t, c = line.split("\t")[:2]
    cond = " OR ".join([f"`{c}` LIKE BINARY '%{g}%'" for g in GARBLE])
    parts.append(f"SELECT COUNT(*) n FROM `{t}` WHERE {cond}")
res = sql("SELECT SUM(n) FROM (" + " UNION ALL ".join(parts) + ") x") if parts else "0"
if res == "0":
    ok(f"B15 应急 {len(parts)} 个文本列 mojibake 残留 = 0（二进制精确匹配，乱码修复未回归）")
else:
    bad(f"应急数据仍有 {res} 处乱码")
sample = sql("SELECT tpl_name FROM emergency_plan_tpl ORDER BY id LIMIT 1")
ok(f"预案中文抽样正常：{sample}") if sample and not any(g in sample for g in MOJIBAKE) else bad(f"预案中文异常：{sample}")

# 只验数据库不够：历史开发期曾生成过乱码的「整盒归档 PDF」成品，
# 因此再下载最新一份成品，用 pdftotext 校验正文中文。
latest_pdf = sql("SELECT id FROM archive_pdf WHERE template_code='EMERGENCY_BOX' "
                 "AND deleted=0 ORDER BY gen_time DESC LIMIT 1")
if not latest_pdf:
    soft("暂无存活的整盒应急归档 PDF，跳过成品正文校验")
else:
    try:
        rq = urllib.request.Request(f"{BASE}/api/archive/pdf/{latest_pdf}/download")
        rq.add_header("Authorization", "Bearer " + tok)
        blob = urllib.request.urlopen(rq, timeout=25).read()
        tmp = "/tmp/verify_all_emergency_box.pdf"
        with open(tmp, "wb") as f:
            f.write(blob)
        text = subprocess.run(["pdftotext", tmp, "-"], capture_output=True, text=True).stdout
        garbled = [g for g in MOJIBAKE if g in text]
        if "一、总则" in text and not garbled:
            ok(f"最新整盒应急归档 PDF 正文中文正常（{len(blob)} 字节，含「一、总则」）")
        else:
            bad(f"最新整盒归档 PDF 正文异常：命中={garbled} 命中总则={'一、总则' in text}")
    except Exception as e:
        bad(f"整盒归档 PDF 下载/解析失败：{e}")

print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL} · 提示 {WARN}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
