#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用户 2026-11-11 两项新反馈的专项验收：
  ① 档案柜「管理人员档案」= 人员基础信息里管理人员的一人一档 PDF 集合，按人分类，
     册内含岗前培训 / 考核记录 / 三书 / 任命书 / 证照；含样例数据。
  ② 安全生产责任书上下级双签：上下级关系取自安全管理架构（org_post.parent_id 汇报线），
     两人都签才生效；最顶层主要负责人与公司签署（公司盖章 + 本人签字）。
  ③ 组织架构变化 → 受影响责任书自动作废并生成新版本待签，历史 PDF 留存。

运行：python3 server/deploy/verify_manager_resp_sign.py
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
YEAR = time.localtime().tm_year
PASS = FAIL = 0
MYSQL = ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123",
         "--default-character-set=utf8mb4", "-N", "-e"]


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
        resp = urllib.request.urlopen(r, timeout=60)
        b = resp.read()
        return resp.status, (b if raw else b.decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return e.code, (e.read() if raw else e.read().decode("utf-8", "replace"))
    except Exception as e:
        return 0, (b"" if raw else str(e))


def jdata(st, raw):
    if st != 200:
        return None
    try:
        return json.loads(raw).get("data")
    except Exception:
        return None


def pdf_text(blob, tag):
    p = f"/tmp/{tag}.pdf"
    open(p, "wb").write(blob)
    raw = subprocess.run(["pdftotext", p, "-"], capture_output=True, text=True).stdout
    # PDF 排版会把长句按页宽折行（例如「与公司签订本\n责任书」），关键词断言需先拼回整句
    return raw.replace("\n", "").replace("\r", "")


def sql(q):
    r = subprocess.run(MYSQL + ["USE safetrans; " + q], capture_output=True, text=True)
    return r.stdout.strip()


print("=" * 72)
print(f"管理人员档案按人分类 + 责任书上下级双签 · 专项验收（年度 {YEAR}）")
print("=" * 72)

# ---------- 登录 ----------
st, raw = http("POST", "/api/auth/dev-login", body={"username": "aa_gaozhou", "password": "123456"})
login = jdata(st, raw)
if not login or not login.get("accessToken"):
    bad(f"企业账号登录失败 -> {st} {raw[:160]}")
    sys.exit(2)
tok = login["accessToken"]
ok("企业账号 aa_gaozhou 登录成功")

# ==================================================================
# ① 管理人员档案：按人分类的一人一档 PDF 集合
# ==================================================================
print("\n【① 管理人员档案 · 一人一档 PDF 集合】")

st, raw = http("GET", f"/api/archive/manager/overview?year={YEAR}", token=tok)
ov = jdata(st, raw)
if not ov:
    bad(f"管理人员档案总览接口失败 -> {st} {raw[:200]}")
else:
    persons = ov.get("persons") or []
    ok(f"总览接口可用：共 {ov.get('total')} 名管理人员，已生成 {ov.get('generated')} 份档案册")
    ok("样例管理人员已补齐（6 人）") if len(persons) == 6 else bad(f"管理人员数量应为 6，实际 {len(persons)}")
    names = {p["name"] for p in persons}
    expected = {"刘洋", "赵敏", "邓佐成", "李文博", "周雅琴", "吴志强"}
    ok("人员来自 person_type=manager 的管理人员（驾驶员陈刚不在内）") \
        if names == expected else bad(f"人员名册不符：{names}")
    codes = set()
    for p in persons:
        codes |= {i["code"] for i in (p.get("items") or [])}
    ok("每人含完整一人一档 13 项 + 年度考核记录（14 项）") \
        if len(codes) == 14 and "CREDIT_ASSESS" in codes else bad(f"归档目录项数={len(codes)}：{sorted(codes)}")
    focus_ok = all(
        all(k in (p.get("focus") or {}) and p["focus"][k]["total"] > 0
            for k in ("三书", "岗前培训", "考核记录", "任命书"))
        for p in persons)
    ok("每人含「三书 / 岗前培训 / 考核记录 / 任命书」四组重点材料") if focus_ok \
        else bad("重点材料分组缺失")
    ok("每人有证照明细（person_doc）") if all((p.get("docs") or []) for p in persons) \
        else bad("部分人员无证照记录")

# 生成全部档案册
st, raw = http("POST", "/api/archive/manager/generate", token=tok, body={"year": YEAR})
gen = jdata(st, raw)
if isinstance(gen, list) and len(gen) == 6:
    ok(f"一次生成 6 份管理人员档案册 PDF：{', '.join(x['name'] for x in gen)}")
else:
    bad(f"全员生成失败 -> {st} {raw[:240]}")

st, raw = http("GET", f"/api/archive/manager/overview?year={YEAR}", token=tok)
ov2 = jdata(st, raw)
ok("重新查询：6 人档案册均已生成") if ov2 and ov2.get("generated") == 6 \
    else bad(f"生成状态未回写：generated={ov2.get('generated') if ov2 else '?'}")

# 按人归档校验（archive_item.person_id + biz_module）
cnt = sql(f"SELECT COUNT(*) FROM archive_item WHERE tenant_id=5001 AND biz_module='manager_archive' "
          f"AND archive_year={YEAR} AND person_id IS NOT NULL AND deleted=0;")
ok(f"按人归档：archive_item.person_id 非空且 biz_module=manager_archive 共 {cnt} 条") \
    if cnt.isdigit() and int(cnt) == 6 else bad(f"按人归档条数异常：{cnt}")
pdfcnt = sql(f"SELECT COUNT(*) FROM archive_pdf WHERE tenant_id=5001 AND template_code='MANAGER_ARCHIVE' "
             f"AND archive_year={YEAR} AND deleted=0;")
ok(f"档案册 PDF 已入库（template_code=MANAGER_ARCHIVE，{pdfcnt} 份）") \
    if pdfcnt.isdigit() and int(pdfcnt) >= 6 else bad(f"档案册 PDF 入库数异常：{pdfcnt}")

# PDF 内容抽查（取第一人）
if isinstance(gen, list) and gen:
    pid, pname = gen[0]["pdfId"], gen[0]["name"]
    st3, blob = http("GET", f"/api/archive/pdf/{pid}/download", token=tok, raw=True)
    if st3 == 200 and blob[:4] == b"%PDF":
        ok(f"档案册 PDF 可下载（{pname}，{len(blob)} 字节）")
        text = pdf_text(blob, "manager_archive")
        for kw in ["管理人员档案", "一人一档", "一、人员基本信息", "二、一人一档归档目录",
                   "三、安全生产三书", "四、岗前三级安全教育培训",
                   "五、年度安全生产责任制考核记录", "六、人员任命书",
                   "七、证照与资格证明明细", pname]:
            ok(f"PDF 含「{kw}」") if kw in text else bad(f"PDF 缺少「{kw}」")
        garble = [g for g in ["ä¸€", "ã€", "Â", "ï¿½"] if g in text]
        ok("档案册 PDF 中文无乱码") if not garble else bad(f"PDF 乱码：{garble}")
    else:
        bad(f"档案册 PDF 下载失败 -> {st3} {len(blob) if isinstance(blob, bytes) else blob}")

# ==================================================================
# ② 安全生产责任书：上下级双签生效
# ==================================================================
print("\n【② 安全生产责任书 · 上下级双签】")

st, raw = http("GET", f"/api/archive/resp-sign/overview?year={YEAR}", token=tok)
rs = jdata(st, raw)
if not rs:
    bad(f"责任书签订总览失败 -> {st} {raw[:240]}")
    rows = []
else:
    rows = rs.get("rows") or []
    ok(f"签订总览可用：{rs.get('total')} 条关系（待签 {rs.get('pending')} / "
       f"待生效 {rs.get('partial')} / 已生效 {rs.get('effective')}）")

by_name = {r["subName"]: r for r in rows}
ok("签订关系覆盖架构中 7 个启用岗位的 7 名人员") if len(rows) == 7 \
    else bad(f"签订关系条数应为 7，实际 {len(rows)}")

root = by_name.get("邓佐成")
if root:
    ok("最顶层主要负责人 supType=company（与公司签署）") if root["supType"] == "company" \
        else bad(f"顶层 supType={root['supType']}")
else:
    bad("未找到主要负责人（邓佐成）的签订记录")

# 上下级关系必须来自 org_post.parent_id 汇报线
expected_sup = {
    "赵敏": "邓佐成", "刘洋": "赵敏", "李文博": "邓佐成",
    "周雅琴": "邓佐成", "吴志强": "李文博", "陈刚": "吴志强",
}
rels = []
for sub, sup in expected_sup.items():
    r = by_name.get(sub)
    rels.append((sub, sup, r.get("supName") if r else None, r.get("supType") if r else None))
    ok(f"{sub} 的上级 = {sup}（来自架构汇报线）") \
        if r and r["supType"] == "person" and r["supName"] == sup \
        else bad(f"{sub} 上级错误：{r.get('supName') if r else '无记录'}（应为 {sup}）")

# --- 双签不生效：只签一方仍是 partial，且不生成 PDF ---
target = by_name.get("刘洋")
if target:
    t0 = target
    if t0["status"] in ("pending", "partial") and not (t0["supSigned"] and t0["subSigned"]):
        side = "sub" if not t0["subSigned"] else "sup"
        st, raw = http("POST", "/api/archive/resp-sign/sign", token=tok,
                       body={"id": t0["id"], "side": side, "signName": "刘洋" if side == "sub" else "赵敏"})
        one = jdata(st, raw)
        if one and one["status"] == "partial" and not one.get("pdfId"):
            ok(f"单方签署（{side}）后状态 = partial，未生效、未生成生效 PDF")
        else:
            bad(f"单方签署状态异常 -> {st} {raw[:200]}")
        # 再签另一方 → 生效
        side2 = "sup" if side == "sub" else "sub"
        nm2 = "赵敏" if side2 == "sup" else "刘洋"
        st, raw = http("POST", "/api/archive/resp-sign/sign", token=tok,
                       body={"id": t0["id"], "side": side2, "signName": nm2})
        eff = jdata(st, raw)
        if eff and eff["status"] == "effective" and eff.get("pdfId") and eff.get("effectiveAt"):
            ok("双方都签署后状态 = effective，写入生效时间并生成 PDF")
        else:
            bad(f"双方签署未生效 -> {st} {raw[:240]}")
    else:
        ok(f"刘洋责任书已处于 {t0['status']}（重复运行跳过首次签署）")
        eff = t0

    # 生效件 PDF 内容：上级 + 下级双签、签署关系、无乱码
    pdf_id = (eff or {}).get("pdfId") if isinstance(eff, dict) else None
    if pdf_id:
        st4, blob = http("GET", f"/api/archive/pdf/{pdf_id}/download", token=tok, raw=True)
        if st4 == 200 and blob[:4] == b"%PDF":
            ok(f"生效责任书 PDF 可下载（{len(blob)} 字节）")
            text = pdf_text(blob, "resp_letter_effective")
            for kw in ["安全生产责任书", "赵敏", "刘洋", "已电子签署", "共同签订本责任书"]:
                ok(f"生效 PDF 含「{kw}」") if kw in text else bad(f"生效 PDF 缺少「{kw}」")
            garble = [g for g in ["ä¸€", "ã€", "Â", "ï¿½"] if g in text]
            ok("生效 PDF 中文无乱码") if not garble else bad(f"生效 PDF 乱码：{garble}")
        else:
            bad(f"生效 PDF 下载失败 -> {st4}")
else:
    bad("未找到刘洋（监控员）签订记录")

# --- 顶层：与公司签署，公司盖章 + 本人签字 ---
if root:
    root_pdf_id = None
    if root["status"] in ("pending", "partial"):
        for side, nm in (("sub", "邓佐成"), ("sup", root["supName"])):
            st, raw = http("POST", "/api/archive/resp-sign/sign", token=tok,
                           body={"id": root["id"], "side": side, "signName": nm})
            cur = jdata(st, raw)
            if side == "sub" and cur:
                ok("顶层：本人签署后仍未生效（等公司盖章）") if cur["status"] == "partial" \
                    else bad(f"顶层本人签署后状态异常：{cur['status']}")
            if side == "sup" and cur:
                ok("顶层：公司签署后双方齐，责任书生效") if cur["status"] == "effective" \
                    else bad(f"顶层公司签署后未生效：{cur['status']}")
                root_pdf_id = cur.get("pdfId")
    else:
        ok(f"顶层责任书已处于 {root['status']}（重复运行跳过签署）")
        root_pdf_id = root.get("pdfId")

    if root_pdf_id:
        st5, blob = http("GET", f"/api/archive/pdf/{root_pdf_id}/download", token=tok, raw=True)
        if st5 == 200 and blob[:4] == b"%PDF":
            text = pdf_text(blob, "resp_letter_root")
            ok("顶层生效 PDF 含「与公司签订本责任书」") if "与公司签订本责任书" in text \
                else bad("顶层生效 PDF 未体现与公司签署")
            ok("顶层生效 PDF 含公司盖章栏「责任主体」「盖章」") \
                if "责任主体" in text and "盖章" in text else bad("顶层生效 PDF 缺公司盖章栏")
        else:
            bad(f"顶层生效 PDF 下载失败 -> {st5}")
    else:
        bad("顶层责任书未生成生效 PDF")

# 幂等：关系未变化时同步不应作废/新建
st, raw = http("POST", "/api/org/chart/sync-resp-sign", token=tok, body={"year": YEAR})
sy = jdata(st, raw)
ok(f"架构未变化时同步幂等（voided={sy.get('voided')}, created={sy.get('created')}）") \
    if sy and int(sy.get("voided") or 0) == 0 and int(sy.get("created") or 0) == 0 \
    else bad(f"同步不幂等 -> {st} {raw[:200]}")

# ==================================================================
# ③ 架构变化 → 责任书作废重签
# ==================================================================
print("\n【③ 组织架构变化 → 责任书联动作废重签】")

# 把「资料员」(7005) 的上级从总经理(7001)改为安全员(7002)，周雅琴的上级应从邓佐成变赵敏
st, raw = http("PUT", "/api/org/chart/post/7005", token=tok, body={"parentId": "7002"})
if st == 200:
    ok("修改资料员岗位上级（总经理 → 安全员）")
    st, raw = http("POST", "/api/org/chart/sync-resp-sign", token=tok, body={"year": YEAR})
    ch = jdata(st, raw)
    ok(f"架构变化触发作废重签：voided={ch.get('voided')}, created={ch.get('created')}") \
        if ch and int(ch.get("voided") or 0) >= 1 and int(ch.get("created") or 0) >= 1 \
        else bad(f"架构变化未触发重签 -> {st} {raw[:200]}")
    st, raw = http("GET", f"/api/archive/resp-sign/overview?year={YEAR}", token=tok)
    ov3 = jdata(st, raw)
    new_zy = next((r for r in (ov3.get("rows") or []) if r["subName"] == "周雅琴"), None)
    ok("周雅琴新版本上级已变为赵敏（汇报线实时生效）") \
        if new_zy and new_zy["supName"] == "赵敏" and new_zy["status"] == "pending" \
        else bad(f"新版本关系错误：{new_zy}")
    if new_zy:
        st, raw = http("GET", f"/api/archive/resp-sign/history?subPersonId={new_zy['subPersonId']}&year={YEAR}",
                       token=tok)
        hist = jdata(st, raw)
        ok("历史版本可查（含已作废的旧签署件）") \
            if isinstance(hist, list) and len(hist) >= 2 and any(h["status"] == "void" for h in hist) \
            else bad(f"历史版本缺失：{hist}")
    else:
        bad("周雅琴新版本不存在，无法查历史")
else:
    bad(f"修改岗位上级失败 -> {st} {raw[:200]}")

# 恢复架构，避免影响演示数据
st, raw = http("PUT", "/api/org/chart/post/7005", token=tok, body={"parentId": "7001"})
ok("恢复资料员岗位上级为总经理") if st == 200 else bad(f"恢复上级失败 -> {st} {raw[:160]}")
http("POST", "/api/org/chart/sync-resp-sign", token=tok, body={"year": YEAR})

print("=" * 72)
print(f"结果：通过 {PASS} · 失败 {FAIL}")
sys.exit(0 if FAIL == 0 else 1)
