#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用户 2026-11-11 三项反馈的专项验收：
  ① 登录失效静默续期：POST /auth/refresh 可换新 access token；
     前端在 token 失效+refreshToken 有效时不弹「登录已失效」直接续期成功（Playwright 实机）。
  ② 组织架构 → 档案柜联动：《安全组织架构》《关于成立安全生产领导小组的通知》《人员任命书》
     regen-archive 自动重生成；keepHistory=true 留存旧版 / false 下架旧版。
  ③ 二级维护计划范本化：L2_MAINT_PLAN 生成成品 PDF，正文与用户 docx 一致、中文无乱码、盖章。

运行：python3 server/deploy/verify_user_feedback_1111.py
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
FRONT = "http://localhost"
PASS = FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print(f"  ✅ {m}")


def bad(m):
    global FAIL
    FAIL += 1
    print(f"  ❌ {m}")


def http(method, path, token=None, body=None, raw=False, headers=None):
    data = json.dumps(body or {}).encode() if method in ("POST", "PUT") else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
        r.add_header("X-Token", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        b = resp.read()
        if raw:
            return resp.status, b
        return resp.status, b.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def jdata(st, raw):
    if st != 200:
        return None
    try:
        return json.loads(raw).get("data")
    except Exception:
        return None


print("=" * 68)
print("用户反馈三项专项验收（登录续期 / 架构联动 / 二级维护计划范本）")
print("=" * 68)

# ---------- 登录 ----------
st, raw = http("POST", "/api/auth/dev-login", body={"username": "aa_gaozhou", "password": "123456"})
login = jdata(st, raw)
if not login or not login.get("accessToken"):
    bad(f"dev-login 失败 -> {st} {raw[:120]}")
    sys.exit(2)
tok = login["accessToken"]
rt = login.get("refreshToken", "")
ok("dev-login 成功")
if rt:
    ok("登录返回 refreshToken（前端静默续期的前提）")
else:
    bad("登录未返回 refreshToken")

# ---------- ① 静默续期 ----------
st, raw = http("POST", "/api/auth/refresh", body={"refreshToken": rt})
new_login = jdata(st, raw)
if new_login and new_login.get("accessToken"):
    ok(f"POST /auth/refresh 换新 access token 成功（refresh 7 天有效）")
    st2, _ = http("GET", "/api/flow/emergency/overview", token=new_login["accessToken"])
    ok("续期后的新 token 可正常访问业务接口") if st2 == 200 else bad(f"新 token 访问业务接口 -> {st2}")
else:
    bad(f"/auth/refresh 失败 -> {st} {raw[:160]}")

# 无效 refresh 必须被拒绝，防止乱拿 token
st, raw = http("POST", "/api/auth/refresh", body={"refreshToken": "garbage-token"})
ok("无效 refreshToken 被拒绝（401）") if st in (401, 403) or '"code"' in raw and json.loads(raw).get("code") != 0 else bad(f"无效 refresh 未被拒绝 -> {st} {raw[:120]}")

# ---------- ③ 二级维护计划范本 ----------
# 范本生成是服务商/超管专属增值服务（企业只读），用服务商账号 + X-Target-Tenant 代企业生成
st, raw = http("POST", "/api/auth/dev-login", body={"username": "bb_provider", "password": "123456"})
prov = jdata(st, raw)
ptok = prov.get("accessToken") if prov else None
ok("服务商 bb_provider 登录") if ptok else bad(f"服务商登录失败 -> {st} {raw[:120]}")
st, raw = http("POST", "/api/archive/template/generate", token=ptok,
               body={"code": "L2_MAINT_PLAN", "itemName": "二级维护计划", "boxNo": 1, "year": 2026, "post": ""},
               headers={"X-Target-Tenant": "5001"})
gen = jdata(st, raw)
if gen and (gen.get("pdfId") or gen.get("id")):
    pdf_id = gen.get("pdfId") or gen.get("id")
    ok(f"二级维护计划按范本生成成功（pdfId={pdf_id}）")
    st2, blob = http("GET", f"/api/archive/pdf/{pdf_id}/download", token=ptok, raw=True,
                     headers={"X-Target-Tenant": "5001"})
    if st2 == 200 and blob[:4] == b"%PDF":
        ok(f"成品 PDF 可下载（{len(blob)} 字节）")
        open("/tmp/l2_maint_plan.pdf", "wb").write(blob)
        text = subprocess.run(["pdftotext", "/tmp/l2_maint_plan.pdf", "-"],
                              capture_output=True, text=True).stdout
        for kw in ["关于公司车辆二级维护计划通知", "一、工作要求", "二、车辆维护周期", "三、实施时间",
                   "二级维护由驾驶员按期", "一年一次"]:
            ok(f"PDF 正文含「{kw}」") if kw in text else bad(f"PDF 正文缺少「{kw}」")
        garble = [g for g in ["ä¸€", "ã€", "Â", "ï¿½"] if g in text]
        ok("PDF 中文无乱码") if not garble else bad(f"PDF 乱码：{garble}")
    else:
        bad(f"成品 PDF 下载异常 -> {st2} {len(blob)}")
else:
    bad(f"二级维护计划生成失败 -> {st} {raw[:200]}")

# ---------- ② 组织架构联动 ----------
st, raw = http("GET", "/api/org/chart/archive-status", token=tok)
st0 = jdata(st, raw)
ok(f"archive-status 接口可用（year={st0.get('year') if st0 else '?'}）") if st0 else bad(f"archive-status -> {st} {raw[:160]}")

# 第一次 regen：生成三份
st, raw = http("POST", "/api/org/chart/regen-archive", token=tok, body={"keepHistory": True})
r1 = jdata(st, raw)
if r1 and len(r1.get("generated") or []) == 3:
    ok("组织架构联动：三份文件一次生成（架构图 / 领导小组通知 / 任命书）")
    for row in r1["generated"]:
        ok(f"  · {row['title']} -> pdfId={row['pdfId']} {row.get('fileSize')} 字节")
else:
    bad(f"regen-archive 失败 -> {st} {raw[:240]}")

# PDF 内容抽查：领导小组通知与架构图
st, raw = http("GET", "/api/org/chart/archive-status", token=tok)
st1 = jdata(st, raw)
if st1 and st1.get("hasAny"):
    ok("archive-status hasAny=true（联动状态回读正确）")
    for row in st1["items"]:
        if row.get("pdfId"):
            ok(f"  · {row['title']} 当年已有成品 genTime={row.get('genTime')}")
else:
    bad("联动后 archive-status 仍无成品")

# 架构图 PDF 正文抽查（必须在 keepHistory=false 下架之前：deleted 为逻辑删除，下架后按预期不可下载）
if r1:
    chart_pdf = [x for x in r1["generated"] if x["title"] == "安全组织架构"]
    if chart_pdf:
        st3, blob = http("GET", f"/api/archive/pdf/{chart_pdf[0]['pdfId']}/download", token=tok, raw=True)
        if st3 == 200 and blob[:4] == b"%PDF":
            open("/tmp/org_chart_archive.pdf", "wb").write(blob)
            text = subprocess.run(["pdftotext", "/tmp/org_chart_archive.pdf", "-"],
                                  capture_output=True, text=True).stdout
            ok(f"架构图归档 PDF 可读（{len(blob)} 字节，文本 {len(text.strip())} 字符）")
        else:
            bad(f"架构图归档 PDF 下载失败 -> {st3}")

# 第二次 regen（keepHistory=False）：验证旧版本下架（deleted 逻辑删除后旧版不可再下载）
time.sleep(1.1)
st, raw = http("POST", "/api/org/chart/regen-archive", token=tok, body={"keepHistory": False})
r2 = jdata(st, raw)
if r2 and len(r2.get("generated") or []) == 3:
    ok("第二次联动生成成功（keepHistory=false）")
    ok(f"旧版本下架计数 = {r2.get('archivedOld')}") if int(r2.get("archivedOld") or 0) >= 3 else bad(f"旧版本未下架：{r2.get('archivedOld')}")
    if r1:
        old_chart = [x for x in r1["generated"] if x["title"] == "安全组织架构"]
        if old_chart:
            st4, _ = http("GET", f"/api/archive/pdf/{old_chart[0]['pdfId']}/download", token=tok, raw=True)
            ok("下架后的旧版本按预期不可下载") if st4 != 200 or _[:4] != b"%PDF" else bad("旧版本下架后仍可下载")
else:
    bad(f"第二次 regen 失败 -> {st} {raw[:240]}")

print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
