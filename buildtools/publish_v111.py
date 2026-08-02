#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
地震哨兵 v1.0.11 发布脚本（幂等）。

- 若 GitHub 上已存在 tag=v1.0.11 的 Release，则复用；否则创建。
- 上传当前构建的 debug APK；若同名资源已存在则先删除再上传。
- 仓库：Lokeily/Dianguard
- 通过环境变量 GH_TOKEN 提供 GitHub PAT。
"""
import os
import sys
import json
import urllib.request
import urllib.error

REPO = "Lokeily/Dianguard"
TAG = "v1.0.11"
RELEASE_NAME = "地震哨兵 v1.0.11"
APK_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "build", "outputs", "apk", "debug", "app-debug.apk"
)
ASSET_NAME = "Dianguard-v1.0.11-debug.apk"
RELEASE_BODY = (
    "地震哨兵 v1.0.11\n\n"
    "更新内容：\n"
    "1. 新增应用内更新检测与引导机制：打开 APP 时自动检查 GitHub 最新 Release；\n"
    "2. 发现新版本时弹窗提示，提供“前往更新”按钮，点击后应用内直接下载最新 APK；\n"
    "3. 下载完成后调起系统安装器，同签名 + 更高 versionCode 自动覆盖旧版，无需手动卸载；\n"
    "4. 自动下载失败时回退到浏览器打开下载链接 / 发布页，更新流程不中断；\n"
    "5. 更新检查做了节流（默认 30 分钟内只查一次，规避 GitHub 匿名 API 限流），\n"
    "   同一版本用户选择“稍后”后不再重复弹窗。"
)

API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"


def api_request(method, url, data=None, headers=None, is_json=True):
    token = os.environ.get("GH_TOKEN")
    if not token:
        print("ERROR: 未设置环境变量 GH_TOKEN")
        sys.exit(2)
    h = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"}
    if headers:
        h.update(headers)
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode("utf-8")
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(body) if body and is_json else body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        return e.code, body


def get_existing_release():
    status, data = api_request("GET", f"{API}/repos/{REPO}/releases/tags/{TAG}")
    if status == 200:
        return data.get("id")
    return None


def create_release():
    status, data = api_request(
        "POST",
        f"{API}/repos/{REPO}/releases",
        {
            "tag_name": TAG,
            "name": RELEASE_NAME,
            "body": RELEASE_BODY,
            "draft": False,
            "prerelease": False,
        },
    )
    if status not in (200, 201):
        print(f"ERROR: 创建 Release 失败 ({status}): {data}")
        sys.exit(3)
    return data.get("id")


def delete_existing_asset(rel_id):
    status, data = api_request("GET", f"{API}/repos/{REPO}/releases/{rel_id}/assets")
    if status != 200:
        return
    for a in data:
        if a.get("name") == ASSET_NAME:
            api_request("DELETE", f"{API}/repos/{REPO}/releases/assets/{a['id']}")


def upload_asset(rel_id):
    if not os.path.isfile(APK_PATH):
        print(f"ERROR: 找不到 APK: {APK_PATH}")
        sys.exit(4)
    delete_existing_asset(rel_id)
    with open(APK_PATH, "rb") as f:
        data = f.read()
    url = (f"{UPLOAD}/repos/{REPO}/releases/{rel_id}/assets"
           f"?name={ASSET_NAME}")
    status, resp = api_request(
        "POST",
        url,
        data=data,
        headers={"Content-Type": "application/vnd.android.package-archive"},
        is_json=True,
    )
    if status not in (200, 201):
        print(f"ERROR: 上传 APK 失败 ({status}): {resp}")
        sys.exit(5)
    print(f"OK: 已上传 {ASSET_NAME} ({len(data)/1024/1024:.1f} MB)")


def main():
    print(f"发布 {TAG} 到 {REPO} ...")
    rel_id = get_existing_release()
    if rel_id:
        print(f"复用已存在的 Release (id={rel_id})")
    else:
        rel_id = create_release()
        print(f"已创建 Release (id={rel_id})")
    upload_asset(rel_id)
    print("发布完成。")


if __name__ == "__main__":
    main()
