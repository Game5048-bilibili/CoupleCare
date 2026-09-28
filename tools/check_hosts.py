# -*- coding: utf-8 -*-
"""探测构建 Android APK 所需的各个下载源是否可达（只发 HEAD 请求）。"""

import urllib.request

HOSTS = [
    ("Android SDK cmdline-tools",
     "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"),
    ("Adoptium JDK 17",
     "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"),
    ("Gradle 8.7",
     "https://services.gradle.org/distributions/gradle-8.7-bin.zip"),
    ("Maven Central / AMap",
     "https://repo1.maven.org/maven2/com/amap/api/3dmap/10.0.600/3dmap-10.0.600.pom"),
    ("Google Maven / AGP 8.5.2",
     "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.5.2/gradle-8.5.2.pom"),
]

for name, url in HOSTS:
    try:
        request = urllib.request.Request(url, method="HEAD")
        response = urllib.request.urlopen(request, timeout=25)
        size = response.headers.get("Content-Length")
        print("%-24s HTTP %s  Content-Length=%s" % (name, response.status, size))
    except Exception as exc:  # noqa: BLE001
        print("%-24s 失败 -> %s: %s" % (name, type(exc).__name__, exc))
