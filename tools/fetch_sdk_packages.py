#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
绕过 sdkmanager，直接从 Google 的仓库清单下载 Android SDK 组件。

为什么不用 sdkmanager：本机 Java 侧访问 dl.google.com 的仓库清单会 IO 异常
（而 Python 访问同一个域名是通的），于是改成用 Python 自己解析 repository XML
再下载对应的 zip 包，效果完全一样。

用法：
    python tools/fetch_sdk_packages.py
"""

import os
import shutil
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.path.join(ROOT, ".toolchain", "android-sdk")
BASE = "https://dl.google.com/android/repository/"

# 仓库清单（3 是新版，1 是旧版兜底）
MANIFESTS = ["repository2-3.xml", "repository2-1.xml"]

# (包路径, 期望解压后的目录名)
WANTED = [
    ("platform-tools", "platform-tools"),
    ("platforms;android-34", "android-34"),
    ("build-tools;34.0.0", "34.0.0"),
]

# 清单里查不到时的兜底直链（历史上这些文件名很稳定）
FALLBACKS = {
    "platforms;android-34": "platform-34-ext7_r03.zip",
    "build-tools;34.0.0": "build-tools_r34-windows.zip",
    "platform-tools": "platform-tools-latest-windows.zip",
}


def fetch(url, timeout=60):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(request, timeout=timeout)


def load_manifest():
    for name in MANIFESTS:
        try:
            print("  读取清单 %s" % name)
            with fetch(BASE + name) as response:
                data = response.read()
            print("  清单大小 %.1f MB" % (len(data) / 1048576.0))
            return ET.fromstring(data)
        except Exception as exc:                   # noqa: BLE001
            print("  失败：%s: %s" % (type(exc).__name__, exc))
    return None


def find_archive(root, package_path):
    """在清单里找某个包的第一个 archive 的 url"""
    for node in root.iter("remotePackage"):
        if node.get("path") != package_path:
            continue
        for archive in node.iter("archive"):
            url_node = archive.find("url")
            if url_node is not None and url_node.text:
                return url_node.text.strip()
    return None


def download_and_extract(url, dest_dir, expect_name, package_path):
    zip_path = os.path.join(SDK, "_download.zip")
    os.makedirs(SDK, exist_ok=True)

    full_url = url if url.startswith("http") else BASE + url
    print("  下载 %s" % full_url)
    try:
        with fetch(full_url, timeout=120) as response, open(zip_path, "wb") as handle:
            total = int(response.headers.get("Content-Length") or 0)
            received = 0
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
                received += len(chunk)
        print("  完成 %.1f MB" % (received / 1048576.0))
    except Exception as exc:                       # noqa: BLE001
        print("  下载失败：%s: %s" % (type(exc).__name__, exc))
        return False

    tmp = os.path.join(SDK, "_tmp_extract")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    try:
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(tmp)
    except Exception as exc:                       # noqa: BLE001
        print("  解压失败：%s: %s" % (type(exc).__name__, exc))
        return False

    entries = [e for e in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, e))]
    target = os.path.join(dest_dir, expect_name)
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(dest_dir, exist_ok=True)

    if len(entries) == 1:
        # 标准的单顶层目录包：直接改名成我们期望的名字
        shutil.move(os.path.join(tmp, entries[0]), target)
    else:
        # 少数包（如 platform-tools）解压出来就是散文件
        shutil.move(tmp, target)
        tmp = None

    if tmp:
        shutil.rmtree(tmp, ignore_errors=True)
    os.remove(zip_path)
    print("  已解压到 %s" % os.path.relpath(target, ROOT))
    return True


def main():
    if not os.path.isdir(SDK):
        print("请先运行 python tools/setup_toolchain.py")
        return 1

    print("=" * 64)
    print("用 Python 直接下载 Android SDK 组件到 %s" % os.path.relpath(SDK, ROOT))
    print("=" * 64)

    print("获取仓库清单…")
    root = load_manifest()

    ok_all = True
    for package_path, expect_name in WANTED:
        print()
        print("[%s]" % package_path)

        url = find_archive(root, package_path) if root is not None else None
        if url:
            print("  清单中的文件：%s" % url)
        else:
            url = FALLBACKS.get(package_path)
            print("  清单里没找到，改用兜底直链：%s" % url)
        if not url:
            ok_all = False
            continue

        dest = os.path.join(SDK, "platforms" if package_path.startswith("platforms") else "")
        if package_path.startswith("build-tools"):
            dest = os.path.join(SDK, "build-tools")
        elif package_path == "platform-tools":
            dest = SDK
            expect_name = "platform-tools"

        if not download_and_extract(url, dest, expect_name, package_path):
            ok_all = False

    # 平台包里带的目录名可能是 android-34-ext7 之类，统一改成 android-34
    platforms_dir = os.path.join(SDK, "platforms")
    if os.path.isdir(platforms_dir):
        for entry in os.listdir(platforms_dir):
            if entry.startswith("android-34") and entry != "android-34":
                src = os.path.join(platforms_dir, entry)
                dst = os.path.join(platforms_dir, "android-34")
                shutil.rmtree(dst, ignore_errors=True)
                shutil.move(src, dst)
                print("已把 %s 重命名为 android-34" % entry)

    print()
    print("=" * 64)
    print("结果：")
    checks = [
        ("platform-tools", os.path.join(SDK, "platform-tools", "adb.exe")),
        ("android.jar (platform-34)", os.path.join(SDK, "platforms", "android-34", "android.jar")),
        ("aapt2 (build-tools)", os.path.join(SDK, "build-tools", "34.0.0", "aapt2.exe")),
        ("d8 (build-tools)", os.path.join(SDK, "build-tools", "34.0.0", "d8.bat")),
    ]
    for name, path in checks:
        exists = os.path.exists(path)
        if not exists:
            ok_all = False
        print("  %-28s %s" % (name, "OK" if exists else "缺失 -> " + os.path.relpath(path, ROOT)))
    print("=" * 64)
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
