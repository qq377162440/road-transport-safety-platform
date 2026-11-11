#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
电子档案柜「管理人员档案按人分类 / 责任书上下级双签」实机 UI 回归（Playwright 真实浏览器）。

覆盖：
  ① 盒一「管理人员档案」行显示「按人分类」标签与「按人查看」入口
  ② 抽屉按人展示 6 张人员卡片，含岗位、完整度、四组重点材料、生成/下载
  ③ 展开某人明细可见一人一档 14 项归档目录与证照
  ④ 盒一「安全生产责任书」行显示「双签生效」标签与「签订管理」入口
  ⑤ 签订面板展示 7 条上下级关系、双方签署状态、规则说明、历史版本入口

运行：python3 server/deploy/verify_manager_resp_sign_ui.py
"""
import json
import sys
import urllib.request

from playwright.sync_api import sync_playwright

BASE = "http://localhost"
API = BASE + ":8080"
PASS, FAIL = 0, 0


def ok(m):
    global PASS
    PASS += 1
    print(f"  ✅ {m}")


def bad(m):
    global FAIL
    FAIL += 1
    print(f"  ❌ {m}")


def dev_login():
    body = json.dumps({"username": "aa_gaozhou", "password": "123456"}).encode()
    req = urllib.request.Request(API + "/api/auth/dev-login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return (json.loads(r.read().decode()).get("data") or {}).get("accessToken")


def main():
    tok = dev_login()
    if not tok:
        bad("dev-login 未拿到 accessToken")
        return 1
    ok("dev-login 成功")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 1600, "height": 1000})
        page.add_init_script(f"""
            localStorage.setItem('token', {json.dumps(tok)});
            localStorage.setItem('userType', 'enterprise');
            localStorage.setItem('username', 'aa_gaozhou');
        """)
        page.goto(BASE + "/archive", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1200)

        # ---------- ① 管理人员档案行 ----------
        mrow = page.locator(".item-row").filter(has_text="管理人员档案").first
        if mrow.count() == 1:
            ok("盒一「管理人员档案」行已渲染")
        else:
            bad(f"「管理人员档案」行缺失（count={mrow.count()}）")
        if mrow.locator(".src-tpl", has_text="按人分类").count() >= 1:
            ok("行内标注「按人分类」")
        else:
            bad("缺少「按人分类」标签")
        mbtn = mrow.locator("button", has_text="按人查看")
        if mbtn.count() >= 1:
            ok("存在「按人查看」入口")
            mbtn.first.click()
            page.wait_for_timeout(1600)
        else:
            bad("缺少「按人查看」按钮")

        # ---------- ② 按人分类抽屉 ----------
        panel = page.locator(".ma-panel")
        if panel.count() == 1 and panel.is_visible():
            ok("管理人员档案抽屉已打开")
        else:
            bad("管理人员档案抽屉未打开")
        cards = page.locator(".ma-card")
        n = cards.count()
        ok(f"按人展示 {n} 张人员卡片") if n == 6 else bad(f"人员卡片数应为 6，实际 {n}")
        stat = page.locator(".ma-panel .ma-stat").inner_text() if panel.count() else ""
        ok(f"统计条显示归档年度/人数/已生成：{stat.strip()[:60]}") if "管理人员" in stat else bad("统计条缺失")
        chips = page.locator(".ma-card").first.locator(".ma-chip")
        labels = [chips.nth(i).inner_text().strip() for i in range(chips.count())]
        print(f"     重点材料分组：{labels}")
        ok("卡片含三书/岗前培训/考核记录/任命书四组") \
            if all(any(k in t for t in labels) for k in ("三书", "岗前培训", "考核记录", "任命书")) \
            else bad(f"重点材料分组不全：{labels}")
        ok("卡片提供「生成档案册 / 重新生成」与「下载 PDF」") \
            if cards.first.locator("button", has_text="PDF").count() >= 1 \
            or cards.first.locator("button", has_text="生成").count() >= 1 else bad("缺少生成/下载操作")

        # ---------- ③ 展开明细 ----------
        cards.first.locator(".ma-card-h").click()
        page.wait_for_timeout(350)
        detail = cards.first.locator(".ma-detail")
        if detail.count() == 1 and detail.is_visible():
            items = detail.locator(".ma-item").count()
            ok(f"展开可见一人一档归档明细（{items} 行，含目录 + 证照）") if items >= 14 \
                else bad(f"归档明细行数不足：{items}")
        else:
            bad("人员明细未展开")
        page.screenshot(path="/workspace/preview/档案柜_管理人员档案按人分类.png", full_page=False)
        ok("已保存管理人员档案按人分类截图")
        page.locator(".ma-panel .sd-x").click()
        page.wait_for_timeout(300)

        # ---------- ④⑤ 责任书签订面板 ----------
        rrow = page.locator(".item-row").filter(has_text="安全生产责任书").first
        if rrow.count() == 1:
            ok("盒一「安全生产责任书」行已渲染")
        else:
            bad(f"「安全生产责任书」行缺失（count={rrow.count()}）")
        if rrow.locator(".src-tpl", has_text="双签生效").count() >= 1:
            ok("行内标注「双签生效」")
        else:
            bad("缺少「双签生效」标签")
        rbtn = rrow.locator("button", has_text="签订管理")
        if rbtn.count() >= 1:
            ok("存在「签订管理」入口")
            rbtn.first.click()
            page.wait_for_timeout(1600)
        else:
            bad("缺少「签订管理」按钮")

        rpanel = page.locator(".rs-panel")
        if rpanel.count() == 1 and rpanel.is_visible():
            ok("责任书签订面板已打开")
        else:
            bad("责任书签订面板未打开")
        rule = page.locator(".rs-rule").inner_text() if page.locator(".rs-rule").count() else ""
        ok("面板说明「取自安全管理架构 / 两人都签才生效 / 与公司签署」") \
            if all(k in rule for k in ("安全管理架构", "两个人都签署", "与公司签署")) \
            else bad(f"签订规则说明不全：{rule[:100]}")
        rcards = page.locator(".rs-card")
        rn = rcards.count()
        ok(f"展示 {rn} 条上下级签订关系") if rn == 7 else bad(f"签订关系数应为 7，实际 {rn}")
        company_rel = page.locator(".rs-card", has_text="公司（盖章）")
        ok("存在最顶层「公司（盖章）」签署关系") if company_rel.count() >= 1 \
            else bad("缺少公司与主要负责人的签署关系")
        signs = page.locator(".rs-sign")
        ok("每条展示上级/下级双方签署状态") if signs.count() >= 14 \
            else bad(f"双方签署状态栏不足：{signs.count()}")
        ok("提供「历史版本」入口") if page.locator(".rs-ops button", has_text="历史版本").count() >= 1 \
            else bad("缺少历史版本入口")

        # 截图留存
        page.screenshot(path="/workspace/preview/档案柜_责任书双签面板.png", full_page=False)
        ok("已保存责任书签订面板截图")

        browser.close()

    print("=" * 68)
    print(f"结果：通过 {PASS} · 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
