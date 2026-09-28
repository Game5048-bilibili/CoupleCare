package net.game5048.baobao.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import net.game5048.baobao.MainActivity
import net.game5048.baobao.R
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.data.model.PartnerState
import net.game5048.baobao.util.TimeFormatter

/**
 * 通知构建工具。
 *
 * 常驻通知是前台服务的“入场券”（Android 8+ 要求前台服务必须挂一条通知），
 * 同时也是这个 APP 最有用的信息面板：一眼看到对方电量、在用什么、多久前更新的。
 */
object NotificationHelper {

    const val CHANNEL_TRACKER = "baobao_tracker"
    const val CHANNEL_ALERT = "baobao_alert"

    /** 创建通知渠道，重复调用安全 */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val tracker = NotificationChannel(
            CHANNEL_TRACKER,
            context.getString(R.string.channel_tracker_name),
            // 常驻服务通知优先级要低，否则状态栏一直有个大图标很烦
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_tracker_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            context.getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_alert_desc)
        }

        manager.createNotificationChannel(tracker)
        manager.createNotificationChannel(alert)
    }

    /**
     * 构建常驻通知。
     *
     * 文案随状态变化：
     *  - 未配置 → 提示去设置页填地址和配对码
     *  - 已连接 → 对方电量 + 充电状态 + 最后更新时间 + 当前前台应用
     *  - 重连中 → 显示重连状态与 RTT
     */
    fun buildTrackerNotification(
        context: Context,
        state: PartnerState,
        connection: ConnectionState,
        rttMs: Long
    ): Notification {
        val title = context.getString(R.string.notif_title)
        val detail = buildDetailText(context, state, connection, rttMs)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            REQ_STOP,
            Intent(context, TrackerService::class.java).setAction(TrackerService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_TRACKER)
            .setSmallIcon(R.drawable.ic_stat_tracker)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildDetailText(
        context: Context,
        state: PartnerState,
        connection: ConnectionState,
        rttMs: Long
    ): String {
        val prefs = PreferencesManager.getInstance(context)
        if (!prefs.settings.value.readyToConnect) {
            return "请先在设置页填写服务器地址与配对码"
        }

        if (connection != ConnectionState.AUTHENTICATED) {
            return when (connection) {
                ConnectionState.CONNECTING -> "正在连接服务器…"
                ConnectionState.RECONNECTING -> "连接断开，正在自动重连…"
                ConnectionState.STOPPED -> "已停止上报"
                else -> "等待连接"
            }
        }

        val batteryPart = state.battery?.let { battery ->
            buildString {
                append("对方电量 ${battery.level}%")
                if (battery.charging) append("（充电中）")
                append(" · ")
                append(TimeFormatter.relative(battery.timestamp))
            }
        } ?: "等待对方数据…"

        val appPart = state.currentForegroundApp?.let { " · 在用 $it" } ?: ""
        val rttPart = if (rttMs > 0) " · ${rttMs}ms" else ""

        return batteryPart + appPart + rttPart
    }

    private const val REQ_CONTENT = 3001
    private const val REQ_STOP = 3002
}
