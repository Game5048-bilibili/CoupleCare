package net.game5048.baobao.data.model

import org.json.JSONObject

/**
 * 前台应用行为事件。
 *
 * 对应协议 docs/PROTOCOL.md 中的 `usage` 消息。
 * 一次“使用某 App”会产生两条事件：eventType = start 与 eventType = end，
 * 客户端拿到事件流后由 TrackerRepository 配对成 [UsageSession] 供 UI 显示时长。
 */
data class UsageInfo(
    val packageName: String,
    /** 应用显示名（对方端解析；拿不到时退化为包名） */
    val appLabel: String,
    /** "start" 或 "end" */
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis()
) {

    val isStart: Boolean get() = eventType == EVENT_START

    fun toJson(): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("app_label", appLabel)
        put("event_type", eventType)
        put("timestamp", timestamp)
    }

    companion object {
        const val EVENT_START = "start"
        const val EVENT_END = "end"

        fun fromJson(json: JSONObject): UsageInfo = UsageInfo(
            packageName = json.optString("package_name", ""),
            appLabel = json.optString("app_label", ""),
            eventType = json.optString("event_type", EVENT_START),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

/**
 * 由 start/end 事件配对得到的“一次使用会话”，UI 时间线直接渲染这个。
 */
data class UsageSession(
    val packageName: String,
    val appLabel: String,
    val startTime: Long,
    /** 仍在进行中时等于 startTime + 已流逝时间由 UI 自己算；这里存最后一次事件时间 */
    val endTime: Long,
    /** true 表示对方当前仍停留在该应用 */
    val ongoing: Boolean
) {
    /** 持续时长（毫秒）。进行中的会话由 UI 用当前时间补齐。 */
    val durationMs: Long get() = (endTime - startTime).coerceAtLeast(0L)
}
