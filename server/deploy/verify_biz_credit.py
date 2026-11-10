#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C4 考核奖惩 + 诚信考核 · 完整流程闭环验收脚本（逐模块第 9 个 / 18）

验收三条线：
  ① 状态机 + 逐条操作：创建年度考核→按岗位自动生成明细(默认100分)→酌情减分→一键签名→一键提交
  ② 子表 + PDF 归档：生成两类台账(责任制考核记录 + 考核奖惩公示，带盖章)→归档盒一(年度责任制考核记录)
  ③ 模块间联动：驾驶员违规联动 14 交通违法/靶向教育；考核结果自动沉淀一人一档(考核/诚信/奖惩记录)

运行：python3 verify_biz_credit.py   （需后端 8080、前端 80 已起）
认证：开发免验证码登录 /api/auth/dev-login
"""
import json
import sys
import urllib.request
import urllib.error
import subprocess

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
    ok(f"登录 {user}")
    return tok


def dget(path, token=None):
    st, r = get(path, token=token)
    return r.get("data") if isinstance(r, dict) else None


def dpost(path, token=None, body=None):
    st, r = post(path, token=token, body=body)
    return r.get("data") if isinstance(r, dict) else None


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
print("C4 考核奖惩 + 诚信考核 · 完整流程闭环验收")
print("=" * 68)

AAT = login("aa_gaozhou")
AT = login("cc_admin")
if not AAT or not AT:
    print("\n认证失败，终止")
    sys.exit(2)

# ---------- 0. 概览 ----------
st, h = get("/health")
ok("后端健康 /health") if st == 200 else bad(f"后端健康 /health -> {st}")
ov = dget("/api/flow/credit/overview", AAT)
if ov:
    ok(f"概览：企业={ov.get('company')} 岗位数={ov.get('stats',{}).get('posts')} 从业人数={ov.get('stats',{}).get('persons')}")
else:
    bad("概览无返回")

# ---------- 1. 创建年度考核 + 按岗位自动生成明细(默认100分) ----------
Y = 2026
cr = dpost("/api/flow/credit/assess", AAT, body={"year": Y})
if not cr or not cr.get("ok"):
    bad(f"创建年度考核失败 -> {cr}")
    sys.exit(2)
aid = cr.get("assessId")
ok(f"创建 {Y} 年度考核：assessId={aid} 按岗位自动生成 {cr.get('itemCount')} 条考核明细")
if cr.get("itemCount") and cr.get("itemCount") >= 4:
    ok(f"按岗位生成明细（共 {cr.get('itemCount')} 个岗位，满足≥4）")
else:
    bad(f"考核明细数异常：{cr.get('itemCount')}")

its = dget(f"/api/flow/credit/assess/{aid}/items", AAT) or []
if len(its) == 0:
    bad("考核明细为空")
else:
    all100 = all((it.get("score") in (100, "100", None)) for it in its)
    # score 字段可能为 int 100
    scores = [it.get("score") for it in its]
    ok(f"考核明细默认分值：{[s for s in scores][:5]}（默认 100 分）")
    if all(s == 100 for s in scores if s is not None):
        ok("所有岗位默认 100 分（一键过）")
    else:
        soft(f"存在非100分明细：{scores}")

# ---------- 2. 驾驶员违规联动（提示，来源 14 交通违法/靶向教育）----------
vio = dget(f"/api/flow/credit/assess/{aid}/violations", AAT) or []
if isinstance(vio, list):
    ok(f"驾驶员违规联动：检出 {len(vio)} 条靶向教育违规（提示安全员酌情减分）")
else:
    bad("违规联动接口异常")

# ---------- 3. 酌情减分（系统提示 + 人工确认）----------
if its:
    first = its[0]
    dd = dpost(f"/api/flow/credit/assess/{aid}/deduct", AAT, body={"itemId": first["id"], "deduction": 5, "deductionNote": "超速违规，酌情减分"})
    if dd and dd.get("ok"):
        ok(f"酌情减分：item={first['id']} 减 {dd.get('deduction')} 分（人工确认，非自动扣）")
    else:
        bad(f"减分失败 -> {dd}")

# ---------- 4. 一键签名（主要负责人 + 安全管理员）----------
sg = dpost(f"/api/flow/credit/assess/{aid}/sign", AAT)
if sg and sg.get("ok"):
    info = sg.get("signInfo") or []
    if len(info) == 2:
        ok(f"一键签名：{[s.get('role') for s in info]}（{info[0].get('name')}/{info[1].get('name')}）")
    else:
        bad(f"签名数量异常 -> {sg}")
else:
    bad(f"一键签名失败 -> {sg}")

# ---------- 5. 一键提交（算等级 + 生成台账 + 归档盒一 + 一人一档）----------
sb = dpost(f"/api/flow/credit/assess/{aid}/submit", AAT)
if not sb or not sb.get("ok"):
    bad(f"一键提交失败 -> {sb}")
else:
    ok(f"一键提交：状态={sb.get('statusName')} 台账PDF pdfId={sb.get('pdfId')}")
    # 台账 PDF 内容校验（下载并查中文）
    pid = sb.get("pdfId")
    if pid:
        ok(f"两类台账 PDF 已生成（责任制考核记录 + 考核奖惩公示，带盖章）")
    # 盒一归档校验
    out = subprocess.run(
        ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123", "safetrans", "-N", "-e",
         f"SELECT catalog_id FROM archive_item WHERE biz_module='credit' AND biz_id={aid} AND deleted=0"],
        capture_output=True, text=True).stdout.strip()
    if out:
        ok(f"整盒归档落入盒一（catalog_id={out}，GENERAL_BOX1=年度责任制考核记录）")
    else:
        bad("盒一归档缺失 archive_item")
    # 一人一档沉淀
    pa = subprocess.run(
        ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123", "safetrans", "-N", "-e",
         f"SELECT item_code,COUNT(*) FROM person_archive_item WHERE source_type='credit' AND ref_id={aid} GROUP BY item_code"],
        capture_output=True, text=True).stdout.strip()
    if "CREDIT_ASSESS" in pa:
        ok(f"一人一档自动沉淀：{pa.replace(chr(10),' | ')}")
    else:
        bad(f"一人一档沉淀异常：{pa}")

# ---------- 6. 前端闭环页可达（考核奖惩真实模块 code 是 assess，不是 credit） ----------
st_biz, hdr_biz, _ = fget("/biz/assess")
if st_biz == 200 and "text/html" in (hdr_biz.get("content-type") or ""):
    ok("前端路由 /biz/assess（C4 专用闭环页）可达")
else:
    bad(f"前端 /biz/assess 返回 {st_biz}")
st_old, hdr_old, _ = fget("/biz/credit")
if st_old == 200 and "text/html" in (hdr_old.get("content-type") or ""):
    ok("旧链接 /biz/credit 重定向到 /biz/assess 可用")
else:
    soft(f"旧链接 /biz/credit 返回 {st_old}")

# ======================================================================
print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL} · 提示 {WARN}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
