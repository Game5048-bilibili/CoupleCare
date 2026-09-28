package net.game5048.baobao.data.model

import org.json.JSONObject

/**
 * 定位上报数据 / 对方位置。
 *
 * 对应协议 docs/PROTOCOL.md 中的 `location` 消息。
 * 轨迹就是按时间排序的一串 LocationInfo。
 */
data class LocationInfo(
    val lat: Double,
    val lng: Double,
    /** 定位精度（米），越大越不准，UI 上用半径圈体现 */
    val accuracy: Float = 0f,
    /** 定位来源：gps / network / fused / passive */
    val provider: String = "fused",
    /**
     * 行进方向角（0~360，正北为 0，顺时针）。
     * -1 表示未知（例如刚启动、或原地不动算不出方向）。
     * 地图上用「头像 + 外圈箭头」体现，箭头指向就是 TA 正在走的方向。
     */
    val bearing: Float = UNKNOWN_BEARING,
    /** 采集时刻，毫秒时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lng", lng)
        put("accuracy", accuracy.toDouble())
        put("provider", provider)
        put("bearing", bearing.toDouble())
        put("timestamp", timestamp)
    }

    companion object {
        /** 方向未知的哨兵值 */
        const val UNKNOWN_BEARING = -1f

        fun fromJson(json: JSONObject): LocationInfo = LocationInfo(
            lat = json.optDouble("lat", 0.0),
            lng = json.optDouble("lng", 0.0),
            accuracy = json.optDouble("accuracy", 0.0).toFloat(),
            provider = json.optString("provider", "fused"),
            // 老版本客户端不发 bearing，缺失时安全退化为「未知」
            bearing = json.optDouble("bearing", UNKNOWN_BEARING.toDouble()).toFloat(),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}
