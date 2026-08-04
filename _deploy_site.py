#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 docs/ 静态站点推送到一个新建的 GitHub 仓库并开启 GitHub Pages。
令牌从 stdin 读取，绝不写死在脚本里。
用法: python3 _deploy_site.py <<< "$TOKEN"
"""
import os
import sys
import json
import base64
import urllib.request
import urllib.error

REPO_NAME = "dianguard-site"
REPO_DESC = "地震哨兵 Dianguard 官网 — Android 地震预警应用的下载与功能展示"
SITE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "docs")
API = "https://api.github.com"
BRANCH = "main"


def req(method, url, token, payload=None):
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "dianguard-site-deployer",
    }
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body[:500]}


def collect_files():
    out = []
    for root, _dirs, files in os.walk(SITE_DIR):
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, SITE_DIR).replace("\\", "/")
            with open(full, "rb") as f:
                out.append((rel, f.read()))
    return out


def main():
    token = sys.stdin.read().strip()
    if not token:
        print("ERROR: 未从 stdin 读取到令牌")
        sys.exit(1)

    st, me = req("GET", f"{API}/user", token)
    if st != 200:
        print(f"ERROR: 令牌无效 http={st}: {me}")
        sys.exit(1)
    owner = me["login"]
    print(f"[1/6] 认证成功: {owner}")

    # 建仓库（已存在则复用）
    st, rep = req("POST", f"{API}/user/repos", token, {
        "name": REPO_NAME,
        "description": REPO_DESC,
        "homepage": f"https://{owner.lower()}.github.io/{REPO_NAME}/",
        "private": False,
        "has_issues": True,
        "has_wiki": False,
        "auto_init": False,
    })
    if st in (200, 201):
        print(f"[2/6] 仓库已创建: {rep['full_name']}")
    elif st == 422:
        print(f"[2/6] 仓库已存在，复用: {owner}/{REPO_NAME}")
    else:
        print(f"ERROR: 建仓库失败 http={st}: {rep}")
        sys.exit(1)

    files = collect_files()
    extra = [
        (".nojekyll", b""),
        ("README.md", (
            f"# 地震哨兵 Dianguard 官网\n\n"
            f"Android 地震预警应用「地震哨兵」的官方展示与下载站点。\n\n"
            f"- 在线访问: https://{owner.lower()}.github.io/{REPO_NAME}/\n"
            f"- 应用仓库: https://github.com/{owner}/Earthquake-Sentinel\n"
            f"- 免责声明: https://{owner.lower()}.github.io/{REPO_NAME}/disclaimer.html\n\n"
            f"## 本地预览\n\n```bash\npython3 -m http.server 8000\n```\n\n"
            f"## 说明\n\n纯静态站点，无构建步骤。根目录即 GitHub Pages 发布目录。\n"
        ).encode("utf-8")),
    ]
    files.extend(extra)
    print(f"[3/6] 待上传文件 {len(files)} 个: {[f[0] for f in files]}")

    # 空仓库无法直接建 blob（409 Git Repository is empty），先用 Contents API 打底一个初始提交
    st, _ = req("GET", f"{API}/repos/{owner}/{REPO_NAME}/git/ref/heads/{BRANCH}", token)
    if st != 200:
        st, boot = req("PUT", f"{API}/repos/{owner}/{REPO_NAME}/contents/README.md", token, {
            "message": "chore: 初始化仓库",
            "content": base64.b64encode("# 地震哨兵 Dianguard 官网\n".encode("utf-8")).decode("ascii"),
            "branch": BRANCH,
        })
        if st not in (200, 201):
            print(f"ERROR: 初始化空仓库失败 http={st}: {boot}")
            sys.exit(1)
        print("      空仓库已初始化（README 打底提交）")

    # 建 blob
    tree_items = []
    for rel, content in files:
        st, blob = req("POST", f"{API}/repos/{owner}/{REPO_NAME}/git/blobs", token, {
            "content": base64.b64encode(content).decode("ascii"),
            "encoding": "base64",
        })
        if st not in (200, 201):
            print(f"ERROR: 创建 blob 失败 {rel} http={st}: {blob}")
            sys.exit(1)
        tree_items.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print(f"[4/6] blob 创建完成 ({len(tree_items)} 个)")

    # 已有 head?
    st, ref = req("GET", f"{API}/repos/{owner}/{REPO_NAME}/git/ref/heads/{BRANCH}", token)
    parent = ref["object"]["sha"] if st == 200 else None

    tree_payload = {"tree": tree_items}
    st, tree = req("POST", f"{API}/repos/{owner}/{REPO_NAME}/git/trees", token, tree_payload)
    if st not in (200, 201):
        print(f"ERROR: 创建 tree 失败 http={st}: {tree}")
        sys.exit(1)

    commit_payload = {
        "message": "feat: 地震哨兵 Dianguard 官网（功能展示 / 下载 / 免责声明）",
        "tree": tree["sha"],
    }
    if parent:
        commit_payload["parents"] = [parent]
    st, commit = req("POST", f"{API}/repos/{owner}/{REPO_NAME}/git/commits", token, commit_payload)
    if st not in (200, 201):
        print(f"ERROR: 创建 commit 失败 http={st}: {commit}")
        sys.exit(1)

    if parent:
        st, _ = req("PATCH", f"{API}/repos/{owner}/{REPO_NAME}/git/refs/heads/{BRANCH}", token,
                    {"sha": commit["sha"], "force": True})
    else:
        st, _ = req("POST", f"{API}/repos/{owner}/{REPO_NAME}/git/refs", token,
                    {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]})
    if st not in (200, 201):
        print(f"ERROR: 更新 ref 失败 http={st}")
        sys.exit(1)
    print(f"[5/6] 提交推送成功: {commit['sha'][:8]}")

    # 开启 Pages
    st, pages = req("POST", f"{API}/repos/{owner}/{REPO_NAME}/pages", token,
                    {"source": {"branch": BRANCH, "path": "/"}})
    if st in (201, 409):
        st2, pg = req("GET", f"{API}/repos/{owner}/{REPO_NAME}/pages", token)
        url = pg.get("html_url") if st2 == 200 else f"https://{owner.lower()}.github.io/{REPO_NAME}/"
        print(f"[6/6] GitHub Pages 已开启: {url}")
    else:
        print(f"[6/6] Pages 开启返回 http={st}: {pages}")
        print(f"      如失败请到 Settings → Pages 手动选择 {BRANCH} / root")

    print("\nDONE")
    print(f"仓库:  https://github.com/{owner}/{REPO_NAME}")
    print(f"站点:  https://{owner.lower()}.github.io/{REPO_NAME}/")


if __name__ == "__main__":
    main()
