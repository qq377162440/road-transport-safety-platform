"""盒十一新功能 UI 实机冒烟：一键下载整盒(ZIP) + 下载合并版档案册(PDF)。
登录 dev-login -> 进 /archive -> 盒十一，验证按钮存在并触发真实下载。"""
import json, os, sys, time
from playwright.sync_api import sync_playwright

BASE = "http://localhost"
API = "http://localhost:8080"
DOWNLOAD_DIR = "/tmp/pw_box11"
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

_passed, _failed = [], []
def ok(m): _passed.append(m); print("  ✅", m)
def bad(m): _failed.append(m); print("  ❌", m)

def dev_login(username="aa_gaozhou", password="123456"):
    import urllib.request
    req = urllib.request.Request(API + "/api/auth/dev-login",
        data=json.dumps({"username":username,"password":password}).encode(),
        headers={"Content-Type":"application/json"}, method="POST")
    return json.load(urllib.request.urlopen(req, timeout=10))["data"]["accessToken"]

def main():
    tok = dev_login()
    if not tok: bad("dev-login 未拿到 token"); return 1
    ok("dev-login 成功")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        ctx = browser.new_context(accept_downloads=True)
        page = ctx.new_page()
        page.add_init_script(f"localStorage.setItem('token', {json.dumps(tok)});"
                             "localStorage.setItem('userType','enterprise');"
                             "localStorage.setItem('username','aa_gaozhou');")
        page.goto(BASE + "/archive", wait_until="networkidle", timeout=45000)
        page.wait_for_timeout(1200)

        # 进入盒十一
        page.locator("span.bx-name", has_text="盒十一").first.click()
        page.wait_for_timeout(1000)
        if page.locator(".crew-panel").count() != 1:
            bad("盒十一未渲染 .crew-panel"); return 1
        ok("盒十一专属面板渲染")

        # 头部「一键下载整盒」按钮
        dl_all = page.get_by_role("button", name="一键下载整盒")
        if dl_all.count() >= 1:
            ok("盒十一头部存在「一键下载整盒」按钮")
        else:
            bad("盒十一头部缺少「一键下载整盒」按钮"); return 1

        # 展开第一个从业人员，验证「下载一人一档档案册」按钮
        first_emp = page.locator(".crew-card-h").first
        first_emp.click()
        page.wait_for_timeout(600)
        album_btn = page.get_by_role("button", name="下载一人一档档案册（合并版 PDF）")
        if album_btn.count() >= 1:
            ok("从业人员明细含「下载一人一档档案册」按钮")
        else:
            bad("从业人员明细缺少档案册按钮"); return 1

        # 实际触发合并版档案册下载，校验文件
        with page.expect_download(timeout=60000) as dl_info:
            album_btn.first.click()
        d = dl_info.value
        path = os.path.join(DOWNLOAD_DIR, d.suggested_filename)
        d.save_as(path)
        size = os.path.getsize(path)
        head = open(path, "rb").read(5)
        is_pdf = head.startswith(b"%PDF")
        if size > 1000 and is_pdf:
            ok(f"合并版档案册下载成功：{d.suggested_filename} ({size} bytes, PDF)")
        else:
            bad(f"档案册下载异常：{d.suggested_filename} size={size} pdf={is_pdf}"); return 1

        # 切换到一车一档，验证车辆档案册按钮
        page.get_by_role("button", name="一车一档").click()
        page.wait_for_timeout(600)
        first_veh = page.locator(".crew-card-h").first
        first_veh.click()
        page.wait_for_timeout(600)
        veh_album = page.get_by_role("button", name="下载一车一档档案册（合并版 PDF）")
        if veh_album.count() >= 1:
            ok("车辆明细含「下载一车一档档案册」按钮")
        else:
            bad("车辆明细缺少档案册按钮"); return 1

        with page.expect_download(timeout=60000) as dl_info2:
            veh_album.first.click()
        d2 = dl_info2.value
        path2 = os.path.join(DOWNLOAD_DIR, d2.suggested_filename)
        d2.save_as(path2)
        size2 = os.path.getsize(path2)
        if size2 > 1000 and open(path2,"rb").read(5).startswith(b"%PDF"):
            ok(f"车辆合并版档案册下载成功：{d2.suggested_filename} ({size2} bytes, PDF)")
        else:
            bad(f"车辆档案册下载异常 size={size2}"); return 1

        # 整盒 ZIP 下载
        with page.expect_download(timeout=90000) as dl_info3:
            dl_all.first.click()
        d3 = dl_info3.value
        path3 = os.path.join(DOWNLOAD_DIR, d3.suggested_filename)
        d3.save_as(path3)
        size3 = os.path.getsize(path3)
        import zipfile
        zok = False
        try:
            z = zipfile.ZipFile(path3)
            zok = len(z.namelist()) > 0 and all(i.flag_bits & 0x800 for i in z.infolist())
        except Exception as e:
            bad(f"ZIP 校验失败: {e}")
        if size3 > 1000 and zok:
            ok(f"整盒 ZIP 下载成功：{d3.suggested_filename} ({size3} bytes, {len(zipfile.ZipFile(path3).namelist())} 个文件, UTF-8)")
        else:
            bad(f"整盒 ZIP 异常 size={size3} zok={zok}"); return 1

        browser.close()

    print(f"\n通过 {len(_passed)} 项，失败 {len(_failed)} 项")
    for m in _failed: print("  FAIL:", m)
    return 1 if _failed else 0

if __name__ == "__main__":
    sys.exit(main())
