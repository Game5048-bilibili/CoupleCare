package net.game5048.baobao.data.model

import org.json.JSONObject

/**
 * 电量上报数据 / 对方电量状态。
 *
 * 对应协议 docs/PROTOCOL.md 中的 `battery` 消息（密文 payload 内的字段）。
 */
data class BatteryInfo(
    /** 电量百分比 0..100 */
    val level: Int,
    /** 是否正在充电（含 USB / AC / 无线） */
    val charging: Boolean,
    /** 采集时刻，毫秒时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {

    /** 序列化为明文 JSON，进入密文 payload */
    fun toJson(): JSONObject = JSONObject().apply {
        put("level", level)
        put("charging", charging)
        put("timestamp", timestamp)
    }

    companion object {
        /**
         * 从 JSON 还原。字段缺失时给出安全默认值，避免对方版本不一致导致崩溃。
         */
        fun fromJson(json: JSONObject): BatteryInfo = BatteryInfo(
            level = json.optInt("level", 0).coerceIn(0, 100),
            charging = json.optBoolean("charging", false),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}
