package net.game5048.baobao.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间/时长格式化。UI 里到处都要用，统一放这里，避免各页面写法不一致。
 */
object TimeFormatter {

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /** "刚刚" / "3 分钟前" / "2 小时前" / "昨天 12:30" / "10-05 08:20" */
    fun relative(timestamp: Long): String {
        if (timestamp <= 0L) return "从未"
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 0 -> "刚刚"
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 6 * 3_600_000L -> "${diff / 3_600_000L} 小时前"
            isYesterday(timestamp) -> "昨天 ${clockFormat.format(Date(timestamp))}"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> dateTimeFormat.format(Date(timestamp))
        }
    }

    /** "12:30" */
    fun clock(timestamp: Long): String = clockFormat.format(Date(timestamp))

    /** "10-05 12:30" */
    fun dateTime(timestamp: Long): String = dateTimeFormat.format(Date(timestamp))

    /** "2024-10-05 12:30:11"，调试用 */
    fun full(timestamp: Long): String = fullFormat.format(Date(timestamp))

    /** 使用时长："2 小时 5 分" / "12 分钟" / "45 秒" */
    fun duration(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        val seconds = safe / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "$hours 小时 ${minutes % 60} 分"
            minutes > 0 -> "$minutes 分钟"
            else -> "$seconds 秒"
        }
    }

    /** 电量列表/轨迹点用的紧凑时间："08:20" */
    fun shortTime(timestamp: Long): String = clockFormat.format(Date(timestamp))

    private fun isYesterday(timestamp: Long): Boolean {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        now.add(Calendar.DAY_OF_YEAR, -1)
        return now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }
}
