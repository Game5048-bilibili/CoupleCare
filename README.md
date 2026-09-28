# 异地之约

## 前情提示：此项目为AI制作，这是一个异地恋想要看对方手机状态的解决方案

> 两台手机、一个配对码，互相看到对方**电量、位置、正在用什么 App**。
> 个人自用、不联网第三方服务、不走 HTTP，只有一条加密的 TCP 长连接。

| | |
|---|---|
| 应用名 | 异地之约 |
| 包名 | `net.game5048.baobao` |
| 客户端 | Kotlin + Jetpack Compose + Material 3，minSdk 26 / targetSdk 34 |
| 服务端 | Python 3（标准库 + cryptography），TCP + SQLite |
| 通信 | 裸 TCP 长连接，AES-256-GCM 加密，4 字节长度头 + JSON 帧 |
| 地图 | **高德地图 SDK**（国内直连、无需 Google 服务框架），含 WGS-84 → GCJ-02 自动纠偏 |
| 头像 | 相册选图 → 内置方形裁剪 → 256×256 JPEG，双向同步，地图上用「圆形头像 + 方向箭头」显示 |
| 图标 | 由 `logo.png` 自动生成全套 mipmap + 自适应图标（`tools/make_icons.py`） |
| 适配 | OPPO Reno / ColorOS（含自启动、后台运行、电池优化白名单引导） |
| 编译 | **已实测 `BUILD SUCCESSFUL`，产出 54.24 MB debug APK**（见 4.0 节） |

> **v1.1 更新**：地图由 OSMDroid 换成高德；新增头像上传与裁剪、地图头像+方向箭头；
> 应用图标改为使用 `logo.png`；服务端新增 `avatars` 表；定位上报新增 `bearing` 方向角。
> 协议变更见 `docs/PROTOCOL.md` 第 10 节。
>
> **v1.1 实测验证**：服务端 `test_client.py` **26/26 通过**；Android 端 **Gradle 编译通过**、
> 产出可安装 APK。编译过程中真实修复的 3 个错误：`continue` 不能写在 inline lambda 里、
> `Canvas.drawBitmap` 没有 `(Bitmap, RectF, RectF, Paint)` 重载（×2）、
> 高德 `Marker` 只有 `setIcon()` 没有 `getIcon()`（不能用 Kotlin 属性语法）。
> 另外服务端修掉一个严重 bug：**先连上的那台设备因 `partner_id` 是认证快照而永远发不出数据**。

---

## 目录

- [1. 它能做什么](#1-它能做什么)
- [2. 项目结构](#2-项目结构)
- [3. 三步跑起来](#3-三步跑起来)
- [4. 编译 Android APK](#4-编译-android-apk)
- [5. 部署服务端到 Orange Pi](#5-部署服务端到-orange-pi)
- [6. 内网穿透（frp）](#6-内网穿透frp)
- [7. 权限与保活（ColorOS 重点）](#7-权限与保活coloros-重点)
- [8. 安全说明](#8-安全说明)
- [9. 常见问题](#9-常见问题)
- [10. 耗电与流量](#10-耗电与流量)
- [11. 二次开发](#11-二次开发)

---

## 1. 它能做什么

**地图页**
- 全屏**高德地图**显示对方位置，标记是「对方头像 + 一圈方向箭头」：头像不转，箭头指向 TA 正在走的方向
- 自己也有头像标记，可开关是否显示
- 顶部卡片：对方电量环形图、是否充电、最后更新时间、当前前台应用、在线状态
- 底部信息条：数据新鲜度、定位精度、轨迹点数、**两人相距多远**
- 一键切换是否显示对方最近轨迹（Polyline 连线，最多保留 500 个点，可向服务端拉 200 条历史）

**使用记录页**
- 对方当前正在使用的 App（含持续时长，秒级刷新）
- 时间线列表：App 图标、名称、开始时间、结束时间、持续时长，按时间倒序
- 会话由 `start` / `end` 事件自动配对，对方关机或长时间无数据会自动收尾，不会一直显示“使用中”

**设置页**
- **头像**：从相册选图 → 内置方形裁剪（拖动/双指缩放）→ 保存并自动同步给对方
- 配对码、服务器地址/端口
- 上报开关：定位 / 电量 / 使用记录，各自独立
- 上报频率滑块：定位 30 秒~30 分钟、电量 1 分钟~1 小时、使用记录 10 秒~5 分钟
- 连接状态：链路状态、延迟(RTT)、双方设备 ID
- **一键跳转**到通知权限、定位权限、后台定位、使用情况访问、电池优化白名单、自启动管理

**后台**
- 前台服务常驻（带一条实时信息的常驻通知）
- 断线指数退避自动重连（2s→60s，带抖动）
- 30 秒心跳保活，90 秒无数据判定链路已死
- 开机自启
- 重连窗口内短时持有 WakeLock，连上立刻释放（**不做常驻持锁**，避免耗电）

---

## 2. 项目结构

```
couplecare/
├── README.md                    ← 你正在看的文件
├── logo.png                     ← 应用图标源图（1254×1254），改图标见 4.8 节
├── docs/
│   └── PROTOCOL.md              ← 通信协议契约（改了代码必须同步改它）
├── tools/                       ← 电脑上的小工具，不参与 APK 构建
│   ├── pnglib.py                ← 纯标准库 PNG 读写/缩放（没有 Pillow 时的替代）
│   ├── make_icons.py            ← 从 logo.png 生成全套 Android 图标
│   ├── setup_toolchain.py       ← 下载 JDK17/Gradle8.7/Android SDK 到 .toolchain/
│   ├── fetch_sdk_packages.py    ← 绕过 sdkmanager 直接下载 SDK 组件
│   └── build_apk.py             ← 用项目内工具链一键编译 APK
│
├── android/                     ← Android 客户端
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties        ← ★ 在这里填 AMAP_API_KEY
│   ├── gradle/wrapper/gradle-wrapper.properties
│   └── app/
│       ├── build.gradle.kts
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/             ← 图标（由 logo.png 生成的 PNG）、主题、字符串
│           └── java/net/game5048/baobao/
│               ├── MainActivity.kt          单 Activity + 底部导航
│               ├── BaobaoApp.kt             Application 入口（含高德隐私合规声明）
│               ├── data/
│               │   ├── model/               BatteryInfo / LocationInfo(含 bearing) / UsageInfo / PartnerState
│               │   ├── network/             CryptoUtils / Protocol / TcpClient
│               │   ├── repository/          TrackerRepository（唯一数据源，含头像收发）
│               │   └── local/               PreferencesManager（加密存储）
│               ├── service/                 TrackerService / BootReceiver / NotificationHelper
│               ├── ui/
│               │   ├── theme/               Color / Theme / Type（Material 3 动态取色）
│               │   ├── components/          BatteryCard / UsageCard / MapView(高德)
│               │   │                        AvatarCropper(裁剪对话框) / PermissionStatus
│               │   ├── screen/              MapScreen / UsageScreen / SettingsScreen
│               │   └── MainViewModel.kt
│               └── util/                    Battery/Location/UsageCollector、AvatarStore、
│                                            AvatarMarkerFactory(头像标记绘制)、AMapPrivacy、
│                                            GeoUtils(WGS84→GCJ02)、权限、时间、应用名解析
│
└── server/                      ← Python 服务端
    ├── server.py                主循环、认证、转发、历史查询、头像
    ├── database.py              SQLite（WAL + 单锁 + 自动迁移）
    ├── crypto.py                AES-GCM + PBKDF2（与客户端逐字节对齐）
    ├── config.py                所有可调参数
    ├── test_client.py           自测脚本（模拟两台手机，26 项检查，不需要真机）
    ├── requirements.txt
    └── deploy/
        ├── baobao-server.service   systemd 单元
        ├── frps.toml               公网服务器上的 frp 服务端配置
        └── frpc.toml               Orange Pi 上的 frp 客户端配置
```

---

## 3. 三步跑起来

### 第 1 步：服务端（Orange Pi）

```bash
# 装依赖
sudo apt update && sudo apt install -y python3 python3-pip python3-venv
cd /opt && sudo mkdir baobao-server && sudo chown $USER baobao-server
# 把 server/ 里的文件拷进去
cp -r server/* /opt/baobao-server/
cd /opt/baobao-server
python3 -m venv .venv && source .venv/bin/activate
pip install --upgrade pip && pip install -r requirements.txt

# 先手工跑一次，确认能起来
python3 server.py
# 看到 "服务端已启动，监听 0.0.0.0:9527" 就对了，Ctrl+C 退出
```

自测（可选但强烈建议，另开一个终端）：

```bash
cd /opt/baobao-server && source .venv/bin/activate
python3 server.py --port 19527 --db /tmp/test.db &   # 测试实例
python3 test_client.py --port 19527                  # 应输出 结果：26/26 通过
```

### 第 2 步：内网穿透

按 [第 6 节](#6-内网穿透frp) 配好 frp，让公网能访问到 Orange Pi 的 `9527`。

### 第 2.5 步：申请高德地图 Key

按 [4.7 节](#47-配置高德地图-key必做否则地图空白) 申请 Key 并填进
`android/gradle.properties`。**这一步不做，装好 APP 也看不到地图。**

### 第 3 步：装 APP 并配置

1. 两台手机都装上 APK（同一个包名、同一个配对码）
2. 打开 APP → **设置** 页：
   - 服务器地址：填 frps 的公网域名/IP
   - 端口：`9527`（或你在 frp 里映射的端口）
   - 配对码：两台手机填**一模一样**的，例如 `528520`
   - 头像：点「选择」，从相册挑一张照片，拖动/缩放裁剪成方形后确认
3. 点 **开启常驻**
4. 按 [第 7 节](#7-权限与保活coloros-重点) 把 6 项权限/保活设置全部搞定

顶部状态条变成「已连接」后，地图页几秒内就会出现对方的头像标记。

---

## 4. 编译 Android APK

### 4.0 用项目自带的工具链编译（零配置，已实测通过 ✅）

如果不想装 Android Studio，仓库里带了一套脚本，把 JDK / Gradle / Android SDK
**全部下载到项目文件夹内部**（`.toolchain/`），不碰 C 盘、不污染系统环境：

```bash
# 1) 下载 JDK 17 + Gradle 8.7 + Android SDK 命令行工具（约 620 MB）
python tools/setup_toolchain.py

# 2) 安装 Android SDK 组件：platform-34 / build-tools 34.0.0 / platform-tools
python tools/fetch_sdk_packages.py

# 3) 编译（把 key 换成你申请到的）
python tools/build_apk.py --amap-key 你的32位key
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

**实测结果**（Windows + conda Python 3.12）：

```
BUILD SUCCESSFUL in 46s
38 actionable tasks: 16 executed, 22 up-to-date

app-debug.apk   54.24 MB

package: name='net.game5048.baobao' versionCode='1' versionName='1.0.0'
sdkVersion:'26'   targetSdkVersion:'34'   compileSdkVersion='34'
application-label:'异地之约'
uses-permission: INTERNET / ACCESS_BACKGROUND_LOCATION / FOREGROUND_SERVICE
                 FOREGROUND_SERVICE_LOCATION / FOREGROUND_SERVICE_DATA_SYNC
                 RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / PACKAGE_USAGE_STATS
```

各脚本分工：

| 脚本 | 作用 |
|---|---|
| `tools/setup_toolchain.py` | 下载解压 JDK 17（华为镜像）、Gradle 8.7（腾讯镜像）、Android SDK 命令行工具 |
| `tools/fetch_sdk_packages.py` | 抓 Google 的仓库 XML，直接下载 SDK 组件 zip（绕过 sdkmanager） |
| `tools/build_apk.py` | 组装 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`/`ANDROID_USER_HOME` 再调 Gradle |
| `tools/make_icons.py` | 从 `logo.png` 生成全套图标（见 4.8） |

**两个踩过的坑**（脚本里已经处理）：
- 不要设置 `ANDROID_SDK_HOME`。AGP 会在它后面再追加一个 `/.android`，
  导致 `AndroidDirectoryCreator` 建目录失败，报
  「Could not create provider for value source AndroidBuildService.AndroidDirectoryCreator」。
  正确做法是只设 `ANDROID_USER_HOME`，并把 Gradle 的 `GRADLE_USER_HOME` 一起指到项目内。
- `sdkmanager` 在本机拉不到仓库清单（Java 侧网络受限），所以改用 Python 抓 XML，
  见 `tools/fetch_sdk_packages.py`。你在自己机器上如果 `sdkmanager` 正常，用它也一样。

不想要了整个删掉即可（自包含，约 2.2 GB，Gradle 依赖缓存占大头）：

```bash
rm -rf .toolchain android/app/build              # Linux / macOS
Remove-Item -Recurse -Force .toolchain, android\app\build    # Windows PowerShell
```

### 4.1 用 Android Studio（推荐日常开发，最省事）

1. Android Studio **Koala (2024.1) 或更新**版本
2. `File → Open`，选择 **`couplecare/android`** 目录（不是仓库根目录！）
3. 等待 Gradle Sync 完成（首次会下载依赖，约 5~10 分钟）
   - Android Studio 会自动生成缺失的 `gradlew` / `gradle/wrapper/gradle-wrapper.jar` 和 `local.properties`
4. 手机开启「开发者选项 → USB 调试」，插上数据线
5. 点绿色 ▶ 运行

> **为什么仓库里没有 `gradlew` 和 `gradle-wrapper.jar`？**
> 那是二进制文件，而且 Android Studio 打开工程时会自动补齐。如果你要用纯命令行构建，
> 见下一节。

### 4.2 用命令行构建

**方式 A：装上 Gradle 后生成 wrapper（一次即可）**

```bash
# Ubuntu / macOS
sudo apt install -y openjdk-17-jdk
# 装 Gradle 8.7：https://gradle.org/install/
cd couplecare/android
gradle wrapper --gradle-version 8.7     # 生成 gradlew、gradlew.bat、wrapper jar
./gradlew assembleDebug                 # 产物：app/build/outputs/apk/debug/app-debug.apk
```

**方式 B：直接下载 wrapper jar**

从任意一个 Gradle 8.7 工程里复制 `gradle/wrapper/gradle-wrapper.jar` 到本项目的
`android/gradle/wrapper/` 下，然后 `./gradlew assembleDebug`。

**方式 C：Windows 上**

```powershell
# 需要 JDK 17 与 Gradle 8.7
cd couplecare\android
gradle wrapper --gradle-version 8.7
.\gradlew.bat assembleDebug
```

### 4.3 环境要求

| 组件 | 版本 |
|---|---|
| JDK | **17**（不能用 JDK 8/11，AGP 8.x 强制要求 17） |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 |
| Android SDK Platform | 34（`compileSdk`/`targetSdk`） |
| Build Tools | 34.0.0 |

如果 `local.properties` 缺失，命令行构建需要在环境变量里指定 SDK：

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # Linux
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

### 4.4 装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> debug 包与 release 包的 applicationId **完全相同**（都是 `net.game5048.baobao`），
> 所以两者不能同时安装在一台手机上——这是刻意的：保证两台手机无论装哪种包，
> 行为、包名、权限表现都完全一致。

### 4.5 打正式签名包（可选）

```bash
keytool -genkey -v -keystore baobao.jks -keyalg RSA -keysize 2048 -validity 10000 -alias baobao
```

在 `app/build.gradle.kts` 的 `android { }` 中加：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../baobao.jks")
        storePassword = "你的密码"
        keyAlias = "baobao"
        keyPassword = "你的密码"
    }
}
buildTypes {
    release { signingConfig = signingConfigs.getByName("release") }
}
```

然后 `./gradlew assembleRelease`。

### 4.6 依赖说明

| 依赖 | 用途 | 备注 |
|---|---|---|
| `androidx.compose.material3` | UI | 通过 Compose BOM `2024.09.02` 统一版本 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | MVVM | |
| `com.amap.api:3dmap:10.0.600` | 地图 | **高德地图**，国内直连、无需 Google 服务框架；需要 API Key（见 4.7） |
| `com.google.android.gms:play-services-location:21.0.1` | 定位 | 优先用 FusedLocationProvider；**国行无 GMS 时会自动降级到系统 LocationManager** |
| `androidx.security:security-crypto:1.1.0-alpha06` | 本地加密存储 | 加密配对码与设备 ID |
| `kotlinx-coroutines-android` | 协程 | 没有 Retrofit/OkHttp，网络用原生 Socket |

> **高德拉不到依赖？** 版本号可能已经更新。去高德官方文档查最新版本：
> <https://lbs.amap.com/api/android-sdk/guide/create-project/android-studio-create-project>
> 把 `app/build.gradle.kts` 里的 `10.0.600` 换成文档里的版本即可，其余代码不用动。

### 4.7 配置高德地图 Key（必做，否则地图空白）

高德要求 Key 与「**应用包名 + 签名 SHA1**」绑定，所以必须先从你的签名里取出 SHA1。

**第 1 步：取 SHA1**

方式 A（推荐，Android Studio）：
```
右侧 Gradle 面板 → android → Tasks → android → signingReport（双击）
在 Run 窗口里找 debug 或 release 下的 SHA1，形如：
SHA1: 1A:2B:3C:...
```

方式 B（命令行，用你自己的 keystore）：
```bash
keytool -list -v -keystore 你的.jks -alias 你的别名
# debug 包的默认 keystore（Windows，密码固定是 android）：
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

**第 2 步：申请 Key**

1. 打开 <https://console.amap.com/dev/key/app>（需注册并实名认证，个人开发者即可）
2. 「创建新应用」→ 应用名随便填，比如 `异地之约`
3. 「添加 Key」：
   - Key 名称：`baobao`
   - 服务平台：**Android 平台**
   - 发布版安全码 SHA1：填第 1 步取到的 SHA1
   - 调试版安全码 SHA1：填 debug keystore 的 SHA1（和发布版可以填同一个，方便调试）
   - **PackageName：`net.game5048.baobao`** ← 必须一字不差
4. 复制生成的 Key（32 位十六进制字符串）

**第 3 步：填入工程**

编辑 `android/gradle.properties`：

```properties
AMAP_API_KEY=你申请到的32位key
```

也可以不改文件，用命令行传入（适合 CI）：

```bash
./gradlew assembleDebug -PAMAP_API_KEY=你的key
```

**第 4 步：验证**

重新编译安装后打开地图页。如果顶部出现红色「地图还没配置高德 Key」提示条，
说明 Key 没读到；如果地图区域是网格状空白或右下角有 `INVALID_USER_KEY` 水印，
说明 Key 与包名/SHA1 不匹配 —— 回控制台核对这三项。

> 高德 SDK 会在启动时收集设备信息用于地图渲染，这是它作为第三方 SDK 的固有行为。
> 本项目在 `BaobaoApp.onCreate` 里按官方要求调用了隐私合规声明
> （`AMapPrivacy.ensure`），并且**只把 SDK 用于地图显示**，与你们之间的加密通道无关。

### 4.8 更换应用图标

图标不是手动画的矢量图，而是由根目录的 `logo.png` 自动生成的一整套 PNG。
换图标只需要替换 `logo.png` 再跑一次脚本。

```bash
# 先看看 logo 的内容范围，脚本会告诉你适合哪种版式
python tools/make_icons.py --analyze

# 生成全部图标（正方形 / 圆形 / 自适应前景 / 自适应背景）
python tools/make_icons.py
```

生成内容：

| 文件 | 说明 |
|---|---|
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 普通方形图标（48/72/96/144/192 px） |
| `mipmap-*/ic_launcher_round.png` | 圆形图标（老启动器用） |
| `mipmap-*/ic_launcher_foreground.png` | 自适应图标前景层 |
| `mipmap-*/ic_launcher_background.png` | 自适应图标背景层（满幅版式） |
| `mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标描述 |

**版式说明**：脚本会自动判断。当前 `logo.png` 是「满幅渐变底 + 白色线稿」，
插画一直延伸到边缘，所以选了 **`bleed`（满幅出血）** 版式 —— 保住原图观感，
代价是启动器用圆形遮罩时会裁掉四角。如果换成居中构图的新 logo，
脚本会自动切到 `fit` 版式（把图缩小放进安全区，绝不裁切）。
也可以手动指定：`python tools/make_icons.py --layout fit`。

> 脚本只用 Python 标准库（自带的 `zlib` + 手写 PNG 编解码），**不需要装 Pillow**。
> 换 logo 后如果发现主体被裁，先用 `--analyze` 看脚本给出的主体包围盒和 ASCII 示意图。

---

## 5. 部署服务端到 Orange Pi

### 5.1 安装

```bash
sudo apt update
sudo apt install -y python3 python3-pip python3-venv

sudo mkdir -p /opt/baobao-server
sudo chown -R $USER:$USER /opt/baobao-server
cp -r server/* /opt/baobao-server/

cd /opt/baobao-server
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

# 冒烟测试
python3 server.py --port 19527 --db ./data/smoke.db &
sleep 1
python3 test_client.py --port 19527
kill %1
```

看到 **`结果：26/26 通过`** 就说明认证、加密、转发、存库、历史查询、头像收发、
方向角透传、心跳、越权拒绝全部正常。

> Orange Pi 是 ARM64，`cryptography` 有官方预编译 wheel，`pip install` 一般几秒钟就好。
> 如果 pip 版本太老去尝试源码编译（需要 Rust），执行 `pip install --upgrade pip` 再装。

### 5.2 配置

编辑 `/opt/baobao-server/config.py`：

```python
PORT = 9527                      # 想换端口就改这里，APP 里也要同步改
DB_PATH = ".../data/baobao.db"
HISTORY_RETENTION_DAYS = 30      # 历史保留天数，0 = 永久保留
LOG_LEVEL = "INFO"               # 排查问题时改 "DEBUG"
SOCKET_TIMEOUT = 120             # 客户端 30 秒一次心跳，这里给 3 倍余量
```

### 5.3 用 systemd 常驻

```bash
sudo useradd -r -s /usr/sbin/nologin baobao          # 专用低权限用户（可选）
sudo chown -R baobao:baobao /opt/baobao-server

sudo cp /opt/baobao-server/deploy/baobao-server.service /etc/systemd/system/
sudo nano /etc/systemd/system/baobao-server.service   # 按实际路径/用户名改
sudo systemctl daemon-reload
sudo systemctl enable --now baobao-server

systemctl status baobao-server
journalctl -u baobao-server -f                        # 实时看日志
```

### 5.4 防火墙

```bash
sudo ufw allow 9527/tcp          # 服务端端口（如果直连公网）
sudo ufw allow 7000/tcp          # frp 控制端口
sudo ufw allow 7500/tcp          # frp 管理面板（建议只对内网开放）
sudo ufw enable
```

### 5.5 运维命令

```bash
python3 server.py --stats                      # 各表行数
sqlite3 data/baobao.db ".tables"               # 看表
sqlite3 data/baobao.db "SELECT device_id, datetime(last_seen/1000,'unixepoch','localtime') FROM devices;"
ss -lntp | grep 9527                           # 端口是否在监听
```

**注意**：`devices` 表里的 `pair_code_hash` 存的是 `sha256(配对码)`，不是明文。
这是刻意的设计——配对码就是加密密钥的种子，落库等于泄露密钥。群组识别用哈希完全够用。

---

## 6. 内网穿透（frp）

Orange Pi 没有公网 IP，用 frp 把 TCP 端口打出去。需要一个有公网 IP 的 VPS 做中转。

### 6.1 公网服务器（frps）

```bash
# 下载对应架构的 frp
wget https://github.com/fatedier/frp/releases/download/v0.58.1/frp_0.58.1_linux_amd64.tar.gz
tar -zxvf frp_0.58.1_linux_amd64.tar.gz
sudo mv frp_0.58.1_linux_amd64 /usr/local/frp

sudo cp /usr/local/frp/frps /usr/local/bin/
sudo mkdir -p /etc/frp
sudo cp <项目>/server/deploy/frps.toml /etc/frp/frps.toml
sudo nano /etc/frp/frps.toml       # 改 token、管理面板密码
```

最小可用配置（`/etc/frp/frps.toml`）：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "换成一串长随机字符"
```

systemd 单元 `/etc/systemd/system/frps.service`：

```ini
[Unit]
Description=frp server
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now frps
```

### 6.2 Orange Pi（frpc）

```bash
# 注意选 arm64 版本！
wget https://github.com/fatedier/frp/releases/download/v0.58.1/frp_0.58.1_linux_arm64.tar.gz
tar -zxvf frp_0.58.1_linux_arm64.tar.gz
sudo cp frp_0.58.1_linux_arm64/frpc /usr/local/bin/
sudo mkdir -p /etc/frp
sudo cp <项目>/server/deploy/frpc.toml /etc/frp/frpc.toml
```

`/etc/frp/frpc.toml`：

```toml
serverAddr = "你的公网服务器IP或域名"
serverPort = 7000
auth.method = "token"
auth.token = "与 frps.toml 完全一致"
transport.tls.enable = true       # ★ 建议开启，加密 frpc<->frps 这一段

[[proxies]]
name = "baobao-tcp"
type = "tcp"
localIP = "127.0.0.1"
localPort = 9527
remotePort = 9527
```

systemd 单元 `/etc/systemd/system/frpc.service`：

```ini
[Unit]
Description=frp client
After=network-online.target baobao-server.service
Wants=network-online.target
After=baobao-server.service

[Service]
Type=simple
ExecStart=/usr/local/bin/frpc -c /etc/frp/frpc.toml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now frpc
sudo systemctl status frpc
```

### 6.3 验证穿透是否成功

在**任意一台能上公网的机器**上：

```bash
telnet 你的公网服务器IP 9527
# 或
nc -vz 你的公网服务器IP 9527
```

能连上就说明穿透成功。然后把 `你的公网服务器IP` 和 `9527` 填进 APP 设置页。

### 6.4 内网穿透注意事项

1. **只有 TCP**。APP 与服务器之间是裸 TCP 长连接，不涉及 HTTP，所以穿透只需映射一个 TCP 端口。
2. **一定要开 TLS**（`transport.tls.enable = true`）。配对码在 `auth` 帧里是明文传输的
   （这是协议设计的必然，见 `docs/PROTOCOL.md` 4.1 节），开启 TLS 可以保护这一跳。
3. 部分运营商会封常见端口。如果 9527 连不上，换成 `19527` 之类的高位端口（frpc 的
   `remotePort` 和 APP 里的端口要一起改）。
4. 用免费内网穿透服务（如某些花生壳/ngrok 类）时要注意：它们通常只映射 HTTP，
   或者给的 TCP 端口是随机的且会变。**推荐自建 frp**，最稳定。
5. 断线重连：frpc 会自己重连，APP 也会自己重连（指数退避），所以网络抖动基本无感。

---

## 7. 权限与保活（ColorOS 重点）

ColorOS 是国内对后台管控最严的系统之一。**这 6 项全部做完**，APP 才能稳定常驻。

### 7.1 APP 内直接跳转

打开 APP → **设置** 页 → 「权限与保活」卡片，每一项右边都有「去开启」按钮，
会直接跳到对应的系统页面。绿色勾 = 已完成。

### 7.2 逐项说明

| # | 项目 | 路径 | 说明 |
|---|---|---|---|
| 1 | **通知权限** | 设置页 → 通知 → 允许 | Android 13+ 才有。没授权时服务仍在跑，但常驻通知看不到 |
| 2 | **定位权限** | 设置页 → 权限 → 位置信息 → **始终允许** | 只选「仅在使用时允许」的话，锁屏后位置就断了 |
| 3 | **后台定位** | 同上，必须选 **始终允许** | Android 11+ 不能弹窗申请，只能手动去设置里改 |
| 4 | **使用情况访问权限** | 设置 → 应用 → 特殊应用权限 → 使用情况访问 → 允许「异地之约」 | 打开后**对方才能看到你在用什么 App** |
| 5 | **电池优化白名单** | 电池 → 更多电池设置 → 电池优化 → 异地之约 → **不优化** | 不加白名单，息屏约 30 分钟后会被冻结 |
| 6 | **自启动 + 关联启动 + 后台运行** | 手机管家 → 权限隐私 → 自启动管理 | ColorOS 特有的三件套，**都要允许** |

### 7.3 ColorOS 详细操作路径

**① 使用情况访问权限**（最容易漏，也最关键）

```
设置 → 应用 → 应用管理 → 右上角「⋮」→ 特殊应用权限 → 使用情况访问 → 异地之约 → 允许
```
或直接在 APP 内点「使用情况访问权限 → 去开启」。

**② 自启动 / 关联启动 / 后台运行**

```
手机管家 → 权限隐私 → 自启动管理 → 找到「异地之约」→ 打开
手机管家 → 权限隐私 → 关联启动 → 找到「异地之约」→ 打开
手机管家 → 权限隐私 → 后台运行管理 → 找到「异地之约」→ 允许后台运行
```

不同 ColorOS 版本菜单位置略有差异（有的在 `设置 → 电池 → 应用耗电管理`），
APP 里的「自启动 / 后台运行」按钮会尝试打开最可能的页面，打不开时会退回应用详情页。

**③ 电池优化白名单**

```
设置 → 电池 → 更多电池设置 → 电池优化 → 所有应用 → 异地之约 → 不优化
```
APP 里的按钮会用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹出系统确认框，点「允许」即可。

**④ 锁定后台（可选但很有效）**

```
打开最近任务 → 下拉「异地之约」卡片 → 出现小锁图标（已锁定，不会被一键清理）
```

**⑤ 关闭「智能省电」对本应用的限制（可选）**

```
设置 → 电池 → 智能省电 → 关闭，或把「异地之约」加入例外
```

### 7.4 保活原理（为什么这样做）

- **前台服务 + 常驻通知**：Android 8+ 规定前台服务不会被普通内存回收杀掉。
  通知的 `foregroundServiceType` 声明为 `location|dataSync`，服务会按当前
  **实际已授予的权限**动态挑选类型，避免 Android 14 抛 `SecurityException`。
- **START_STICKY + AlarmManager 兜底**：被系统杀掉后，`onTaskRemoved`/`onDestroy`
  会用 `AlarmManager.set()` 预约一次重启（非精确闹钟，**不需要**
  `SCHEDULE_EXACT_ALARM` 这种敏感权限）。
- **不常驻 WakeLock**：只在“断线重连中”这个短窗口内持锁（最多 5 分钟），
  一连上立刻释放。这样既保证重连成功率，又不会让 CPU 一直醒着耗电。
- **30 秒心跳**：既是保活，也是链路健康检测（90 秒收不到任何字节就重连）。
- **前台服务必须从可见状态启动**：所以开机自启走 `BOOT_COMPLETED`（系统豁免），
  其余情况请先手动打开一次 APP。

### 7.5 如果你就是收不到数据，按顺序排查

1. APP 顶部状态是不是「已连接」？不是 → 检查服务器地址/端口/穿透
2. 设置页「常驻」是不是开着？没开 → 点「开启常驻」
3. 两台手机的**配对码是否完全一致**？（大小写、空格都算）→ 改完会自动重连
4. 设置页里 6 项权限是不是全绿？
5. 服务端 `journalctl -u baobao-server -f` 有没有 `认证成功` 的日志？
6. 让对方切一次前台应用，等 30 秒看「使用记录」页有没有新条目

---

## 8. 安全说明

| 项目 | 做法 |
|---|---|
| 加密算法 | AES-256-GCM（`AES/GCM/NoPadding`），认证标签 128 bit |
| 密钥派生 | PBKDF2-HMAC-SHA256，盐 `yuanzhiyue_salt`，**10000 次迭代**，输出 32 字节 |
| IV | 每帧随机 12 字节，**绝不复用**（GCM 复用 IV 是致命错误） |
| 密文格式 | `Base64( IV[12] ‖ CipherText ‖ Tag[16] )` |
| 明文暴露面 | 只有信封的 `type` / `device_id` / `from`，以及 `auth` 帧的 `pair_code` |
| 配对码落库 | **不存明文**，只存 `sha256(配对码)` 作为群组标识 |
| 本地存储 | 配对码、设备 ID 用 `EncryptedSharedPreferences`（Android Keystore 主密钥） |
| 备份 | `backup_rules.xml` 明确排除含配对码的偏好文件，禁止云备份/ADB 备份 |

**`auth` 帧的配对码为什么是明文？**
密钥由配对码派生，服务端在收到第一条消息前不知道配对码，就没有密钥去解密 payload
（鸡生蛋问题）。服务端拿到明文配对码 → 派生密钥 → 解密 auth 的密文 payload，
解密成功即证明客户端确实持有该密钥。详见 `docs/PROTOCOL.md` 第 4.1 节。

**降低风险的做法**：
1. **开启 frp 的 TLS**（`transport.tls.enable = true`），让「手机→公网服务器」这一段也加密
2. 配对码当密码用，**8 位以上**随机数字，别用 `1234`、`520520` 这类
3. 两台手机之间不要用公共 WiFi 传输配对码，用微信/口头告诉对方

---

## 9. 常见问题

**Q：地图一片空白 / 显示 INVALID_USER_KEY？**
A：高德 Key 没配好。检查三件事：① `android/gradle.properties` 里 `AMAP_API_KEY` 是否填了；
② Key 绑定的包名是否**恰好**是 `net.game5048.baobao`；
③ SHA1 是否和你实际签名的一致（debug 用 debug.keystore 的 SHA1，release 用你 jks 的）。
详见 [4.7 节](#47-配置高德地图-key必做否则地图空白)。

**Q：对方的位置在地图上偏了几百米？**
A：不应该发生。高德用 GCJ-02，手机定位给的是 WGS-84，本项目在**绘制前**统一做了
`GeoUtils.wgs84ToGcj02` 纠偏，协议里传的始终是原始 WGS-84。
如果你改过 `PartnerMap` 里的 `toGcj()`（比如以为可以直接传原坐标），就会偏几百米。
另外：**国内**偏移几百米是坐标系问题，**国外**坐标不做偏移（脚本里有 `outOfChina` 判断）。

**Q：地图上对方只有头像没有箭头？**
A：箭头表示行进方向，需要对方在移动中才能算出来。静止时 `bearing = -1`（未知），
此时箭头归到正北且环变淡。让对方走几步（位移超过 12 米）就会转起来。

**Q：头像换了但对方那边没变？**
A：头像走的是 `avatar` 消息，需要连接正常。换完后如果对方显示的是旧头像，
让对方在设置页点一次「刷新历史」，或者等对方重新连上（认证成功时会自动拉一次头像）。

**Q：国行 OPPO / ColorOS 没有 Google 服务框架，定位还能用吗？**
A：**能用。** `LocationCollector` 做了双引擎自动降级：
启动时探测一次 GMS，有就用 `FusedLocationProvider`（最省电），
没有就自动切到系统原生 `LocationManager`（同时监听 GPS + 网络定位），
完全不需要任何 Google 组件。日志里会打印当前用的是哪个引擎：
`adb logcat -s LocationCollector` → 看到「系统 LocationManager（无 Google 服务）」即为降级模式。
降级模式下室内定位会慢一些（纯网络定位依赖 WiFi/基站数据库），这是系统能力差异，不是 APP 的问题。

**Q：设置页显示「已连接」，但地图上没有对方位置？**
A：对方可能没开「上报定位」开关，或者对方的定位权限只有「仅在使用时允许」（锁屏就断）。
让对方在设置页检查。

**Q：使用记录里对方一直是同一个 App，不动了？**
A：两种情况：① 对方真的没切应用；② 对方的「使用情况访问权限」没开。
在对方手机上打开 APP 的「使用记录」页，如果顶部有红色提示条，就是没开权限。

**Q：对方电量显示「等待对方数据」？**
A：对方从没成功上报过电量。让对方的 APP 打开一次，并确认「开启常驻」是打开的。

**Q：修改配对码后两台手机都连不上？**
A：改配对码会让密钥变化，需要**两端都改成一样**。改完 APP 会自动重连并重新认证。

**Q：换了手机 / 重装了 APP，之前的记录还在吗？**
A：服务端保留 30 天（`HISTORY_RETENTION_DAYS`）。新装的 APP 认证成功后会
自动拉取对方最近 200 条历史，地图轨迹和使用记录会立刻补上。

**Q：服务端日志里一直刷「读超时，断开」？**
A：正常现象。手机切到无网络环境或息屏过久时会断开，客户端会自动重连。
如果**一直**连不上，检查 frp 隧道和防火墙。

**Q：两个人都想装，但一个用 ColorOS 一个用 MIUI/鸿蒙？**
A：代码里已经内置了 vivo / 小米 / 华为的自启动页面跳转（`PermissionUtils.openAutoStartSettings`），
只是没有 ColorOS 那么详尽，但功能完全一样。

**Q：可以三个以上设备互相看吗？**
A：当前协议是**一对一**（同一个配对码下取最早注册的那台作为伙伴）。
要支持多设备需要改 `database.py` 的 `register_device` 和 `server.py` 的转发逻辑。

---

## 10. 耗电与流量

**默认配置下的量级**（定位 3 分钟 / 电量 5 分钟 / 使用记录 30 秒）：

| 项目 | 估算 |
|---|---|
| 流量 | 每天约 0.5~2 MB（一帧几十到几百字节，一天几百帧） |
| 电量 | 约占全天耗电的 3%~8%，**低于微信后台** |
| 内存 | 前台服务约 40~70 MB（主要在地图） |

**耗电主要来自定位**。想更省电：
- 把定位间隔调到 10 分钟以上，或走路时直接关掉「上报定位」开关
- 使用记录轮询调到 60 秒
- 电量间隔调到 30 分钟（电量变化时仍会立刻上报，只是兜底周期变长）

「设置」页的滑块就是为这个准备的，可以按自己的接受度权衡。

---

## 11. 二次开发

**改协议**：先改 `docs/PROTOCOL.md`，然后**同时**改
`android/.../data/network/Protocol.kt` 和 `server/server.py` 里的常量与处理分支。
改完跑一遍 `python3 test_client.py` 做回归。

**改 UI 配色**：`ui/theme/Color.kt`。默认会跟随壁纸动态取色（Material You），
不想要的话把 `BaobaoTheme(dynamicColor = false)`。

**改一种上报类型**（比如「屏幕是否点亮」）：
1. `data/model/` 加数据类 + `toJson/fromJson`
2. `Protocol.kt` 加 `TYPE_XXX` 常量
3. `server/server.py` 加 `TYPE_XXX` 常量、`_handle_report` 分支、`database.py` 加表和读写
4. `util/` 加 Collector，在 `TrackerService.startCollectors()` 里启动
5. `TrackerRepository.handleEnvelope` 加分支，`PartnerState` 加字段，UI 里渲染

**换地图（比如换成百度）**：
1. `app/build.gradle.kts` 换依赖，Manifest 换 Key 的 meta-data
2. 重写 `ui/components/MapView.kt`（对外只暴露 `PartnerMap` 这一个 Composable，
   调用方 `MapScreen` 不用动）
3. 坐标转换：百度要 WGS-84 → BD-09，在 `GeoUtils` 里加一个 `wgs84ToBd09`，
   改 `PartnerMap` 里的 `toGcj()` 调用即可。**协议和服务端完全不用改**，
   因为它们存的始终是 WGS-84 —— 这正是当初把纠偏放在渲染前的原因。

**调地图标记的样子**：`util/AvatarMarkerFactory.kt`
- `AVATAR_FILL` 控制头像在圆里的大小
- `RING_SCALE` 控制方向环相对头像的放大倍数
- `buildHeadingRing` 里的 `arrowHeight` / `arrowHalfWidth` 控制箭头形状

**改服务端端口**：`server/config.py` 的 `PORT`，同步改 frp 的 `localPort/remotePort`
和 APP 设置页的端口。

---

## 附：默认参数速查

| 参数 | 默认值 | 位置 |
|---|---|---|
| 服务端监听端口 | 9527 | `server/config.py` → `PORT` |
| 定位上报间隔 | 180 秒 | `PreferencesManager.DEFAULT_LOCATION_INTERVAL_SEC` |
| 电量上报间隔 | 300 秒 | `PreferencesManager.DEFAULT_BATTERY_INTERVAL_SEC` |
| 使用记录轮询 | 30 秒 | `PreferencesManager.DEFAULT_USAGE_POLL_SEC` |
| 心跳间隔 | 30 秒 | `Protocol.HEARTBEAT_INTERVAL_MS` |
| 读超时 | 90 秒 | `Protocol.READ_TIMEOUT_MS` |
| 单帧上限 | 1 MiB | `Protocol.MAX_FRAME_BYTES` / `config.MAX_PAYLOAD` |
| 轨迹保留点数 | 500 | `PartnerState.MAX_TRACK_POINTS` |
| 历史拉取条数 | 200 | `TrackerRepository.HISTORY_LIMIT` |
| 历史保留天数 | 30 天 | `server/config.py` → `HISTORY_RETENTION_DAYS` |
| 头像尺寸 / 质量 | 256×256 / JPEG 85 | `AvatarStore.AVATAR_SIZE` / `JPEG_QUALITY` |
| 头像大小上限 | 512 KiB | `server/config.py` → `MAX_AVATAR_BYTES` |
| 方向角最小位移 | 12 米 | `LocationCollector.MIN_BEARING_DISTANCE_M` |
| PBKDF2 迭代 | 10000 | 两端都必须一致 |

---

## 12. 服务端数据说明

SQLite 文件默认在 `/opt/baobao-server/data/baobao.db`，共 5 张表：

| 表 | 内容 | 清理策略 |
|---|---|---|
| `devices` | 设备与配对关系（`pair_code_hash` 是 sha256，不存明文） | 永久 |
| `battery_reports` | 电量历史 | 超过 `HISTORY_RETENTION_DAYS` 自动删 |
| `location_reports` | 定位历史（含 `bearing`） | 同上 |
| `usage_reports` | 前台应用 start/end 事件 | 同上 |
| `avatars` | 每人**最新一张**头像（BLOB） | 永久（是状态不是历史） |

常用查询：

```bash
sqlite3 data/baobao.db "SELECT device_id, partner_id FROM devices;"
sqlite3 data/baobao.db "SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM location_reports;"
sqlite3 data/baobao.db "SELECT device_id, length(data) FROM avatars;"     # 头像大小
```

备份只需要拷贝 `data/baobao.db`（连同 `-wal`/`-shm`，或先 `systemctl stop baobao-server`）。

---

祝你们天天都能看到对方的电量还是 100%。💗
