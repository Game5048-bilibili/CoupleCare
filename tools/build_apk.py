#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用项目文件夹内的工具链编译 Android APK（不依赖 C 盘上的任何东西）。

前置：先跑 python tools/setup_toolchain.py 和 --sdk-packages

用法：
    python tools/build_apk.py                 # 编译 debug APK
    python tools/build_apk.py --release       # 编译 release APK（未签名）
    python tools/build_apk.py --clean         # 先 clean 再编译
    python tools/build_apk.py --task compileDebugKotlin   # 只做 Kotlin 编译检查（最快）
"""

import argparse
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLCHAIN = os.path.join(ROOT, ".toolchain")
JDK = os.path.join(TOOLCHAIN, "jdk17")
GRADLE = os.path.join(TOOLCHAIN, "gradle-8.7", "bin", "gradle.bat")
GRADLE_HOME = os.path.join(TOOLCHAIN, "gradle-home")
SDK = os.path.join(TOOLCHAIN, "android-sdk")
ANDROID_DIR = os.path.join(ROOT, "android")


def main():
    parser = argparse.ArgumentParser(description="编译异地之约 APK")
    parser.add_argument("--release", action="store_true", help="编译 release")
    parser.add_argument("--clean", action="store_true", help="先执行 clean")
    parser.add_argument("--task", default=None, help="自定义 Gradle 任务")
    parser.add_argument("--amap-key", default=None, help="临时指定高德 Key（不写入文件）")
    args = parser.parse_args()

    # ---------------- 前置检查 ----------------
    problems = []
    if not os.path.exists(os.path.join(JDK, "bin", "java.exe")):
        problems.append("缺少 JDK 17：%s" % JDK)
    if not os.path.exists(GRADLE):
        problems.append("缺少 Gradle 8.7：%s" % GRADLE)
    if not os.path.isdir(os.path.join(SDK, "platforms", "android-34")):
        problems.append("缺少 Android SDK platform-34：%s" % SDK)
    if problems:
        for problem in problems:
            print("[缺少] " + problem)
        print("\n先运行：python tools/setup_toolchain.py && python tools/setup_toolchain.py --sdk-packages")
        return 1

    # ---------------- 环境变量 ----------------
    android_user_home = os.path.join(TOOLCHAIN, "android-home")
    os.makedirs(android_user_home, exist_ok=True)

    env = dict(os.environ)
    env["JAVA_HOME"] = JDK
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    # AGP 默认往 C:\Users\<用户>\.android 写 analytics.settings / debug.keystore，
    # 这里指向项目内的目录，保证整个过程不碰 C 盘。
    # 注意：**不要**设置 ANDROID_SDK_HOME —— AGP 会在它后面再追加一个 /.android，
    # 导致 AndroidDirectoryCreator 建目录失败（"Could not create provider for value source"）。
    env["ANDROID_USER_HOME"] = android_user_home
    env.pop("ANDROID_SDK_HOME", None)
    # 关键：Gradle 默认把依赖缓存写到 C:\Users\xxx\.gradle，这里改到项目里
    env["GRADLE_USER_HOME"] = GRADLE_HOME
    env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
    # 关掉文件系统监视：沙箱里 Gradle 的 native watcher 拿不到线程句柄
    # （"Couldn't open current thread, error = 5"），关掉不影响构建正确性
    env["GRADLE_OPTS"] = (
        (env.get("GRADLE_OPTS") or "") + " -Dorg.gradle.vfs.watch=false"
    ).strip()

    # ---------------- local.properties ----------------
    # Android Gradle Plugin 靠它找 SDK；路径里的反斜杠在 properties 里要转义
    local_properties = os.path.join(ANDROID_DIR, "local.properties")
    with open(local_properties, "w", encoding="utf-8") as handle:
        handle.write("# 由 tools/build_apk.py 自动生成\n")
        handle.write("sdk.dir=%s\n" % SDK.replace("\\", "\\\\"))
    print("已写入 %s" % os.path.relpath(local_properties, ROOT))

    # ---------------- 组装命令 ----------------
    task = args.task or ("assembleRelease" if args.release else "assembleDebug")
    command = [GRADLE, "--no-daemon", "--console=plain", task]

    if args.clean:
        command.insert(3, "clean")

    if args.amap_key:
        command.append("-PAMAP_API_KEY=" + args.amap_key)

    print("=" * 70)
    print("JAVA_HOME        = %s" % JDK)
    print("GRADLE_USER_HOME = %s" % GRADLE_HOME)
    print("ANDROID_HOME     = %s" % SDK)
    print("任务             = %s" % task)
    print("=" * 70)

    result = subprocess.run(command, cwd=ANDROID_DIR, env=env)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
