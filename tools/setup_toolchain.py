#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把编译 Android APK 需要的工具链全部下载到**项目文件夹内部**（.toolchain/），不碰 C 盘。

包含：
    1. OpenJDK 17        -> .toolchain/jdk17        （AGP 8.x 强制要求 JDK 17）
    2. Gradle 8.7        -> .toolchain/gradle-8.7   （与 AGP 8.5.2 匹配）
    3. Android SDK 命令行工具 -> .toolchain/android-sdk/cmdline-tools/latest
    4. Android SDK 组件   -> .toolchain/android-sdk/{platforms,build-tools,platform-tools}

为什么用 Python 而不是 PowerShell/curl：本机 PowerShell 的 schannel 拿不到凭据
（SEC_E_NO_CREDENTIALS），下载会失败；Python 自带的 OpenSSL 没有这个问题。

用法：
    python tools/setup_toolchain.py            # 全部下载并解压
    python tools/setup_toolchain.py --check    # 只检查各下载源是否可达
    python tools/setup_toolchain.py --sdk-packages   # 只装 Android SDK 组件
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLCHAIN = os.path.join(ROOT, ".toolchain")
GRADLE_HOME = os.path.join(TOOLCHAIN, "gradle-home")     # 避免依赖写到 C:\Users\xxx\.gradle
SDK_ROOT = os.path.join(TOOLCHAIN, "android-sdk")

# Android SDK 组件（sdkmanager 的包名）
SDK_PACKAGES = [
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0",
]

# 官方 sdkmanager 会要求交互式同意许可；非交互环境下直接写这两个文件
# 内容就是各许可协议的 SHA1 摘要，写进去等价于「我同意」。
LICENSES = {
    "android-sdk-license": [
        "24333f8a63b6825ea9c5514f83c2829b004d1fee",
        "8933bad161af4178b1185d1a37fbf41ea5269c55",
        "d56f5187479451eabf01fb78af6dfcb131a6481e",
    ],
    "android-sdk-preview-license": [
        "84831b9409646a918e30573bab4c9c91346d8abd",
    ],
}

ARTIFACTS = [
    {
        "name": "OpenJDK 17",
        "url": "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
        "zip": os.path.join(TOOLCHAIN, "openjdk-17.zip"),
        "extract_to": TOOLCHAIN,
        "final_name": "jdk17",
        "min_mb": 100,
    },
    {
        "name": "Gradle 8.7",
        "url": "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip",
        "zip": os.path.join(TOOLCHAIN, "gradle-8.7-bin.zip"),
        "extract_to": TOOLCHAIN,
        "final_name": "gradle-8.7",
        "min_mb": 80,
    },
    {
        "name": "Android SDK cmdline-tools",
        "url": "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
        "zip": os.path.join(TOOLCHAIN, "cmdline-tools.zip"),
        "extract_to": os.path.join(SDK_ROOT, "_cmdline_tmp"),
        "final_name": None,          # 单独处理（要挪到 cmdline-tools/latest）
        "min_mb": 80,
    },
]


# ======================================================================
# 下载
# ======================================================================

def human(size):
    return "%.1f MB" % (size / 1048576.0) if size else "?"


def download(name, url, dest, min_mb=1):
    """下载到 dest；已存在且大小合理就跳过（可断点续跑）。"""
    os.makedirs(os.path.dirname(dest), exist_ok=True)

    if os.path.exists(dest) and os.path.getsize(dest) >= min_mb * 1024 * 1024:
        print("  [跳过] %s 已存在（%s）" % (name, human(os.path.getsize(dest))))
        return True

    print("  [下载] %s" % name)
    print("         %s" % url)

    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            total = int(response.headers.get("Content-Length") or 0)
            received = 0
            last_report = 0.0
            with open(dest, "wb") as handle:
                while True:
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
                    received += len(chunk)
                    now = time.time()
                    if now - last_report > 2.0:
                        last_report = now
                        speed = received / max(0.001, now - started) / 1048576.0
                        if total:
                            print("         %s / %s  (%.1f MB/s)" %
                                  (human(received), human(total), speed))
                        else:
                            print("         %s  (%.1f MB/s)" % (human(received), speed))
    except Exception as exc:                       # noqa: BLE001
        print("  [失败] %s -> %s: %s" % (name, type(exc).__name__, exc))
        if os.path.exists(dest):
            os.remove(dest)
        return False

    size = os.path.getsize(dest)
    if size < min_mb * 1024 * 1024:
        print("  [失败] %s 下载不完整（只有 %s）" % (name, human(size)))
        return False

    print("  [完成] %s  %s  用时 %.0f 秒" % (name, human(size), time.time() - started))
    return True


def unzip(zip_path, dest, name):
    print("  [解压] %s -> %s" % (name, os.path.relpath(dest, ROOT)))
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(dest)
    return True


# ======================================================================
# 主流程
# ======================================================================

def check_hosts():
    print("检查下载源可达性：")
    urls = [(a["name"], a["url"]) for a in ARTIFACTS]
    urls.append(("Maven Central",
                 "https://repo1.maven.org/maven2/com/amap/api/3dmap/10.0.600/3dmap-10.0.600.pom"))
    urls.append(("Google Maven",
                 "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.5.2/gradle-8.5.2.pom"))
    urls.append(("Gradle Plugin Portal",
                 "https://plugins.gradle.org/m2/org/jetbrains/kotlin/android/org.jetbrains.kotlin.android.gradle.plugin/2.0.20/org.jetbrains.kotlin.android.gradle.plugin-2.0.20.pom"))

    ok = True
    for name, url in urls:
        try:
            request = urllib.request.Request(url, method="HEAD",
                                             headers={"User-Agent": "Mozilla/5.0"})
            response = urllib.request.urlopen(request, timeout=25)
            print("  [OK]   %-26s HTTP %s  %s" %
                  (name, response.status, human(int(response.headers.get("Content-Length") or 0))))
        except Exception as exc:                   # noqa: BLE001
            ok = False
            print("  [FAIL] %-26s %s: %s" % (name, type(exc).__name__, exc))
    return ok


def install_sdk_packages():
    """调用 sdkmanager 装 platform-34 / build-tools / platform-tools"""
    java_home = os.path.join(TOOLCHAIN, "jdk17")
    sdkmanager = os.path.join(SDK_ROOT, "cmdline-tools", "latest", "bin", "sdkmanager.bat")

    if not os.path.exists(sdkmanager):
        print("找不到 sdkmanager：%s" % sdkmanager)
        return False
    if not os.path.exists(os.path.join(java_home, "bin", "java.exe")):
        print("找不到 JDK：%s" % java_home)
        return False

    # 先写好许可，避免 sdkmanager 卡在交互式询问上
    licenses_dir = os.path.join(SDK_ROOT, "licenses")
    os.makedirs(licenses_dir, exist_ok=True)
    for license_name, hashes in LICENSES.items():
        with open(os.path.join(licenses_dir, license_name), "w", encoding="utf-8") as handle:
            handle.write("\n".join(hashes) + "\n")
    print("  已写入 SDK 许可文件")

    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["ANDROID_HOME"] = SDK_ROOT
    env["ANDROID_SDK_ROOT"] = SDK_ROOT
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")

    print("  安装 SDK 组件：%s" % ", ".join(SDK_PACKAGES))
    result = subprocess.run(
        [sdkmanager, "--sdk_root=" + SDK_ROOT] + SDK_PACKAGES,
        env=env, capture_output=True, text=True, timeout=1800
    )
    tail = (result.stdout or "")[-1500:]
    print(tail)
    if result.returncode != 0:
        print("  sdkmanager 返回 %d" % result.returncode)
        print((result.stderr or "")[-1500:])
        return False

    print("  SDK 组件安装完成")
    return True


def main():
    parser = argparse.ArgumentParser(description="下载 Android 构建工具链到项目文件夹内")
    parser.add_argument("--check", action="store_true", help="只检查下载源")
    parser.add_argument("--sdk-packages", action="store_true", help="只装 Android SDK 组件")
    parser.add_argument("--force", action="store_true", help="重新下载")
    args = parser.parse_args()

    if args.check:
        return 0 if check_hosts() else 1

    os.makedirs(TOOLCHAIN, exist_ok=True)

    if args.sdk_packages:
        return 0 if install_sdk_packages() else 1

    if args.force:
        shutil.rmtree(TOOLCHAIN, ignore_errors=True)
        os.makedirs(TOOLCHAIN, exist_ok=True)

    print("=" * 64)
    print("工具链目录：%s" % TOOLCHAIN)
    print("=" * 64)

    for artifact in ARTIFACTS:
        name = artifact["name"]
        final_path = None
        if artifact["final_name"]:
            final_path = os.path.join(TOOLCHAIN, artifact["final_name"])

        # 已经解压好就跳过
        if final_path and os.path.isdir(final_path):
            print("  [跳过] %s 已解压" % name)
            continue

        if not download(name, artifact["url"], artifact["zip"], artifact["min_mb"]):
            return 1

        if name == "Android SDK cmdline-tools":
            # 压缩包里是 cmdline-tools/，必须放到 <sdk>/cmdline-tools/latest
            tmp = artifact["extract_to"]
            shutil.rmtree(tmp, ignore_errors=True)
            unzip(artifact["zip"], tmp, name)
            target = os.path.join(SDK_ROOT, "cmdline-tools", "latest")
            shutil.rmtree(target, ignore_errors=True)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.move(os.path.join(tmp, "cmdline-tools"), target)
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            unzip(artifact["zip"], artifact["extract_to"], name)
            # 压缩包里的顶层目录名和我们要的不一样，统一改成 final_name
            if final_path:
                candidates = [
                    entry for entry in os.listdir(TOOLCHAIN)
                    if os.path.isdir(os.path.join(TOOLCHAIN, entry))
                    and entry not in (artifact["final_name"], "android-sdk", "gradle-home")
                    and (entry.startswith("jdk") or entry.startswith("gradle")
                         or entry.startswith("openjdk"))
                ]
                if candidates and not os.path.isdir(final_path):
                    shutil.move(os.path.join(TOOLCHAIN, candidates[0]), final_path)
                    print("  重命名为 %s" % artifact["final_name"])

    # Gradle 的用户目录也放进来，避免写 C:\Users\xxx\.gradle
    os.makedirs(GRADLE_HOME, exist_ok=True)

    print()
    print("工具链就绪：")
    for path in (os.path.join(TOOLCHAIN, "jdk17"),
                 os.path.join(TOOLCHAIN, "gradle-8.7"),
                 SDK_ROOT):
        print("  %-46s %s" % (os.path.relpath(path, ROOT), "存在" if os.path.exists(path) else "缺失"))

    print()
    print("接下来安装 Android SDK 组件：")
    print("  python tools/setup_toolchain.py --sdk-packages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
