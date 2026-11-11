#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
盒十一「从业人员一人一档 + 一车一档」UI 实机冒烟测试（Playwright）。

覆盖：
  ① 档案盒目录出现「盒十一」并可点击进入
  ② 盒十一走专属面板（.crew-panel），含「从业人员一人一档 / 一车一档」两个标签
  ③ 从业人员区列出人员、一车一档区列出车辆，且各有归档项文件
  ④ 展开某从业人员，点击文件名 → 弹出文件预览（真实 PDF 已加载到 iframe）
  ⑤ 切换到一车一档，展开车辆并点击文件名同样可预览

运行：python3 server/deploy/verify_box11_crew.py
"""
import json
import sys
import urllib.request
import urllib.error
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


def dev_login(username="aa_gaozhou", password="123456"):
    body = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(
        API + "/api/auth/dev-login", data=body, method="POST",
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return (json.loads(r.read().decode()).get("data") or {}).get("accessToken")


def click_box(page, label):
    """点击左侧档案盒目录中指定名称的盒"""
    page.locator("span.bx-name", has_text=label).first.click()


def main():
    tok = dev_login()
    if not tok:
        bad("dev-login 未拿到 accessToken"); return 1
    ok("dev-login 成功")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.add_init_script(f"""
            localStorage.setItem('token', {json.dumps(tok)});
            localStorage.setItem('userType', 'enterprise');
            localStorage.setItem('username', 'aa_gaozhou');
        """)
        page.goto(BASE + "/archive", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1200)

        # ① 目录出现盒十一
        box11 = page.locator("span.bx-name", has_text="盒十一")
        if box11.count() >= 1:
            ok("档案盒目录存在「盒十一」")
        else:
            bad("档案盒目录缺少「盒十一」"); return 1
        click_box(page, "盒十一")
        page.wait_for_timeout(1000)

        # ② 专属面板
        if page.locator(".crew-panel").count() == 1:
            ok("盒十一渲染专属浏览面板（.crew-panel）")
        else:
            bad("盒十一未渲染 .crew-panel"); return 1
        tabs = page.locator(".crew-tab")
        if tabs.count() == 2:
            ok("含「从业人员一人一档 / 一车一档」两个标签")
        else:
            bad(f"crew-tab 数量异常：{tabs.count()}")

        tab_texts = [tabs.nth(i).inner_text().strip() for i in range(tabs.count())]
        print(f"     标签：{tab_texts}")

        # ③ 从业人员区
        emp_cards = page.locator(".crew-body .crew-card")
        ne = emp_cards.count()
        if ne >= 1:
            ok(f"从业人员一人一档列出 {ne} 名人员")
        else:
            bad("从业人员区未列出人员")

        # ④ 展开第一名从业人员，点击文件名预览
        emp_cards.first.locator(".crew-card-h").click()
        page.wait_for_timeout(600)
        files = emp_cards.first.locator(".crew-file.clickable")
        nf = files.count()
        if nf >= 1:
            ok(f"从业人员展开后可见 {nf} 个可点击文件名")
        else:
            bad("从业人员归档项无可点击文件名"); return 1

        # 记录预览前的弹窗数
        files.first.click()
        page.wait_for_timeout(1500)
        modal = page.locator(".pdf-mask", has_text="文件预览")
        if modal.count() >= 1:
            ok("点击文件名弹出文件预览窗")
        else:
            bad("点击文件名未弹出预览窗"); return 1
        # 验证 iframe 已加载真实 PDF（src 为 blob:）
        iframe = modal.locator("iframe.pdf-embed")
        if iframe.count() == 1:
            src = iframe.first.get_attribute("src") or ""
            if src.startswith("blob:"):
                ok(f"预览窗已内嵌真实 PDF（{src[:24]}…）")
            else:
                bad(f"预览 iframe src 异常：{src[:40]}")
        else:
            bad("预览窗未内嵌 PDF iframe")
        # 关闭预览
        modal.locator("button.x").first.click()
        page.wait_for_timeout(400)

        # ⑤ 切到一车一档
        tabs.nth(1).click()
        page.wait_for_timeout(800)
        veh_cards = page.locator(".crew-body .crew-card")
        nv = veh_cards.count()
        if nv >= 1:
            ok(f"一车一档列出 {nv} 辆车")
        else:
            bad("一车一档区未列出车辆"); return 1
        veh_cards.first.locator(".crew-card-h").click()
        page.wait_for_timeout(600)
        vfiles = veh_cards.first.locator(".crew-file.clickable")
        if vfiles.count() >= 1:
            ok(f"车辆展开后可见 {vfiles.count()} 个可点击文件名")
        else:
            bad("车辆归档项无可点击文件名"); return 1
        vfiles.first.click()
        page.wait_for_timeout(1500)
        vmodal = page.locator(".pdf-mask", has_text="文件预览")
        if vmodal.count() >= 1 and vmodal.locator("iframe.pdf-embed").count() == 1:
            ok("车辆文件名点击同样可预览真实 PDF")
        else:
            bad("车辆文件预览未正常加载")

    print(f"\n盒十一冒烟测试：通过 {PASS} · 失败 {FAIL}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
