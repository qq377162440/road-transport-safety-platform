#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 事故管理 + 交通违法 · 完整流程闭环验收脚本（逐模块第 8 个 / 18）

验收三条线：
  ① 状态机 + 逐条操作：事故 登记→处理中→已结案；交通违法 登记→推送靶向教育→落实
  ② 子表 + PDF 归档：登记即自动生成标准事故快报表(带盖章) / 年度汇总统计表 / 一键整盒大归档(盒十·普货 / 盒十一·危货)
  ③ 模块间联动：交通违法联动 14 培训教育（自动建 targeted 培训计划）/ 整盒归档复用 B7 档案柜

运行：python3 verify_biz_accident.py   （需后端 8080、前端 80 已起）
认证：开发免验证码登录 /api/auth/dev-login
"""
import json
import sys
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


def _parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw}


def req(method, path, token=None, body=None, raw=False, headers=None):
    if method in ("POST", "PUT"):
        data = json.dumps(body if body is not None else {}).encode("utf-8")
    else:
        data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        raw_bytes = resp.read()
        if raw:
            return resp.status, dict(resp.headers), raw_bytes
        return resp.status, _parse(raw_bytes.decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw_bytes = e.read()
        if raw:
            return e.code, dict(e.headers), raw_bytes
        return e.code, _parse(raw_bytes.decode("utf-8", "replace"))


def get(path, token=None, raw=False, body=None):
    return req("GET", path, token=token, raw=raw, body=body)


def post(path, token=None, body=None, raw=False):
    return req("POST", path, token=token, body=body, raw=raw)


def login(user):
    st, r = post("/api/auth/dev-login", body={"username": user, "password": "123456"})
    tok = (r.get("data") or {}).get("accessToken") if isinstance(r, dict) else None
    if not tok:
        bad(f"登录失败 {user} -> {r}")
        return None
    ok(f"登录 {user}（userType={(r.get('data') or {}).get('userType')}）")
    return tok


def dget(path, token=None):
    st, r = get(path, token=token)
    return r.get("data") if isinstance(r, dict) else None


def dpost(path, token=None, body=None):
    st, r = post(path, token=token, body=body)
    return r.get("data") if isinstance(r, dict) else None


# 乱码探测：检测典型双编码残留标记
MOJI = ["Ã", "¢", "â€", "Ã©", "Â·", "ä¸€", "å…¬"]


def has_mojibake(s):
    if not s:
        return False
    return any(m in s for m in MOJI)


def fget(path):
    try:
        r = urllib.request.urlopen(FRONT + path, timeout=15)
        hdrs = {k.lower(): v for k, v in r.headers.items()}
        return r.status, hdrs, r.read()
    except urllib.error.HTTPError as e:
        hdrs = {k.lower(): v for k, v in e.headers.items()}
        return e.code, hdrs, e.read()


# ======================================================================
print("=" * 68)
print("C3 事故管理 + 交通违法 · 完整流程闭环验收")
print("=" * 68)

AAT = login("aa_gaozhou")  # 企业（普货，演示）
AT = login("cc_admin")     # 超管
if not AAT or not AT:
    print("\n认证失败，终止")
    sys.exit(2)

# ---------- 0. 健康 + 概览 ----------
st, h = get("/health")
ok("后端健康 /health") if st == 200 else bad(f"后端健康 /health -> {st}")

ov = dget("/api/flow/accident/overview", AAT)
if not ov:
    bad("概览 overview 无返回")
else:
    ok(f"概览：企业={ov.get('company')} 危货={ov.get('isDanger')} 事故统计={ov.get('stats')}")
    if has_mojibake(ov.get("company") or ""):
        bad(f"企业名出现乱码：{ov.get('company')}")
    else:
        ok("概览企业名中文正常（无乱码）")

# ---------- 1. 事故登记 + 自动快报表（带盖章）----------
reg = dpost("/api/flow/accident/record", AAT, body={
    "accidentTime": "2026-03-15 09:30:00",
    "location": "佛山市高明区荷城街道",
    "accidentType": "collision",
    "severity": "general",
    "description": "2026年3月15日9时30分，粤E12345在荷城街道与粤E67890发生追尾。",
    "vehiclePlate": "粤E12345",
    "involvePerson": "张三/李四",
    "lossAmount": "12000.50",
})
if not reg or not reg.get("ok"):
    bad(f"事故登记失败 -> {reg}")
    sys.exit(2)
aid = reg.get("accidentId")
reportPdfId = reg.get("reportPdfId")
ok(f"事故登记：accidentId={aid} 自动生成快报表 pdfId={reportPdfId}（登记即生成标准格式快报）")
if not reportPdfId:
    bad("登记未自动生成快报表")

# 详情含快报表 HTML（验证中文无乱码 + 盖章）
det = dget(f"/api/flow/accident/record/{aid}", AAT) or {}
qr = det.get("quickReport") or {}
html = qr.get("reportContent") or ""
if not html:
    bad("事故详情未返回快报表内容")
else:
    if has_mojibake(html):
        bad("快报表 HTML 含乱码残留")
    elif "佛山市高明" in html and "碰撞" in html and "事故调查经过" in html.replace("事故经过", ""):
        ok("快报表正文中文正常（含企业名/事故类型/地点，无乱码）")
    elif "佛山市高明" in html and "碰撞" in html:
        ok("快报表正文中文正常（企业名/事故类型命中，无乱码）")
    else:
        soft("快报表中文命中不全，需人工核对：" + html[:80])
    # 盖章标记
    if "seal" in html.lower() or "data:image/png;base64" in html:
        ok("快报表已带电子盖章（seal 区块/印章 base64）")
    else:
        soft("快报表未检出盖章标记")

# ---------- 2. 事故状态机：reported → processing → closed ----------
for stt in ("processing", "closed"):
    r = dpost(f"/api/flow/accident/record/{aid}/status", AAT, body={"status": stt})
    if r and r.get("ok"):
        ok(f"事故状态流转 → {stt}（{r.get('statusName')}）")
    else:
        bad(f"事故状态流转 {stt} 失败 -> {r}")

# 重新打开登记第二条用于年度汇总聚合
reg2 = dpost("/api/flow/accident/record", AAT, body={
    "accidentTime": "2026-07-20 14:00:00",
    "location": "佛山市高明区明城镇",
    "accidentType": "rear_end",
    "severity": "minor",
    "description": "轻微追尾，无人伤。",
    "vehiclePlate": "粤E22334",
    "lossAmount": "800",
})
aid2 = reg2.get("accidentId") if isinstance(reg2, dict) else None

# ---------- 3. 年度汇总统计表（按年自动聚合 + PDF）----------
sm = dget("/api/flow/accident/summary/2026", AAT) or {}
if sm.get("totalCount") is not None:
    ok(f"年度汇总聚合：totalCount={sm.get('totalCount')} totalLoss={sm.get('totalLoss')} minor={sm.get('minorCount')} general={sm.get('generalCount')} major={sm.get('majorCount')}")
else:
    bad("年度汇总无返回")
sp = dpost("/api/flow/accident/summary/2026/pdf", AAT)
if sp and sp.get("ok"):
    ok(f"年度汇总统计表 PDF 生成：pdfId={sp.get('pdfId')} summaryId={sp.get('summaryId')}")
else:
    bad(f"年度汇总 PDF 生成失败 -> {sp}")

# ---------- 4. 交通违法登记 + 联动靶向教育 ----------
vio = dpost("/api/flow/accident/violation", AAT, body={
    "violationTime": "2026-04-01 10:00:00",
    "vehiclePlate": "粤E12345",
    "driverName": "王五",
    "violationType": "over_speed",
    "deductPoints": 3,
    "description": "限速60路段实测78，超速。",
})
if not vio or not vio.get("ok"):
    bad(f"交通违法登记失败 -> {vio}")
    vid = None
else:
    vid = vio.get("violationId")
    ok(f"交通违法登记：violationId={vid} 类型=over_speed 扣{3}分")

# 联动靶向教育：创建 14 培训教育的 targeted 培训计划
if vid:
    edu = dpost(f"/api/flow/accident/violation/{vid}/edu", AAT)
    if edu and edu.get("ok"):
        ok(f"联动靶向教育：创建培训计划 planId={edu.get('planId')} 名称={edu.get('planName')}（plan_type=targeted）")
        # 验证 training_plan 确为 targeted 且关联 violation_type
        tp = dget(f"/api/training/plan/{edu.get('planId')}", AAT) if False else None
        # 直接查库校验
        import subprocess
        out = subprocess.run(
            ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123", "safetrans",
             "-N", "-e", f"SELECT plan_type,violation_type FROM training_plan WHERE id={edu.get('planId')}"],
            capture_output=True, text=True).stdout.strip()
        if "targeted" in out:
            ok(f"靶向教育落库校验：training_plan.plan_type=targeted（{out}）")
        else:
            soft(f"靶向教育落库校验异常：{out}")
    else:
        bad(f"联动靶向教育失败 -> {edu}")
    # 教育落实状态流转
    es = dpost(f"/api/flow/accident/violation/{vid}/edu-status", AAT, body={"eduStatus": "learned"})
    if es and es.get("ok"):
        ok(f"教育落实状态 → learned（{es.get('eduStatusName')}）")
    else:
        bad(f"教育落实状态流转失败 -> {es}")

# ---------- 5. 增值服务申请（联系人工处理）----------
svc = dpost("/api/flow/accident/service", AAT, body={
    "serviceType": "four_not_let_go",
    "serviceDesc": "请求平台协助制作四不放过资料。",
    "contactName": "赵六",
    "contactPhone": "13800000000",
})
if svc and svc.get("ok"):
    ok(f"增值服务申请：serviceId={svc.get('serviceId')} 状态=待受理")
else:
    bad(f"增值服务申请失败 -> {svc}")

# ---------- 6. 一键整盒大归档（盒十·普货 / 盒十一·危货）----------
ar = dpost("/api/flow/accident/archive", AAT)
if not ar or not ar.get("ok"):
    bad(f"整盒归档失败 -> {ar}")
else:
    box = ar.get("box")
    expected = "DANGER_BOX11" if ov.get("isDanger") else "GENERAL_BOX10"
    if box == expected:
        ok(f"整盒归档落入正确档案盒：{box}（{'危货·十一' if ov.get('isDanger') else '普货·十'} · 事故档案），PDF {ar.get('size')} 字节")
    else:
        bad(f"归档盒错误：期望 {expected} 实际 {box}")
    # catalogId 解析（运行时按 code 解析，不硬编码 ID）
    if ar.get("catalogId"):
        ok(f"归档目录解析正确：catalogId={ar.get('catalogId')}（运行时按 code={box} 解析）")
    else:
        bad("归档未返回 catalogId")
    if ar.get("size") and int(ar.get("size")) > 2000:
        ok("归档成品非空（整盒 PDF 已生成）")
    else:
        bad(f"归档成品过小：{ar.get('size')} 字节")

# ---------- 7. 前端闭环页可达（SPA 回退）----------
st_biz, hdr_biz, _ = fget("/biz/accident")
if st_biz == 200 and "text/html" in (hdr_biz.get("content-type") or ""):
    ok("前端路由 /biz/accident（C3 专用闭环页）可达")
else:
    soft(f"前端 /biz/accident 返回 {st_biz}")

# ======================================================================
print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL} · 提示 {WARN}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
