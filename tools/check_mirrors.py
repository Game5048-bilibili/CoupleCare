# -*- coding: utf-8 -*-
"""探测国内镜像上的 JDK 17 与 Gradle 8.7（HEAD 请求，不下载）。"""

import urllib.request

CANDIDATES = [
    ("清华 Adoptium 目录",
     "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/"),
    ("华为 OpenJDK 17",
     "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip"),
    ("腾讯 Gradle 8.7",
     "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip"),
    ("腾讯 Gradle 8.9",
     "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"),
    ("阿里 Gradle 8.7",
     "https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.7-bin.zip"),
    ("清华 Gradle",
     "https://mirrors.tuna.tsinghua.edu.cn/gradle/gradle-8.7-bin.zip"),
    ("Azul Zulu 17",
     "https://cdn.azul.com/zulu/bin/zulu17.54.21-ca-jdk17.0.13-win_x64.zip"),
]

for name, url in CANDIDATES:
    try:
        request = urllib.request.Request(url, method="HEAD",
                                         headers={"User-Agent": "Mozilla/5.0"})
        response = urllib.request.urlopen(request, timeout=25)
        size = response.headers.get("Content-Length")
        mb = "%.1f MB" % (int(size) / 1048576) if size else "?"
        print("%-20s HTTP %s  %s" % (name, response.status, mb))
    except Exception as exc:  # noqa: BLE001
        print("%-20s 失败 -> %s: %s" % (name, type(exc).__name__, exc))
