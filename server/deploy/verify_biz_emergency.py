#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B15 应急管理 · 完整流程闭环验收脚本（逐模块第 7 个 / 18）

验收三条线：
  ① 状态机 + 逐条操作：预案 草稿→已发布→已归档；演练 未开展→进行中→已完成（三要素齐备）
  ② 子表 + PDF 归档：6 套场景范本 / 预案只读预览(变量取企业账号) / 一键签名 / 整盒大归档(盒七·普货 / 盒八·危货)
  ③ 模块间联动：队伍·签到同源 B1 人员 / 整盒归档复用 B7 档案柜 / 演练签到并入小程序统一签到入口

运行：python3 verify_biz_emergency.py   （需后端 8080、前端 80 已起）
认证：使用开发免验证码登录 /api/auth/dev-login（与前端 AUTO_LOGIN 一致）
"""
import json
import sys
import time
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
    # POST/PUT 恒发送请求体（即使为空对象），避免 @RequestBody 缺失
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


def putp(path, token=None, body=None, raw=False, headers=None):
    return req("PUT", path, token=token, body=body, raw=raw, headers=headers)


def login(user):
    st, r = post("/api/auth/dev-login", body={"username": user, "password": "123456"})
    tok = (r.get("data") or {}).get("accessToken") if isinstance(r, dict) else None
    if not tok:
        bad(f"登录失败 {user} -> {r}")
        return None
    ok(f"登录 {user}（userType={(r.get('data') or {}).get('userType')}）")
    return tok


def dget(path, token=None):
    """GET，返回 data 字段"""
    st, r = get(path, token=token)
    if not isinstance(r, dict):
        return None
    return r.get("data")


def dpost(path, token=None, body=None):
    """POST，返回 data 字段"""
    st, r = post(path, token=token, body=body)
    if not isinstance(r, dict):
        return None
    return r.get("data")


def dput(path, token=None, body=None):
    """PUT，返回 data 字段"""
    st, r = putp(path, token=token, body=body)
    if not isinstance(r, dict):
        return None
    return r.get("data")


# ======================================================================
print("=" * 68)
print("B15 应急管理 · 完整流程闭环验收")
print("=" * 68)

AAT = login("aa_gaozhou")  # 企业（普货，演示）
AT = login("cc_admin")      # 超管（纠错权限）
BT = login("bb_provider")   # 服务商
if not AAT or not AT:
    print("\n认证失败，终止")
    sys.exit(2)

# ---------- 0. 健康 + 概览 ----------
st, h = get("/health")
ok("后端健康 /health") if st == 200 else bad(f"后端健康 /health -> {st}")

ov = dget("/api/flow/emergency/overview", AAT)
if not ov:
    bad("概览 overview 无返回")
else:
    ok(f"概览：企业={ov.get('company')} 危货={ov.get('isDanger')} 预案统计={ov.get('stats')}")
    tpls = ov.get("planTpls") or []
    if len(tpls) == 6:
        ok(f"6 套场景范本齐全：{[t.get('tpl_name') for t in tpls]}")
    else:
        bad(f"场景范本数量异常：{len(tpls)}")
    if ov.get("drillItems") is not None:
        ok(f"演练项目（含关联执行状态）下发：{(ov.get('drillItems') or []) and len(ov.get('drillItems'))} 项")
    else:
        bad("概览缺少 drillItems（前端开展演练依赖此字段）")
    if (ov.get("refs") or []) and len(ov["refs"]) >= 1:
        ok(f"模块间联动引用：{[r.get('name') for r in ov['refs']]}")
    else:
        bad("联动引用为空")

# ---------- 1. 场景范本 API ----------
tpls_api = dget("/api/flow/emergency/plan-tpls", AAT) or []
codes = [t["tpl_code"] for t in tpls_api if t.get("tpl_code")]
if len(codes) == 6:
    ok(f"场景范本 API 返回 {len(codes)} 套：{codes}")
else:
    bad(f"场景范本 API 数量异常：{len(codes)}")

# ---------- 2. 预案：生成 + 只读预览 + 一键签名 + 发布 ----------
planId = ""
gen = dpost("/api/flow/emergency/plan/generate", AAT, body={"planName": "2026年度B15验收应急预案", "sceneCodes": codes})
if not gen or not gen.get("ok"):
    bad(f"生成预案失败 -> {gen}")
else:
    planId = str(gen.get("planId"))
    ok(f"生成预案：planId={planId} 勾选场景数={gen.get('sceneCount')}（综合预案优先组合）")
    prev = dget(f"/api/flow/emergency/plan/{planId}/preview", AAT)
    if not prev:
        bad("预案只读预览无返回")
    else:
        html = prev.get("contentHtml") or ""
        ro = prev.get("readOnly")
        has_var = "{{" in html
        filled = (ov.get("company") or "") in (html or "")
        if ro is True and not has_var and filled:
            ok("预案只读预览：readOnly=true，变量已填充企业账号数据（无未替换占位符）")
        else:
            bad(f"预案只读预览校验失败：readOnly={ro} 残留占位符={has_var} 含企业名={filled}")
        # 企业不手输校验：preview 不含可编辑表单字段（仅只读正文）
        if "<input" not in html and "<textarea" not in html:
            ok("预案正文为只读渲染（无手输表单控件）")
        else:
            soft("预案预览含表单控件，需确认是否只读")
    # 前端「预案详情」抽屉数据源：GET /plan/{id}（曾缺失导致详情抽屉 404 空白）
    det = dget(f"/api/flow/emergency/plan/{planId}", AAT)
    if det and det.get("plan_name"):
        ok(f"预案详情抽屉数据源 /plan/{{id}} 正常：{det.get('plan_name')} 状态={det.get('statusName')} 签名={(det.get('signInfo') or []) and len(det['signInfo'])} 条")
    else:
        bad(f"预案详情抽屉数据源 /plan/{{id}} 缺失或为空 -> {det}")
    # 一键签名
    sg = dpost(f"/api/flow/emergency/plan/{planId}/sign", AAT)
    si = sg.get("signInfo") if isinstance(sg, dict) else None
    if si and len(si) == 2:
        ok(f"一键签名：主要负责人+安全管理员 留痕（{[s.get('role') for s in si]}）")
    else:
        bad(f"一键签名数量异常 -> {sg}")
    # 发布
    pb = dpost(f"/api/flow/emergency/plan/{planId}/publish", AAT)
    if pb and pb.get("status") == "published":
        ok("预案发布：草稿→已发布")
    else:
        bad(f"预案发布失败 -> {pb}")

# ---------- 3. 超管纠错权限（企业 403 / 超管跨租户纠错 OK）----------
me = dget("/api/auth/me", AAT) or {}
tid = str(me.get("tenantId") or "5001")
st, r = putp(f"/api/flow/emergency/plan/{planId}/correct", AAT, body={"status": "published"})
if r.get("code") == 403:
    ok("企业账号调用超管纠错接口被拦截（业务 code=403，仅 provider/admin 可纠错）")
else:
    bad(f"企业账号纠错未被拦截 -> {r}")
st2, r2 = putp(f"/api/flow/emergency/plan/{planId}/correct", BT, body={"planName": "2026年度B15验收应急预案(已纠错)"}, headers={"X-Target-Tenant": tid})
if st2 == 200 and (r2.get("data") or {}).get("ok"):
    ok("服务商/超管跨租户纠错成功：planName 已更新")
else:
    soft(f"超管纠错返回 -> {r2}")

# ---------- 4. 演练：自动生成计划 + 项目三要素完成 ----------
dp = dpost("/api/flow/emergency/drill-plan/generate", AAT, body={"year": 2026})
if not dp or not dp.get("ok"):
    bad(f"生成演练计划失败 -> {dp}")
else:
    ok(f"系统自动生成演练计划：year={dp.get('year')} 含 {dp.get('itemCount')} 个演练项目（含方案+评估表）")
    items = dget("/api/flow/emergency/drill-item/list", AAT) or []
    todo = [it for it in items if it.get("status") == "none"]
    if not todo:
        bad("无待开展演练项目")
    else:
        first = todo[0]
        stt = dpost(f"/api/flow/emergency/drill/{first['id']}/start", AAT)
        drillId = str(stt.get("drillId")) if isinstance(stt, dict) else ""
        if drillId and (stt or {}).get("status") == "executing":
            ok(f"开展演练（项目→执行）：drillId={drillId} 状态=进行中")
        else:
            bad(f"开展演练失败 -> {stt}")
        # 照片
        ph = dpost(f"/api/flow/emergency/drill/{drillId}/photo", AAT, body={"photoUrl": "http://minio.test/drill1.jpg"})
        ok("上传演练照片（要素1）") if ph and ph.get("ok") else bad(f"上传照片失败 -> {ph}")
        # 评估
        ev = dpost(f"/api/flow/emergency/drill/{drillId}/eval", AAT, body={"evalResult": "演练过程顺利，应急队伍响应及时；存在问题：部分人员对应急器材位置不熟悉。改进：加强日常培训。"})
        ok("填写演练评估表（要素3）") if ev and ev.get("ok") else bad(f"填写评估失败 -> {ev}")
        # 签名：走小程序统一签到入口（drill 类型）
        opts = dget("/api/flow/emergency/options", AAT) or {}
        persons = opts.get("persons") or []
        pid = str(persons[0]["id"]) if persons else "1"
        # 聚合待签任务应包含该演练
        tasks = dget(f"/api/mini/checkin/tasks?personId={pid}", AAT) or []
        drill_task = [t for t in tasks if t.get("bizModule") == "drill" and str(t.get("bizId")) == drillId]
        if drill_task:
            ok("小程序统一签到聚合到本演练待签任务（会议/演练同一入口）")
        else:
            soft(f"统一签到未聚合到本演练（tasks={len(tasks)}），仍走直接签名校验")
        # 统一签到签名（要素2）
        sgn = dpost("/api/mini/checkin/sign", AAT, body={"bizModule": "drill", "bizId": drillId, "personId": pid, "signImage": "data:image/png;base64,MINI_SIGN"})
        if sgn and sgn.get("signed"):
            ok("演练签到经小程序统一入口完成（要素2）")
        else:
            bad(f"统一签到失败 -> {sgn}")
        # 完成（三要素齐备判定）
        cm = dpost(f"/api/flow/emergency/drill/{drillId}/complete", AAT)
        if cm and cm.get("ok") and cm.get("status") == "completed":
            comp = cm.get("completeness") or {}
            ok(f"演练完成：三要素齐备 photo={comp.get('photo')} sign={comp.get('sign')} eval={comp.get('eval')}")
        else:
            bad(f"演练完成失败（三要素不齐？）-> {cm}")

# ---------- 5. 应急资源：队伍 + 器材 + 到期预警 ----------
from datetime import date, timedelta
tm = dpost("/api/flow/emergency/team", AAT, body={"teamName": "B15验收应急抢险队", "teamRole": "现场处置"})
teamId = str(tm.get("teamId")) if isinstance(tm, dict) else ""
if teamId:
    ok(f"维护应急队伍：teamId={teamId}")
    mb = dpost(f"/api/flow/emergency/team/{teamId}/member", AAT, body={"userId": int(pid) if pid.isdigit() else pid, "duty": "队长"})
    ok("队伍添加成员（同源 B1 人员）") if mb and mb.get("ok") else soft(f"添加成员 -> {mb}")
else:
    bad("维护应急队伍失败")

eq = dpost("/api/flow/emergency/equipment", AAT, body={"equipName": "干粉灭火器", "equipType": "消防", "quantity": 20, "unit": "具", "validUntil": "2099-12-31", "location": "办公楼一层"})
eqId = str(eq.get("equipId")) if isinstance(eq, dict) else ""
ok("登记应急器材") if eqId else bad("登记器材失败")
# 临期器材预警：有效期 10 天后（<1 个月），应被标记为临期
near_date = (date.today() + timedelta(days=10)).isoformat()
dpost("/api/flow/emergency/equipment", AAT, body={"equipName": "临期演练担架", "equipType": "救援", "quantity": 2, "unit": "副", "validUntil": near_date, "location": "仓库"})
eqlist = dget("/api/flow/emergency/equipment/list", AAT) or []
expiring = [e for e in eqlist if e.get("expiring")]
if expiring:
    ok(f"器材到期预警生效：检出 {len(expiring)} 件临期（如 {expiring[0].get('equip_name')} 有效期 {expiring[0].get('valid_until')}）")
else:
    bad("器材到期预警未生效（应检出 10 天内到期器材）")

# ---------- 6. 危货专属（仅危货企业可维护，普货应被拦截）----------
dr = dpost("/api/flow/emergency/duty-rule", AAT, body={"ruleName": "应急值班制度"})
# 普货企业返回 ok:false + 仅危货提示
if isinstance(dr, dict) and dr.get("ok") is False and "危货" in (dr.get("msg") or ""):
    ok("危货专属接口对普货企业正确拦截（提示仅危货企业）")
else:
    soft(f"危货专属拦截提示未达预期 -> {dr}（普货企业下属正常）")

# ---------- 7. 一键整盒大归档（盒七·普货 / 盒八·危货）----------
ar = dpost("/api/flow/emergency/archive", AAT)
if not ar or not ar.get("ok"):
    bad(f"整盒归档失败 -> {ar}")
else:
    box = ar.get("box")
    expected = "DANGER_BOX8" if ov.get("isDanger") else "GENERAL_BOX7"
    exp_catalog = "4085" if ov.get("isDanger") else "4036"
    if box == expected:
        ok(f"整盒归档落入正确档案盒：{box}（{'危货·八' if ov.get('isDanger') else '普货·七'} · 应急管理），PDF {ar.get('size')} 字节")
    else:
        bad(f"归档盒错误：期望 {expected} 实际 {box}")
    if str(ar.get("catalogId")) == exp_catalog:
        ok(f"归档目录映射正确：catalogId={ar.get('catalogId')}（与 V17 播种一致）")
    else:
        bad(f"归档目录映射异常：catalogId={ar.get('catalogId')} 期望 {exp_catalog}")
    # 归档后预案应标记 archived
    plist = dget("/api/flow/emergency/plan/list", AAT) or []
    archived = [p for p in plist if p.get("status") == "archived"]
    if archived:
        ok(f"整盒归档后预案状态已流转为已归档（{len(archived)} 份）")
    else:
        bad("整盒归档后预案未标记 archived")
    # 归档成品在 overview.archives
    ov2 = dget("/api/flow/emergency/overview", AAT) or {}
    archives = ov2.get("archives") or []
    if archives:
        ok(f"归档成品回写档案柜：{(archives[0] or {}).get('title')}")
    else:
        bad("归档成品未在 overview.archives 出现")

# ---------- 8. 联动引用 / 小程序预留 ----------
refs = dget("/api/flow/emergency/references", AAT) or []
ok(f"联动引用接口：{[r.get('name') for r in refs]}") if refs else bad("联动引用为空")
expg = dget("/api/flow/emergency/expiring", AAT) or {}
ok(f"待归档预警聚合：total={expg.get('total')}") if isinstance(expg, dict) else bad("待归档预警异常")

# ---------- 9. 小程序统一签到（聚合 + 路由可达性）----------
def fget(path):
    """前端 SPA（端口 80）路由回退探测"""
    try:
        r = urllib.request.urlopen(FRONT + path, timeout=15)
        hdrs = {k.lower(): v for k, v in r.headers.items()}
        return r.status, hdrs, r.read()
    except urllib.error.HTTPError as e:
        hdrs = {k.lower(): v for k, v in e.headers.items()}
        return e.code, hdrs, e.read()


st_home, hdr_home, _ = fget("/m/checkin")
if st_home == 200 and "text/html" in (hdr_home.get("content-type") or ""):
    ok("前端路由 /m/checkin（统一签到页）可达，SPA 回退正常")
else:
    soft(f"前端 /m/checkin 返回 {st_home}")
st_biz, hdr_biz, _ = fget("/biz/emergency")
if st_biz == 200 and "text/html" in (hdr_biz.get("content-type") or ""):
    ok("前端路由 /biz/emergency（B15 专用闭环页）可达")
else:
    soft(f"前端 /biz/emergency 返回 {st_biz}")

# ---------- 10. 首页统一签到角标（小程序聚合）----------
home = dget(f"/api/mini/home?personId={pid}&role=worker", AAT) or {}
badges = home.get("badges") or {}
if "checkin" in badges:
    ok(f"小程序首页统一签到角标下发：checkin={badges['checkin']}")
else:
    soft("首页未下发 checkin 角标")

# ======================================================================
print("=" * 68)
print(f"结果：通过 {PASS} · 失败 {FAIL} · 提示 {WARN}")
print("=" * 68)
sys.exit(1 if FAIL else 0)
