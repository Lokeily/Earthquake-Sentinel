#!/usr/bin/env python3
"""Update the dianguard-site GitHub Pages repo via the Contents API.
Token is read from stdin (never written to disk)."""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request as u

TOKEN = sys.stdin.read().strip()
OWNER, REPO = "Lokeily", "dianguard-site"
BASE = f"https://api.github.com/repos/{OWNER}/{REPO}/contents/"
SRC = r"C:/Users/nsklr/WorkBuddy/2026-08-01-14-49-09/Dianguard/docs"

README = """# 地震哨兵 Dianguard 官网

Android 地震预警应用「地震哨兵」的官方展示与下载站点（GitHub Pages）。

- 在线访问: https://lokeily.github.io/dianguard-site/
- 版本更新页: https://lokeily.github.io/dianguard-site/changelog.html
- 免责声明:   https://lokeily.github.io/dianguard-site/disclaimer.html
- 应用仓库:   https://github.com/Lokeily/Earthquake-Sentinel

## 下载实时同步最新版

首页与下载区的 APK 链接、版本号、更新时间，均在每次打开页面时
实时读取 `Lokeily/Earthquake-Sentinel` 的最新 Release，
因此用户下载到的永远是最新版。

## 本地预览

```bash
cd docs
python3 -m http.server 8000
```

## 说明

纯静态站点，无构建步骤。根目录即 GitHub Pages 发布目录。
修改后重跑部署脚本即可覆盖更新。
"""

# (repo_path, local_path_or_None_for_inline, inline_content)
FILES = [
    ("index.html", os.path.join(SRC, "index.html"), None),
    ("features.html", os.path.join(SRC, "features.html"), None),
    ("how.html", os.path.join(SRC, "how.html"), None),
    ("disclaimer.html", os.path.join(SRC, "disclaimer.html"), None),
    ("changelog.html", os.path.join(SRC, "changelog.html"), None),
    ("styles.css", os.path.join(SRC, "styles.css"), None),
    ("app.js", os.path.join(SRC, "app.js"), None),
    ("assets/ic_launcher.png", os.path.join(SRC, "assets", "ic_launcher.png"), None),
    ("assets/ic_launcher_round.png", os.path.join(SRC, "assets", "ic_launcher_round.png"), None),
    ("README.md", None, README),
]


def req(method, url, data=None, _tries=5):
    headers = {
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "site-update",
        "Content-Type": "application/json",
    }
    last = None
    for attempt in range(_tries):
        try:
            r = u.Request(url, data=data, headers=headers, method=method)
            with u.urlopen(r, timeout=60) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            last = e
            # 4xx 客户端错误（除 409 冲突 / 429 限流）不重试，直接抛出
            if e.code < 500 and e.code not in (409, 429):
                raise
            time.sleep(1.5 * (attempt + 1))
        except urllib.error.URLError as e:
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise last


def main():
    for repo_path, local, inline in FILES:
        if inline is not None:
            content = inline.encode("utf-8")
        else:
            with open(local, "rb") as f:
                content = f.read()
        b64 = base64.b64encode(content).decode("ascii")

        sha = None
        try:
            st, cur = req("GET", BASE + repo_path)
            sha = cur.get("sha")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise

        payload = {
            "message": "site update: " + repo_path,
            "content": b64,
            "branch": "main",
        }
        if sha:
            payload["sha"] = sha
        st, _ = req("PUT", BASE + repo_path, json.dumps(payload).encode("utf-8"))
        print(f"{st}  {repo_path}")


if __name__ == "__main__":
    main()
