#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将本地签名 APK 部署到 GitHub Release（绕过 git 协议，仅用 REST API）。
令牌从环境变量 GITHUB_TOKEN 读取，绝不写死在脚本里。
用法: GITHUB_TOKEN=xxx python3 _upload_release_apk.py
"""
import os
import sys
import json
import urllib.request
import urllib.error

OWNER = "Lokeily"
REPO = "Earthquake-Sentinel"
TAG = "v1.2.0"
ASSET_NAME = "Dianguard-v1.2.0-release.apk"
APK_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ASSET_NAME)
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"


def api_request(method, url, token, data=None, headers=None, is_upload=False):
    req_headers = {"Authorization": f"Bearer {token}"}
    if headers:
        req_headers.update(headers)
    if isinstance(data, str):
        data = data.encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        return e.code, body


def main():
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        # 兼容从 stdin 读取令牌，避免令牌出现在进程参数/脚本文件中
        try:
            token = sys.stdin.read().strip()
        except Exception:
            token = ""
    if not token:
        print("ERROR: 未设置环境变量 GITHUB_TOKEN，也未从 stdin 读取到令牌")
        sys.exit(1)
    if not os.path.isfile(APK_PATH):
        print(f"ERROR: 找不到 APK: {APK_PATH}")
        sys.exit(1)

    apk_size = os.path.getsize(APK_PATH)
    print(f"[1/4] 本地 APK: {APK_PATH} ({apk_size} bytes)")

    # 1) 取 release id
    st, body = api_request("GET", f"{API}/repos/{OWNER}/{REPO}/releases/tags/{TAG}", token)
    if st != 200:
        print(f"ERROR: 查询 Release 失败 http={st}: {body[:300]}")
        sys.exit(1)
    rel = json.loads(body)
    release_id = rel["id"]
    print(f"[2/4] Release '{TAG}' id={release_id}, 现有资产: {[a['name'] for a in rel.get('assets', [])]}")

    # 2) 删除同名旧资产（避免重名冲突）
    for a in rel.get("assets", []):
        if a["name"] == ASSET_NAME:
            dst, _ = api_request("DELETE", f"{API}/repos/{OWNER}/{REPO}/releases/assets/{a['id']}", token)
            print(f"      删除旧资产 {ASSET_NAME}: http={dst}")

    # 3) 上传新资产
    with open(APK_PATH, "rb") as f:
        apk_bytes = f.read()
    url = f"{UPLOAD}/repos/{OWNER}/{REPO}/releases/{release_id}/assets?name={ASSET_NAME}"
    st, body = api_request(
        "POST", url, token,
        data=apk_bytes,
        headers={"Content-Type": "application/vnd.android.package-archive"},
    )
    if st not in (200, 201):
        print(f"ERROR: 上传失败 http={st}: {body[:400]}")
        sys.exit(1)
    up = json.loads(body)
    print(f"[3/4] 上传成功: {up.get('name')} size={up.get('size')} browser_download_url={up.get('browser_download_url')}")

    # 4) 复核
    st, body = api_request("GET", f"{API}/repos/{OWNER}/{REPO}/releases/tags/{TAG}", token)
    rel = json.loads(body)
    names = [a["name"] for a in rel.get("assets", [])]
    print(f"[4/4] 复核 Release 资产: {names}")
    print("DONE" if ASSET_NAME in names else "WARN: 资产未出现在列表中")


if __name__ == "__main__":
    main()
