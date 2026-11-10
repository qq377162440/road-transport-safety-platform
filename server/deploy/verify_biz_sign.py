#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C6 自定义文件签署 · 完整流程闭环验收脚本（逐模块第 10 个 / 18）

验收三条线：
  ① 状态机 + 逐条操作：新建任务(draft) → 放置盖章/签名锚点(pending) → 一键签署(signed) → 归档(archived)
  ② 子表 + PDF 归档：锚点 JSON 落库、结果 PDF 中文无乱码且自动盖章、archive_pdf / archive_item 回写档案柜
  ③ 模块间联动：印章引擎(企业章)、签字库(tpl_signature)、档案盒目录(archive_catalog)、
     流程日志(sign_flow_log)；打印手盖/打印手签覆盖线下手写登记

运行：python3 verify_biz_sign.py   （需后端 8080、前端 80 已起）
认证：开发免验证码登录 /api/auth/dev-login
"""
import json
import sys
import subprocess
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
FRONT = "http://localhost"
PDF_OUT = "/tmp/verify_sign_result.pdf"

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


def req(method, path, token=None, body=None, raw=False):
    data = json.dumps(body if body is not None else {}).encode("utf-8") if method in ("POST", "PUT") else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=25)
        b = resp.read()
        return (resp.status, dict(resp.headers), b) if raw else (resp.status, _parse(b.decode("utf-8", "replace")))
    except urllib.error.HTTPError as e:
        b = e.read()
        return (e.code, dict(e.headers), b) if raw else (e.code, _parse(b.decode("utf-8", "replace")))
    except Exception as e:
        return (0, {}, b"") if raw else (0, {"_err": str(e)})


def login(user):
    st, r = req("POST", "/api/auth/dev-login", body={"username": user, "password": "123456"})
    tok = (r.get("data") or {}).get("accessToken") if isinstance(r, dict) else None
    if not tok:
        bad(f"登录失败 {user} -> {r}")
        return None
    ok(f"登录 {user}")
    return tok


def dget(path, token):
    st, r = req("GET", path, token=token)
    return r.get("data") if isinstance(r, dict) else None


def dpost(path, token, body=None):
    st, r = req("POST", path, token=token, body=body or {})
    return r.get("data") if isinstance(r, dict) else None


def dput(path, token, body=None):
    st, r = req("PUT", path, token=token, body=body or {})
    return r.get("data") if isinstance(r, dict) else None


def sql(q):
    return subprocess.run(
        ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123",
         "--default-character-set=utf8mb4", "safetrans", "-N", "-e", q],
        capture_output=True, text=True).stdout.strip()


def fget(path):
    try:
        r = urllib.request.urlopen(FRONT + path, timeout=15)
        return r.status, {k.lower(): v for k, v in r.headers.items()}
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}


# ======================================================================
print("=" * 68)
print("C6 自定义文件签署 · 完整流程闭环验收")
print("=" * 68)

AAT = login("aa_gaozhou")
AT = login("cc_admin")
if not AAT or not AT:
    print("\n认证失败，终止")
    sys.exit(2)

# ---------- 0. 概览 / 选项 ----------
st, h = req("GET", "/health")
ok("后端健康 /health") if st == 200 else bad(f"后端健康 /health -> {st}")
ov = dget("/api/flow/sign/overview", AAT)
if ov and ov.get("company"):
    boxes = ov.get("boxes") or []
    ok(f"概览：企业={ov.get('company')} 可选档案盒={len(boxes)} 任务数={ov.get('stats',{}).get('tasks')}")
    ok("档案盒目录联动 archive_catalog（level=1）") if len(boxes) >= 20 else bad(f"档案盒数异常：{len(boxes)}")
else:
    bad("概览无返回")
opt = dget("/api/flow/sign/options", AAT) or {}
posts = [p.get("post") for p in (opt.get("posts") or [])]
ok(f"签署岗位联动 org_post：{posts[:5]}") if posts else soft("未取到启用岗位（不影响闭环）")

# ---------- 1. 新建签署任务（状态机 draft） ----------
TITLE = "剧毒化学品道路运输通行证（地方版）·闭环验收"
BOX = "GENERAL_BOX1"
cr = dpost("/api/flow/sign/task", AAT, {"title": TITLE, "archiveBox": BOX, "sourcePdf": ""})
if not cr or not cr.get("ok"):
    bad(f"新建签署任务失败 -> {cr}")
    sys.exit(2)
tid_ = cr.get("taskId")
ok(f"① 新建任务 draft：taskId={tid_} 文件名称={TITLE} 归属={BOX}")
det = dget(f"/api/flow/sign/task/{tid_}", AAT) or {}
ok("任务状态 draft / 未归档") if det.get("status") == "draft" and not det.get("archived") else bad(f"初始状态异常 -> {det.get('status')}")

# ---------- 2. 放置盖章/签名锚点（状态机 pending，核心修复点） ----------
anchors = [
    {"type": "seal", "page": 1, "x": 520, "y": 860, "scale": 1.0, "sealType": "company", "required": True},
    {"type": "sign", "page": 1, "x": 110, "y": 920, "scale": 1.0, "post": "主要负责人", "signType": "history", "required": True},
    {"type": "sign", "page": 1, "x": 110, "y": 990, "scale": 1.0, "post": "安全管理员", "signType": "blank", "required": True},
]
an = dput(f"/api/flow/sign/task/{tid_}/anchors", AAT, {"anchors": anchors})
if not an or not an.get("ok"):
    bad(f"放置锚点失败 -> {an}")
    sys.exit(2)
if an.get("anchorCount") == 3 and an.get("sealCount") == 1 and an.get("signCount") == 2:
    ok(f"① 放置锚点 pending：共 {an.get('anchorCount')} 个（盖章 {an.get('sealCount')} / 签名 {an.get('signCount')}）")
else:
    bad(f"锚点数量异常（Java Map.toString 序列化 bug 未修复？）-> {an}")

# 直接读库校验：anchors 必须是合法 JSON，不能是 [{type=seal, ...}] 这种 Java 集合字符串
raw = sql(f"SELECT anchors FROM sign_task WHERE id={tid_}")
try:
    arr = json.loads(raw)
    ok(f"② 锚点 JSON 落库合法：{len(arr)} 项，type={[a.get('type') for a in arr]}")
    if "type=seal" in raw or "={" in raw:
        bad("锚点仍为 Java 集合字符串（非 JSON）")
    else:
        ok("锚点为标准 JSON（已修复 Map/List.toString 序列化问题）")
    if any(a.get("signType") == "blank" for a in arr):
        ok("打印手签锚点已保存（支持线下打印手写登记）")
except Exception as e:
    bad(f"锚点 JSON 解析失败：{e} raw={raw[:120]}")

det2 = dget(f"/api/flow/sign/task/{tid_}", AAT) or {}
ok("任务状态 pending / 锚点数回显 3") if det2.get("status") == "pending" and det2.get("anchorCount") == 3 else bad(f"状态或锚点回显异常 -> {det2.get('status')} / {det2.get('anchorCount')}")

# ---------- 3. 一键签署（状态机 signed，合成 PDF） ----------
sg = dpost(f"/api/flow/sign/task/{tid_}/sign", AAT)
if not sg or not sg.get("ok"):
    bad(f"一键签署失败 -> {sg}")
    sys.exit(2)
pid = sg.get("pdfId")
ok(f"① 一键签署 signed：盖章 {sg.get('sealCount')} 处 / 签名 {sg.get('signCount')} 处 / 打印手写位 {sg.get('handCount')} 处")
ok(f"② 结果 PDF 已生成 pdfId={pid} 对象={sg.get('resultPdf')}")

# PDF 下载 + 中文/盖章校验
st, hdr, pdfbytes = req("GET", f"/api/archive/pdf/{pid}/download", token=AAT, raw=True)
if st == 200 and pdfbytes[:4] == b"%PDF":
    ok(f"② 结果 PDF 可下载（{len(pdfbytes)} 字节）")
    with open(PDF_OUT, "wb") as f:
        f.write(pdfbytes)
    txt = subprocess.run(["pdftotext", PDF_OUT, "-"], capture_output=True, text=True).stdout
    chinese = [w for w in ["自定义文件", "企业公章", "签字", "归档盒", "剧毒化学品"] if w in txt]
    mojibake = [w for w in ["ä¸€", "ã€", "Â", "ï¿½"] if w in txt]
    ok(f"② PDF 中文正常无乱码：命中 {chinese}") if chinese and not mojibake else bad(f"PDF 中文异常：命中={chinese} 乱码={mojibake}")
    if "打印后手写" in txt or "打印手写" in txt or "签字" in txt:
        ok("② 打印手签占位已渲染（线下手写登记可用）")
else:
    bad(f"结果 PDF 下载失败 -> HTTP {st} / {pdfbytes[:40]}")

# archive_pdf 子表回写
ap = sql(f"SELECT COUNT(*),MAX(template_code) FROM archive_pdf WHERE item_id={tid_} AND tenant_id=5001 AND deleted=0 AND template_code='SIGN_RESULT'")
ok(f"② archive_pdf 结果记录回写：{ap}") if ap.split("\t")[0] == "1" else bad(f"archive_pdf 回写异常 -> {ap}")

# ---------- 4. 归档到指定档案盒（状态机 archived） ----------
ar = dpost(f"/api/flow/sign/task/{tid_}/archive", AAT)
if not ar or not ar.get("ok"):
    bad(f"归档失败 -> {ar}")
else:
    ok(f"① 归档 archived：{ar.get('box')} / {ar.get('boxName')} catalogId={ar.get('catalogId')}")

ai = sql(f"SELECT id,catalog_id,title FROM archive_item WHERE biz_module='sign' AND biz_id={tid_} AND deleted=0")
if ai:
    ok(f"② archive_item 回写档案柜：{ai[:90]}")
    cat = sql(f"SELECT id FROM archive_catalog WHERE code='{BOX}' AND level=1")
    ai_cat = ai.split("\t")[1]
    ok(f"③ 档案盒目录联动正确（catalog_id={cat}）") if ai_cat == cat else bad(f"catalog_id 不匹配：{ai_cat} vs {cat}")
else:
    bad("archive_item 未回写（biz_id 可能误写成 itemId）")

# 重复归档幂等：不得产生第二条 archive_item
dpost(f"/api/flow/sign/task/{tid_}/archive", AAT)
cnt = sql(f"SELECT COUNT(*) FROM archive_item WHERE biz_module='sign' AND biz_id={tid_} AND deleted=0")
ok("② 重复归档幂等（仅 1 条 archive_item，已修复 biz_id=itemId 漂移）") if cnt == "1" else bad(f"重复归档产生 {cnt} 条记录")

# ---------- 5. 流程日志 + 状态机完整性 ----------
logs = sql(f"SELECT action,from_status,to_status FROM sign_flow_log WHERE biz_id={tid_} AND deleted=0 ORDER BY id")
actions = [x.split("\t")[0] for x in logs.splitlines() if x]
needed = ["create", "anchors", "sign", "archive"]
ok(f"① 流程日志状态机完整：{' → '.join(actions)}") if all(a in actions for a in needed) else bad(f"流程日志缺失：{actions}")

tasks = dget("/api/flow/sign/task/list", AAT) or []
hit = next((t for t in tasks if str(t.get("id")) == str(tid_)), None)
if hit and hit.get("archived") and hit.get("pdfId") and hit.get("anchorCount") == 3:
    ok(f"① 任务列表回显完整：status={hit.get('status')} archived={hit.get('archived')} anchors={hit.get('anchorCount')}")
else:
    bad(f"任务列表回显异常 -> {hit}")

# ---------- 6. 前端闭环页 ----------
st_b, hdr_b = fget("/biz/sign")
ok("前端路由 /biz/sign（C6 专用闭环页）可达") if st_b == 200 and "text/html" in (hdr_b.get("content-type") or "") else soft(f"前端 /biz/sign 返回 {st_b}")

# ======================================================================
print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL} · 提示 {WARN}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
