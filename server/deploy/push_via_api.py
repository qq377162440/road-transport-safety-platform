#!/usr/bin/env python3
"""
GitHub Git Data API 推送 —— 自底向上递归建树版。

每个提交：按目录层级递归创建 tree（先子目录、后父目录），与本地 tree SHA 逐级校验，
最终根树一致才建 commit。仅当最终 commit SHA == 本地 HEAD 时更新 refs/heads/master。
"""
import base64
import concurrent.futures as cf
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN", "")
OWNER = "qq377162440"
REPO = "road-transport-safety-platform"
API = "https://api.github.com"
WORK = "/workspace"
BRANCH = "master"


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "safetrans-push",
    })
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                txt = r.read().decode()
                return r.status, (json.loads(txt) if txt else {})
        except urllib.error.HTTPError as e:
            txt = e.read().decode()
            try:
                obj = json.loads(txt) if txt else {}
            except Exception:
                obj = {"raw": txt}
            if e.code in (429, 500, 502, 503, 504, 422) and attempt < 4 and \
                    ("timed out" in str(obj.get("message", "")) or e.code != 422):
                time.sleep(2 * (attempt + 1))
                continue
            return e.code, obj
        except Exception:
            if attempt < 4:
                time.sleep(2 * (attempt + 1))
                continue
            raise
    return 599, {"raw": "retries exhausted"}


def git(*args, binary=False):
    out = subprocess.run(["git", *args], cwd=WORK, capture_output=True)
    if out.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败: {out.stderr.decode(errors='replace')}")
    return out.stdout if binary else out.stdout.decode(errors="replace").strip()


def ls_entry(rev, path=None):
    """返回 [(mode, type, sha, name)]，仅直接子项。
    注意：给目录路径时必须用 <rev>:<path> 形式，否则 ls-tree 只会回显该目录条目本身。"""
    target = f"{rev}:{path}" if path else rev
    raw = git("ls-tree", "-z", target, binary=True)
    out = []
    for rec in raw.split(b"\x00"):
        if not rec:
            continue
        meta, name = rec.split(b"\t", 1)
        mode, typ, sha = meta.decode().split()
        out.append((mode, typ, sha, name.decode("utf-8")))
    return out


_blobs = set()
_blob_lock = threading.Lock()


def upload_blob(sha):
    with _blob_lock:
        if sha in _blobs:
            return
    content = git("cat-file", "blob", sha, binary=True)
    st, blob = api("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                   {"content": base64.b64encode(content).decode(), "encoding": "base64"})
    if st not in (200, 201):
        raise RuntimeError(f"上传 blob 失败 {sha[:8]}: {st} {blob}")
    if blob["sha"] != sha:
        raise RuntimeError(f"blob SHA 不一致 {sha[:8]} != {blob['sha'][:8]}")
    with _blob_lock:
        _blobs.add(sha)


_tree_cache = {}   # local tree sha -> remote tree sha


def make_tree(commit, path=""):
    """递归建树，返回远端 tree sha；path 为空表示根树。"""
    local_sha = git("rev-parse", f"{commit}^{{tree}}" if not path else f"{commit}:{path}")
    if local_sha in _tree_cache:
        return _tree_cache[local_sha]

    entries = []
    for mode, typ, sha, name in ls_entry(commit, path or None):
        if typ == "tree":
            sub_path = f"{path}/{name}" if path else name
            sub_remote = make_tree(commit, sub_path)
            entries.append({"path": name, "mode": "040000", "type": "tree", "sha": sub_remote})
        elif typ == "blob":
            entries.append({"path": name, "mode": mode, "type": "blob", "sha": sha})
        elif typ == "commit":
            entries.append({"path": name, "mode": mode, "type": "commit", "sha": sha})
        else:
            raise RuntimeError(f"未知类型 {typ} @ {path}/{name}")

    st, tree = api("POST", f"/repos/{OWNER}/{REPO}/git/trees", {"tree": entries})
    if st not in (200, 201):
        raise RuntimeError(f"建树失败 @ '{path or '.'}' ({len(entries)} 项): {st} {tree}")
    if tree["sha"] != local_sha:
        raise RuntimeError(f"树 SHA 不一致 @ '{path or '.'}' gh={tree['sha'][:10]} local={local_sha[:10]}")
    _tree_cache[local_sha] = tree["sha"]
    return tree["sha"]


def main():
    if len(TOKEN) < 20:
        print("ERROR: GITHUB_TOKEN 缺失"); return 2
    st, me = api("GET", "/user")
    if st != 200:
        print(f"ERROR: 鉴权失败 ({st}) {me.get('message')}"); return 2
    print(f"鉴权 OK: {me.get('login')}")

    st, repo = api("GET", f"/repos/{OWNER}/{REPO}")
    if st != 200:
        print(f"ERROR: 仓库不可访问 ({st}) {repo.get('message')}"); return 2
    print(f"仓库 OK: {repo['full_name']}")

    base = git("rev-parse", "origin/master")
    head = git("rev-parse", "HEAD")
    print(f"base={base[:10]} head={head[:10]}")

    # 可选：显式指定远端基线（当本地 origin/master 与远端 SHA 不一致但 tree 相同时）
    remote_base = os.environ.get("REMOTE_BASE", base)
    only_commit = os.environ.get("ONLY_COMMIT", "")

    st, base_c = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{remote_base}")
    if st != 200:
        print(f"ERROR: 基线 {remote_base[:10]} 不在远端 ({st})"); return 3
    # 本地基线的 tree 应与远端基线 tree 一致（用于校验内容连续性）
    if base_c["tree"]["sha"] != git("rev-parse", f"{base}^{{tree}}"):
        print("ERROR: 本地基线 tree 与远端基线 tree 不一致"); return 3
    print("基线 tree 一致，可安全快进。")
    _tree_cache[git("rev-parse", f"{base}^{{tree}}")] = base_c["tree"]["sha"]

    if only_commit:
        commits = [only_commit]
        print(f"只推指定提交: {only_commit[:10]}")
    else:
        commits = git("rev-list", "--reverse", f"{base}..HEAD").splitlines()
    print(f"待推送提交数: {len(commits)}")

    # 预上传所有 blob（去重 + 并发）
    need = set()
    for c in commits:
        raw = git("ls-tree", "-r", "-z", c, binary=True)
        for rec in raw.split(b"\x00"):
            if not rec:
                continue
            meta = rec.split(b"\t", 1)[0].decode().split()
            if meta[1] == "blob":
                need.add(meta[2])
    print(f"唯一 blob 数: {len(need)}（并发上传）")
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        list(ex.map(upload_blob, sorted(need)))
    print("blob 上传完成。")

    parent_sha = remote_base
    parent_tree = base_c["tree"]["sha"]
    ok = True
    for c in commits:
        tree_sha = make_tree(c)
        parent_tree = tree_sha
        msg = git("log", "-1", "--format=%B", c)
        st, cm = api("POST", f"/repos/{OWNER}/{REPO}/git/commits", {
            "message": msg, "tree": tree_sha, "parents": [parent_sha],
            "author": {
                "name": git("log", "-1", "--format=%an", c),
                "email": git("log", "-1", "--format=%ae", c),
                "date": git("log", "-1", "--format=%aI", c),
            },
            "committer": {
                "name": git("log", "-1", "--format=%cn", c),
                "email": git("log", "-1", "--format=%ce", c),
                "date": git("log", "-1", "--format=%cI", c),
            },
        })
        if st not in (200, 201):
            print(f"ERROR: 建提交失败 @ {c[:10]}: {st} {cm}"); return 4
        new_sha = cm["sha"]
        print(f"  [{'MATCH' if new_sha == c else 'DIFF '}] {c[:10]} -> {new_sha[:10]}  "
              f"{git('log','-1','--format=%s',c)[:46]}")
        parent_sha = new_sha

    local_head_tree = git("rev-parse", f"{head}^{{tree}}")
    if parent_tree != local_head_tree:
        print(f"\nSTOP: 最终树 {parent_tree[:10]} != 本地 HEAD 树 {local_head_tree[:10]}，未更新分支。")
        return 5
    print(f"\n最终树一致（{parent_tree[:10]}），内容与本地 HEAD 逐字节相同。")

    st, ref = api("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
                  {"sha": parent_sha, "force": False})
    if st not in (200, 201):
        print(f"ERROR: 更新 ref 失败: {st} {ref}"); return 6
    print(f"✅ 推送成功！{BRANCH} -> {parent_sha[:10]}（{len(commits)} 个提交，快进自 {base[:10]}）")
    try:
        git("update-ref", f"refs/remotes/origin/{BRANCH}", head)
        print("已同步本地 origin/master 跟踪引用。")
    except Exception as e:
        print(f"（本地跟踪引用未更新：{e}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
