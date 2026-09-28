package net.game5048.baobao.data.network

import org.json.JSONObject

/**
 * 通信协议常量与帧编解码。
 *
 * 完整定义见 docs/PROTOCOL.md，服务端 server/server.py 使用同一套规则。
 *
 * 帧格式：
 * ```
 * +------------------+----------------------------+
 * | 4 字节大端 uint32 |  UTF-8 JSON 字节流 (len)     |
 * +------------------+----------------------------+
 * ```
 */
object Protocol {

    /** 单帧最大长度 1 MiB，与服务端 MAX_PAYLOAD 保持一致 */
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** 长度头字节数 */
    const val HEADER_BYTES = 4

    // ---------------- 客户端 -> 服务端 ----------------
    const val TYPE_AUTH = "auth"
    const val TYPE_BATTERY = "battery"
    const val TYPE_LOCATION = "location"
    const val TYPE_USAGE = "usage"
    const val TYPE_AVATAR = "avatar"
    const val TYPE_PING = "ping"
    const val TYPE_HISTORY_REQUEST = "history_request"
    const val TYPE_BYE = "bye"

    // ---------------- 服务端 -> 客户端 ----------------
    const val TYPE_AUTH_OK = "auth_ok"
    const val TYPE_PONG = "pong"
    const val TYPE_STATUS = "status"
    const val TYPE_HISTORY_RESPONSE = "history_response"
    const val TYPE_ERROR = "error"

    // ---------------- 历史数据类型 ----------------
    const val DATA_BATTERY = "battery"
    const val DATA_LOCATION = "location"
    const val DATA_USAGE = "usage"
    /** 头像不是「历史」，但复用 history_request 通道拉取，最多返回 1 条 */
    const val DATA_AVATAR = "avatar"

    /** 心跳间隔：30 秒 */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** 读超时：90 秒收不到任何字节就认为链路已死（3 个心跳周期） */
    const val READ_TIMEOUT_MS = 90_000

    /** TCP 连接超时 */
    const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * 解析后的信封。
     *
     * [payload] 是密文 Base64，需要调用方用 [CryptoUtils.decrypt] 解出业务 JSON。
     * 之所以不让 TcpClient 直接解密：TcpClient 只负责“管道”，
     * 业务语义（怎么用这些数据）属于 Repository 的职责。
     */
    data class Envelope(
        /** 消息类型 */
        val type: String,
        /** 我方发出的帧里带的是自己的设备 ID */
        val deviceId: String?,
        /** 服务端转发来的帧里带的是数据来源设备 ID（对方） */
        val from: String?,
        /** 信封级时间戳（毫秒） */
        val ts: Long,
        /** 密文 Base64；ping/pong/auth_ok 等控制帧可能为 null */
        val payload: String?,
        /** 原始 JSON，便于访问协议扩展字段（如 auth_ok.partner_device_id） */
        val raw: JSONObject
    ) {
        /** 解密并解析业务体；无 payload 或解密失败返回 null */
        fun decryptPayload(pairCode: String): JSONObject? {
            val cipherText = payload ?: return null
            val plain = CryptoUtils.decrypt(cipherText, CryptoUtils.deriveKey(pairCode)) ?: return null
            return runCatching { JSONObject(plain) }.getOrNull()
        }
    }

    /**
     * 构造待发送的 JSON 信封字符串。
     *
     * @param type 消息类型
     * @param deviceId 本机设备 ID（明文，供服务端路由）
     * @param encryptedPayload 已加密的 Base64 密文；控制帧可传 null
     * @param plainExtra 额外的信封明文字段。
     *        只有 `auth` 会用：配对码必须明文发给服务端，否则服务端没有密钥去解密 payload
     *        （密钥正是由配对码派生的，这是鸡生蛋问题）。详见 docs/PROTOCOL.md 第 4.1 节。
     */
    fun buildEnvelope(
        type: String,
        deviceId: String,
        encryptedPayload: String?,
        timestamp: Long = System.currentTimeMillis(),
        plainExtra: JSONObject? = null
    ): String = JSONObject().apply {
        put("type", type)
        put("device_id", deviceId)
        put("ts", timestamp)
        if (encryptedPayload != null) put("payload", encryptedPayload)
        plainExtra?.let { extra ->
            val keys = extra.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, extra.get(key))
            }
        }
    }.toString()

    /**
     * 解析收到的 JSON 信封。
     * 字段缺失时用安全默认值，绝不因为对方多发/少发字段而抛异常断开连接。
     */
    fun parseEnvelope(json: String): Envelope? = runCatching {
        val obj = JSONObject(json)
        Envelope(
            type = obj.optString("type", ""),
            deviceId = obj.optString("device_id").takeIf { it.isNotEmpty() },
            from = obj.optString("from").takeIf { it.isNotEmpty() },
            ts = obj.optLong("ts", System.currentTimeMillis()),
            payload = obj.optString("payload").takeIf { it.isNotEmpty() },
            raw = obj
        )
    }.getOrNull()

    /** 把长度头与 JSON 体打包成完整帧 */
    fun encodeFrame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_FRAME_BYTES) { "帧过大：${body.size} 字节" }
        val frame = ByteArray(HEADER_BYTES + body.size)
        // 大端序写入前 4 字节（与 Python struct.pack(">I", len) 对应）
        frame[0] = ((body.size ushr 24) and 0xFF).toByte()
        frame[1] = ((body.size ushr 16) and 0xFF).toByte()
        frame[2] = ((body.size ushr 8) and 0xFF).toByte()
        frame[3] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, frame, HEADER_BYTES, body.size)
        return frame
    }
}
