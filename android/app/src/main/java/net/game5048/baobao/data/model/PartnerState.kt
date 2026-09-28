package net.game5048.baobao.data.model

/**
 * TCP 链路状态机（对外暴露给 UI 显示“已连接 / 重连中”）。
 */
enum class ConnectionState {
    /** 未配置配对码或服务端地址 */
    IDLE,
    /** 正在建立连接 */
    CONNECTING,
    /** TCP 已建立且 auth 成功 */
    AUTHENTICATED,
    /** 断开，倒计时后自动重连 */
    RECONNECTING,
    /** 被用户手动停止 */
    STOPPED;

    val displayName: String
        get() = when (this) {
            IDLE -> "未配置"
            CONNECTING -> "连接中…"
            AUTHENTICATED -> "已连接"
            RECONNECTING -> "重连中…"
            STOPPED -> "已停止"
        }

    val isOnline: Boolean get() = this == AUTHENTICATED
}

/**
 * 对方（伴侣）的完整状态快照。
 *
 * 这是 UI 层唯一需要观察的对象：TrackerRepository 持有 `StateFlow<PartnerState>`，
 * 每收到一条消息就 copy() 出新值，Compose 自动重组。
 */
data class PartnerState(
    /** 是否已完成配对（服务端返回过 partner_device_id） */
    val paired: Boolean = false,
    /** 对方设备 ID；null 表示对方还没激活过 */
    val partnerDeviceId: String? = null,
    /** 对方当前是否在线（由服务端 status 消息驱动） */
    val partnerOnline: Boolean = false,

    /** 对方最近一次电量 */
    val battery: BatteryInfo? = null,
    /** 对方最近一次位置 */
    val location: LocationInfo? = null,
    /** 对方最近轨迹（按时间升序，最多 MAX_TRACK_POINTS 个点） */
    val track: List<LocationInfo> = emptyList(),
    /** 对方最近的使用会话（按时间倒序，最新的在前） */
    val sessions: List<UsageSession> = emptyList(),
    /** 对方当前正在使用的前台应用（没有则为 null） */
    val currentForegroundApp: String? = null,

    /** 本机最近一次已知位置，用于地图上显示“我” */
    val ownLocation: LocationInfo? = null,

    /**
     * 对方头像的最后更新时间（毫秒）。0 表示还没有头像。
     *
     * 这里**故意不直接持有 Bitmap**：
     *  - 头像文件由 AvatarStore 管，UI 用 `remember(partnerAvatarAt) { AvatarStore.load(...) }` 读
     *  - 状态流里只放一个版本号，Compose 的重组比较既便宜又不会漏掉更新
     */
    val partnerAvatarAt: Long = 0L,

    /** 自己头像的最后更新时间（毫秒）。0 表示还没设置过 */
    val ownAvatarAt: Long = 0L,

    /** 服务端时间 - 本地时间（毫秒），用于校正“多久之前”的显示 */
    val serverTimeOffset: Long = 0L,

    /** 最近一次错误提示，UI 顶部 Snackbar 消费后置空 */
    val lastError: String? = null
) {
    /** 对方数据最后刷新时间（取电量/位置/使用三者最新） */
    val lastUpdate: Long
        get() = listOfNotNull(
            battery?.timestamp,
            location?.timestamp,
            sessions.firstOrNull()?.let { if (it.ongoing) System.currentTimeMillis() else it.endTime }
        ).maxOrNull() ?: 0L

    companion object {
        /** 轨迹最多保留的点数，避免长时间运行内存膨胀 */
        const val MAX_TRACK_POINTS = 500
    }
}
