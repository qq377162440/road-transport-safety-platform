#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
电子档案柜「文件分类 / 岗位切换」实机 UI 回归（Playwright 真实浏览器）。

验收用户反馈：「证件类的，就跟岗位无关的文件为什么还有切换岗位的按钮」

覆盖：
  ① /archive 页面渲染出分类筛选栏与四类标签、行内类别标签
  ② 分类筛选可切换，且「全选本盒」文案随筛选变化
  ③ 「营业执照」成品预览：显示「③ 证件证照」，【不出现】岗位下拉
  ④ 「安全生产责任书」成品预览：显示「① 静态范本」+「按岗位」，【出现】岗位下拉
  ⑤ 从责任书切到营业执照时岗位选择被清空，不串岗位
  ⑥ 证件类预览不弹「未读取到岗位人名」提示

运行：python3 server/deploy/verify_archive_ui.py
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


def main():
    tok = dev_login()
    if not tok:
        bad("dev-login 未拿到 accessToken")
        return 1
    ok("dev-login 成功")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        # 预置登录态，避免依赖验证码图形登录
        page.add_init_script(f"""
            localStorage.setItem('token', {json.dumps(tok)});
            localStorage.setItem('userType', 'enterprise');
            localStorage.setItem('username', 'aa_gaozhou');
        """)
        page.goto(BASE + "/archive", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1200)

        # ① 分类筛选栏
        filt = page.locator(".cat-filter")
        if filt.count() == 1:
            ok("分类筛选栏已渲染")
        else:
            bad(f"分类筛选栏缺失（count={filt.count()}）")
        pills = page.locator(".cat-pill")
        texts = [pills.nth(i).inner_text().strip() for i in range(pills.count())]
        print(f"     筛选项：{texts}")
        for label in ["全部", "静态范本", "动态记录", "上传留存", "证件证照"]:
            if any(label in t for t in texts):
                ok(f"存在分类筛选「{label}」")
            else:
                bad(f"缺少分类筛选「{label}」")

        rows = page.locator(".item-row")
        n_all = rows.count()  # 必须存快照：Locator.count() 是实时查询，筛选后会跟着变
        if n_all > 0:
            ok(f"盒一归档项渲染 {n_all} 行")
        else:
            bad("归档项未渲染")
        cat_tags = page.locator(".item-row .cat")
        if cat_tags.count() > 0:
            ok(f"行内类别标签渲染 {cat_tags.count()} 个")
        else:
            bad("行内类别标签缺失")

        # ② 分类筛选切换
        cert_pill = page.locator(".cat-pill", has_text="证件证照")
        if cert_pill.count():
            cert_pill.first.click()
            page.wait_for_timeout(400)
            n_cert = page.locator(".item-row").count()
            all_cb = page.locator(".selall-cb")
            cb_text = all_cb.inner_text() if all_cb.count() else ""
            if 0 < n_cert < n_all:
                ok(f"证件筛选生效：{n_all} → {n_cert} 行")
            else:
                bad(f"证件筛选行数异常：{n_all} → {n_cert}")
            if "全选当前分类" in cb_text:
                ok("全选文案随分类切换为「全选当前分类」")
            else:
                bad(f"全选文案未切换：{cb_text}")
            page.locator(".cat-pill", has_text="全部").first.click()
            page.wait_for_timeout(400)
        else:
            bad("找不到证件分类筛选按钮")

        # 打开预览的辅助函数（页面里另有上传弹窗复用 .pdf-toolbar / .x，必须限定 .pdf-mask.show）
        TB = ".pdf-mask.show .pdf-toolbar"
        def open_item(name):
            close = page.locator(".pdf-mask.show .x")
            if close.count():
                close.first.click()
            page.wait_for_timeout(200)
            link = page.locator(".item-row .n", has_text=name)
            if not link.count():
                return False
            link.first.click()
            page.wait_for_timeout(1200)
            return page.locator(".pdf-mask.show").count() == 1

        # ③ 营业执照：证件类 + 无岗位下拉
        if not open_item("营业执照"):
            bad("未能打开「营业执照」预览")
        else:
            ok("已打开「营业执照」成品预览")
            tb = page.locator(TB)
            tb_text = tb.inner_text() if tb.count() else ""
            if "证件证照" in tb_text:
                ok("预览工具栏显示「③ 证件证照」类别")
            else:
                bad(f"预览工具栏类别标签缺失：{tb_text[:120]}")
            sels = page.locator(TB + " select.tb-sel")
            if sels.count() == 0:
                ok("营业执照预览【没有】岗位切换下拉（用户反馈已修复）")
            else:
                bad(f"营业执照预览仍存在岗位下拉：{sels.first.inner_text()[:80]}")
            warn = page.locator(".pdf-mask.show .person-warn")
            if warn.count() == 0 or not warn.first.is_visible():
                ok("营业执照预览未弹「未读取到岗位人名」提示")
            else:
                bad("营业执照预览误弹岗位人名提示")
            if "默认岗位" not in tb_text:
                ok("工具栏不含「默认岗位」文案")
            else:
                bad("工具栏仍含「默认岗位」文案")

        # ④ 责任书：静态范本 + 按岗位 + 有岗位下拉
        if not open_item("安全生产责任书"):
            bad("未能打开「安全生产责任书」预览")
        else:
            ok("已打开「安全生产责任书」成品预览")
            tb_text = page.locator(TB).inner_text()
            if "静态范本" in tb_text:
                ok("预览工具栏显示「① 静态范本」类别")
            else:
                bad(f"责任书类别标签缺失：{tb_text[:120]}")
            sels = page.locator(TB + " select.tb-sel")
            if sels.count() == 1 and "默认岗位" in sels.first.inner_text():
                opts = sels.first.locator("option").count()
                ok(f"责任书预览【保留】岗位下拉（{opts} 个选项）")
            else:
                bad(f"责任书预览岗位下拉异常（count={sels.count()}）")

        # ⑤ 从责任书切回营业执照，岗位选择不串
        if open_item("营业执照"):
            sels = page.locator(TB + " select.tb-sel")
            if sels.count() == 0:
                ok("责任书 → 营业执照切换后仍无岗位下拉（无串岗位）")
            else:
                bad("切换回营业执照后又出现岗位下拉")

        page.screenshot(path="/tmp/archive_category_verify.png", full_page=False)
        browser.close()

    print(f"\n结果：{PASS} 通过 / {FAIL} 失败")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
