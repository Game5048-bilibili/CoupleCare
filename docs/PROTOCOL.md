# 异地之约 · 通信协议 v1

> 本文件是 Android 客户端（`data/network/Protocol.kt`、`CryptoUtils.kt`）与
> Python 服务端（`server/server.py`、`server/crypto.py`）共同遵循的唯一契约。
> 任何一端修改，另一端必须同步修改。

---

## 1. 传输层

- 纯 TCP，长连接，**不是** HTTP。
- 服务端默认监听 `0.0.0.0:9527`（`server/config.py` 可改）。
- 客户端连接后必须尽快发送 `auth`，否则服务端主动断开（默认 `AUTH_TIMEOUT = 10` 秒，见 `server/config.py`）。
- 一个 `device_id` 只保留最新一条连接（重复登录会踢掉旧连接）。

## 2. 帧格式（Framing）

```
+----------------+----------------------------+
| 4 字节大端 uint32 |  UTF-8 JSON 字节流 (len)     |
+----------------+----------------------------+
```

- `len` = JSON 字节数（不含 4 字节头），最大 `1 MiB`（`MAX_PAYLOAD`）。
- JSON 必须紧凑（无换行），UTF-8 编码，中文直接放（`app_label` 会有中文名）。
- 收到的 `len > MAX_PAYLOAD` 视为攻击/异常，服务端直接断开。

## 3. JSON 信封（Envelope）

```jsonc
{
  "type":      "battery",       // 必填，消息类型
  "device_id": "a1b2c3...",     // 客户端 -> 服务端必填：发送方设备 ID（明文）
  "from":      "a1b2c3...",     // 服务端 -> 客户端必填：数据来源设备 ID（明文）
  "ts":        1730000000000,   // 毫秒时间戳（信封级，用于排序/调试）
  "payload":   "q1Jv...=="      // 业务体：AES-GCM 密文 Base64（见第 4 节）
}
```

设计原理：
- **只有路由必需字段明文**（`type` / `device_id` / `from`）——服务端要在不解密的情况下
  知道“谁发的、转发给谁”，且服务端转发前需要按连接查找 partner，明文可省一次解析。
- **所有业务数据一律进 `payload` 密文**，包括 `pair_code`。所以抓包/中间人只能看到
  设备 ID 和消息类型，看不到电量、坐标、应用名。
- 少数控制消息允许在信封里冗余一个明文字段（如 `auth_ok.partner_device_id`），
  仅为方便调试；客户端一律以**解密后的内容**为准。

### 4.1 唯一的例外：`auth` 帧必须明文带 `pair_code`

密钥是**由配对码派生**的，服务端在收到第一条消息前并不知道配对码，
因此它没有任何密钥可以解密 `auth` 的 payload —— 这是鸡生蛋问题。

所以 `auth` 帧的 `pair_code` 放在**信封明文字段**里：

```jsonc
{
  "type": "auth",
  "device_id": "a1b2...",          // 明文，用于路由
  "pair_code": "528520",           // 明文，服务端据此派生密钥
  "ts": 1730000000000,
  "payload": "q1Jv...=="           // 密文，内含 pair_code + device_id + app_version + model
}
```

服务端拿到 `pair_code` 后派生密钥、解密 `payload`：

- 解密**成功** → 客户端确实持有同一配对码派生出的密钥，等于完成了一次隐式的
  challenge-response 认证（攻击者只知道配对码是不够用的，他同样能通过 —— 见下方权衡）。
- 解密**失败** → 回 `error{code:"auth_failed"}` 并断开。

安全性权衡（个人自用场景可接受）：
1. 配对码在公网链路上会明文出现一次。**强烈建议**开启 frp 的 TLS
   （`transport.tls.enable = true`），让“手机 → frps”这一段也被加密。
2. 服务端**不落库**明文配对码，`devices` 表只存 `sha256(pair_code)` 作为群组标识。
3. 配对码 = 密钥种子，请当密码对待：别用 `1234` 这种，建议 8 位以上随机数字。
4. 后续所有帧（电量/定位/使用/历史）全部加密，泄露面仅限“谁在什么时候连过服务器”。


## 4. 加密

| 项目 | 取值 |
|---|---|
| 算法 | AES-256-GCM（`AES/GCM/NoPadding`），认证标签 128 bit |
| 密钥派生 | `PBKDF2WithHmacSHA256` / Python `PBKDF2HMAC(SHA256)` |
| 盐 | 固定字符串 `yuanzhiyue_salt`（UTF-8 字节） |
| 迭代 | `10000` |
| 派生长度 | `32` 字节（256 bit） |
| IV | 每帧随机 12 字节（`SecureRandom` / `os.urandom`） |
| 密文布局 | `Base64( IV[12] ‖ CipherText ‖ Tag[16] )`，标准 Base64 带 `=` 填充 |

要点：
1. 两端派生逻辑必须逐字节一致。Java 的 `PBKDF2WithHmacSHA256` 把口令按 **UTF-8** 处理，
   Python 侧使用 `pair_code.encode("utf-8")`，一致。**配对码请只用 ASCII**（推荐 6 位数字），
   避免某些 JDK 实现对非 ASCII 口令的编码差异。
2. GCM 认证失败（`AEADBadTagException`）出现在：配对码不一致、密文被篡改、
   Base64 被破坏。服务端收到解密失败 → 回 `error` 并断开；客户端收到 → 丢弃该帧并提示。
3. 同一帧绝不复用 IV；服务端转发时**重新加密**（生成新 IV），不复用客户端密文。

## 5. 消息类型总表

### 5.1 客户端 → 服务端

| type | 明文字段 | 密文 payload 字段 | 说明 |
|---|---|---|---|
| `auth` | `device_id`, **`pair_code`** | `pair_code`, `device_id`, `app_version`, `model` | 连接后立即发送，用于绑定/识别（`pair_code` 明文的必要性见 4.1） |
| `battery` | – | `level`(0-100), `charging`(bool), `timestamp`(ms) | 电量变化时 + 每 N 分钟 |
| `location` | – | `lat`, `lng`, `accuracy`(米), `provider`, **`bearing`**, `timestamp` | 默认每 3 分钟 / 移动 50 米 |
| `usage` | – | `package_name`, `app_label`, `event_type`(`start`/`end`), `timestamp` | 前台应用切换时 |
| `avatar` | – | `mime`, `data`(Base64), `timestamp` | 用户裁剪保存头像时上传一次 |
| `ping` | – | 无（不带 `payload`） | 每 30 秒 |
| `history_request` | – | `target_device_id`, `data_type`(`battery`/`location`/`usage`/`avatar`), `limit` | 拉取对方历史 / 头像 |
| `bye` | – | 无 | 主动断开前发送（可选） |

**`bearing`（方向角）约定**：0~360 的浮点数，正北为 0、顺时针增长，单位度。
取不到方向时填 **`-1`**（客户端与坐标转换、服务端校验都必须接受这个哨兵值）。
客户端优先用系统给的航向（`Location.hasBearing()`），没有就用前后两个点算方位角，
位移不足 12 米时沿用上一次的值——否则原地 GPS 抖动会让箭头乱转。

**`avatar` 的约束**：
- `data` 是**整张图片文件的 Base64**（标准 Base64，带 `=` 填充，不含 `data:` 前缀）。
- 客户端固定上传 **256×256 JPEG（quality 85）**，通常 10~30 KB，Base64 后约 15~40 KB。
- 服务端上限 `MAX_AVATAR_BYTES = 512 KiB`，超了回 `error{code:"bad_request"}`。
- `mime` 不在白名单（`image/jpeg` / `image/png` / `image/webp`）时，服务端**归一到 `image/jpeg`** 而不是报错。
- `timestamp` 是版本号：接收方只在 `timestamp > 本地已有版本` 时才写盘，避免重复拉取时反复覆盖文件。

### 5.2 服务端 → 客户端

| type | 明文字段 | 密文 payload 字段 | 说明 |
|---|---|---|---|
| `auth_ok` | `partner_device_id` | `partner_device_id`, `online`, `server_time` | 认证成功；`partner_device_id` 可能为 `null`（对方还没装/没连过） |
| `pong` | – | 无 | 对 `ping` 的回应，兼作 RTT 测量 |
| `battery` | `from` | 同上报字段 | 转发对方电量 |
| `location` | `from` | 同上报字段 | 转发对方定位（含 `bearing`） |
| `usage` | `from` | 同上报字段 | 转发对方使用行为 |
| `avatar` | `from` | 同上报字段 | 转发对方刚更新的头像 |
| `status` | `online`(bool) | `partner_device_id`, `online` | **扩展**：对方上线/下线时推送 |
| `history_response` | – | `data_type`, `records`(数组) | 历史数据；`records` 元素字段见 5.3 |
| `error` | `message` | `message`, `code` | 错误；`code` 见 5.4 |

### 5.3 `history_response.records` 元素

- `data_type = "battery"` → `{ "level", "charging", "timestamp" }`
- `data_type = "location"` → `{ "lat", "lng", "accuracy", "provider", "bearing", "timestamp" }`
- `data_type = "usage"` → `{ "package_name", "app_label", "event_type", "timestamp" }`
- `data_type = "avatar"` → `{ "mime", "data"(Base64), "timestamp" }`，**最多 1 条**（0 条表示对方还没设置过头像）

按 `timestamp` **升序**返回（旧 → 新），客户端直接顺序入轨迹/时间线。

### 5.4 `error.code`

| code | 含义 |
|---|---|
| `bad_frame` | 帧格式错误 / 超长 |
| `auth_required` | 未先 `auth` 就发业务消息 |
| `auth_failed` | 解密失败或 payload 缺 `pair_code` |
| `unknown_type` | 不支持的消息类型 |
| `bad_request` | `history_request` 参数非法 |
| `internal` | 服务端内部异常 |

## 6. 配对与在线状态

- 配对码（`pair_code`）既是**共享密钥种子**又是**群组 ID**：
  服务端 `devices(pair_code)` 相同且 `device_id` 不同的两个设备互为伙伴。
- `devices.partner_id` 在第二台设备加入时由服务端补全（双向写入）。
- 一方上线 → 服务端向另一方推 `status{online:true}`；
  一方断开 → 推 `status{online:false}`。
- 客户端若长时间收不到任何帧（默认 90 秒读超时）则判定链路已死并重连。

## 7. 时序示例

```
clientA                       server                        clientB
  |-- auth(pair_code=123456) -->|                              |
  |<-- auth_ok(partner=B) ------|                              |
  |                             |<-- auth(pair_code=123456) ---|
  |<-- status(online=true) -----|---- auth_ok(partner=A) ----->|
  |                             |---- status(online=true) ---->|
  |-- battery(level=88) ------->|                              |
  |                             |-- battery(from=A, level=88) ->|
  |-- location(bearing=123.5) ->|                              |
  |                             |-- location(from=A, ...) ----->|   （对方头像会套在
  |-- avatar(jpeg, ts) -------->|                              |     这个点上，箭头指向 bearing）
  |                             |-- avatar(from=A, jpeg) ------>|
  |-- ping -------------------->|                              |
  |<-- pong --------------------|                              |
  |-- history_request(location)->|                              |
  |<-- history_response(200 pts)|                              |
  |-- history_request(avatar) ->|                              |
  |<-- history_response(1 rec) -|                              |
```

## 8. 坐标系统（重要）

- **协议里传输、服务端存储、距离计算，一律使用 WGS-84**（手机定位 API 的原始输出）。
- **只有客户端渲染到高德地图之前**，才调用 `GeoUtils.wgs84ToGcj02` 做偏移纠正。
- 原因：高德/腾讯用 GCJ-02，百度用 BD-09，国内与 WGS-84 相差几十到几百米。
  如果把纠偏提前到发送端，数据就被「污染」了 —— 以后换地图、算距离、做轨迹分析都会偏。
- 服务端不关心坐标系，原样存取即可。

## 9. 版本协商

`auth.payload.app_version` 目前仅记录到数据库，便于排查“两端行为不一致”。
若未来协议不兼容，将新增 `auth_ok.protocol` 字段；当前固定为 `1`。

新增字段都遵循**向后兼容**原则：`bearing` 缺失时客户端按 `-1`（未知）处理，
所以旧版本客户端连上新版本服务端、或反过来，都不会崩，只是少个箭头方向。

## 10. 变更记录

| 版本 | 变更 |
|---|---|
| v1 | 首版：auth / battery / location / usage / ping / history_request |
| v1.1 | `location` 增加 `bearing`（行进方向角，-1 表示未知） |
| v1.2 | 新增 `avatar` 消息与 `history_request.data_type="avatar"`；服务端新增 `avatars` 表 |
