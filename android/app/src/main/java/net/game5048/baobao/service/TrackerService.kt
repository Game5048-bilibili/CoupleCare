package net.game5048.baobao.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.data.network.TcpClient
import net.game5048.baobao.data.repository.TrackerRepository
import net.game5048.baobao.util.BatteryCollector
import net.game5048.baobao.util.LocationCollector
import net.game5048.baobao.util.PermissionUtils
import net.game5048.baobao.util.UsageCollector

/**
 * 常驻前台服务 —— 整个 APP 的心脏。
 *
 * 干三件事：
 *  1. 把自己提升为前台服务（带常驻通知），避免被系统在后台清理
 *  2. 拉起三个采集器（电量 / 定位 / 使用行为）
 *  3. 启动 [TcpClient] 长连接，并持续把最新状态刷到通知栏
 *
 * ## 保活设计（针对 ColorOS）
 * - `START_STICKY`：被系统杀掉后由系统尝试重建
 * - `onTaskRemoved`：用户从最近任务划掉时，用 AlarmManager 定时拉起（ColorOS 上配合
 *   “允许自启动 / 允许后台运行”才有效，见 README）
 * - 断线时才持有 PARTIAL_WAKE_LOCK（最多 5 分钟）：只在“正在重连”这个短窗口内保证 CPU 不睡，
 *   连上就立刻释放。**不常驻持锁**，否则一天下来耗电会很明显。
 */
class TrackerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: PreferencesManager
    private lateinit var repository: TrackerRepository
    private lateinit var client: TcpClient

    private var batteryCollector: BatteryCollector? = null
    private var locationCollector: LocationCollector? = null
    private var usageCollector: UsageCollector? = null

    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastNotificationSignature: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        repository = TrackerRepository.getInstance(this)
        client = TcpClient.getInstance(this)

        NotificationHelper.createChannels(this)

        // onStartCommand 之前就要变成前台服务，否则 5 秒内没调用 startForeground 会 ANR/崩溃
        promoteToForeground()
        observeStateForNotification()
        observeConnectionForWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令")
                stopEverything()
                return START_NOT_STICKY
            }
        }

        prefs.setServiceEnabled(true)
        promoteToForeground()
        startCollectors()
        client.start()

        // 每次启动都补报一次电量：服务重启后对方能立刻看到最新数据
        batteryCollector?.sendCurrentNow()
        locationCollector?.sendCachedLocation()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务列表划掉 APP。ColorOS 会连服务一起杀，这里预约一次重启。
        Log.i(TAG, "任务被移除，预约重启服务")
        scheduleRestart(delayMs = 2_000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "服务销毁")
        runCatching { usageCollector?.stop() }
        runCatching { locationCollector?.stop() }
        runCatching { batteryCollector?.stop() }
        batteryCollector = null
        locationCollector = null
        usageCollector = null

        releaseWakeLock()
        serviceScope.cancel()

        // 不是用户主动停止，就尝试自己拉起来（START_STICKY 之外的兜底）
        if (prefs.settings.value.serviceEnabled) {
            scheduleRestart(delayMs = 3_000L)
        }
        super.onDestroy()
    }

    // ==================================================================
    // 前台服务与通知
    // ==================================================================

    /**
     * 提升为前台服务。
     *
     * foregroundServiceType 必须与 AndroidManifest 中声明的类型匹配，
     * 且只挑“此刻真正有权限”的类型 —— 否则 Android 14 会直接抛 SecurityException。
     *
     * ## 为什么要做降级链
     * 如果 `startForeground` 抛异常，服务就没有进入前台状态，
     * 系统会在 5 秒后以 "did not then call Service.startForeground()" 为由直接杀掉进程。
     * 所以这里按「location+dataSync」→「仅 dataSync」的顺序重试；
     * 全部失败就自己 stopSelf()，宁可安静退出也不要让 APP 崩一次。
     */
    private fun promoteToForeground() {
        val notification = NotificationHelper.buildTrackerNotification(
            this,
            repository.partnerState.value,
            client.connectionState.value,
            client.rttMs.value
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及以下没有 foregroundServiceType 概念
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.e(TAG, "提升前台服务失败：${t.javaClass.simpleName} - ${t.message}")
                stopSelf()
            }
            return
        }

        // 去重：没有定位权限时两者可能相同
        val candidates = listOf(
            foregroundServiceTypes(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ).distinct()

        for (types in candidates) {
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "以类型 $types 提升前台服务失败：${t.message}")
            }
        }

        Log.e(TAG, "无法提升为前台服务（后台启动被限制？），停止自身")
        stopSelf()
    }

    private fun foregroundServiceTypes(): Int {
        // dataSync 是保底类型（心跳/上报本身就是数据同步），没有额外权限要求
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // 只有「前台定位 + 后台定位」两个权限都在手，才敢声明 location 类型。
        // 原因：Android 14 规定，从后台启动 location 类型的前台服务必须持有
        // ACCESS_BACKGROUND_LOCATION，否则 startForeground 直接抛 SecurityException。
        // 权限不全时退回 dataSync，服务照样能跑（只是后台定位会被系统限制，这是系统规则）。
        if (PermissionUtils.hasLocationPermission(this) &&
            PermissionUtils.hasBackgroundLocationPermission(this)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    /** 监听状态变化，刷新通知内容；只有文案真的变了才触发一次 notify */
    private fun observeStateForNotification() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            combine(
                repository.partnerState,
                client.connectionState,
                client.rttMs
            ) { partner, connection, rtt -> Triple(partner, connection, rtt) }
                .collect { (partner, connection, rtt) ->
                    val signature = buildString {
                        append(connection.name)
                        append('|').append(partner.partnerOnline)
                        append('|').append(partner.battery?.level)
                        append('|').append(partner.battery?.charging)
                        append('|').append(partner.currentForegroundApp)
                        append('|').append(rtt)
                        append('|').append((partner.battery?.timestamp ?: 0L) / 60_000L)
                    }
                    if (signature == lastNotificationSignature) return@collect
                    lastNotificationSignature = signature
                    promoteToForeground()
                }
        }
    }

    // ==================================================================
    // 采集器
    // ==================================================================

    private fun startCollectors() {
        if (batteryCollector == null) {
            batteryCollector = BatteryCollector(applicationContext, repository, prefs).also { it.start() }
        }
        if (locationCollector == null) {
            locationCollector = LocationCollector(applicationContext, repository, prefs).also { it.start() }
        }
        if (usageCollector == null) {
            usageCollector = UsageCollector(applicationContext, repository, prefs).also { it.start() }
        }
    }

    // ==================================================================
    // WakeLock（仅在断线重连窗口内持有）
    // ==================================================================

    private fun observeConnectionForWakeLock() {
        serviceScope.launch {
            client.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> acquireWakeLock()
                    else -> releaseWakeLock()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
        }
        runCatching { lock.acquire(WAKELOCK_TIMEOUT_MS) }
            .onSuccess {
                wakeLock = lock
                Log.d(TAG, "重连期间持有 WakeLock（最多 5 分钟）")
            }
            .onFailure { Log.w(TAG, "获取 WakeLock 失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    // ==================================================================
    // 停止与重启
    // ==================================================================

    private fun stopEverything() {
        prefs.setServiceEnabled(false)
        runCatching { usageCollector?.stop() }
        runCatching { locationCollector?.stop() }
        runCatching { batteryCollector?.stop() }
        client.stop()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 预约一次服务重启。
     *
     * 用 `AlarmManager.set()`（非精确闹钟）而不是 setExactAndAllowWhileIdle：
     * 后者在 Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，而这里并不需要秒级精确。
     */
    private fun scheduleRestart(delayMs: Long) {
        if (!prefs.settings.value.serviceEnabled) return
        try {
            val intent = Intent(applicationContext, TrackerService::class.java)
                .setAction(ACTION_RESTART)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getForegroundService(this, REQ_RESTART, intent, flags)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent
            )
        } catch (t: Throwable) {
            Log.w(TAG, "预约重启失败：${t.message}")
        }
    }

    companion object {
        private const val TAG = "TrackerService"

        const val ACTION_START = "net.game5048.baobao.action.START"
        const val ACTION_STOP = "net.game5048.baobao.action.STOP"
        const val ACTION_RESTART = "net.game5048.baobao.action.RESTART"

        /** 常驻通知 ID */
        const val NOTIFICATION_ID = 1001

        private const val REQ_RESTART = 2001
        private const val WAKELOCK_TAG = "baobao:tracker"
        private const val WAKELOCK_TIMEOUT_MS = 5 * 60 * 1000L

        /** 启动服务（设置页的开关 / 首次配置完成后调用） */
        fun start(context: Context) {
            PreferencesManager.getInstance(context).setServiceEnabled(true)
            val intent = Intent(context, TrackerService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "启动服务失败：${it.message}") }
        }

        /** 停止服务 */
        fun stop(context: Context) {
            PreferencesManager.getInstance(context).setServiceEnabled(false)
            val intent = Intent(context, TrackerService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "停止服务失败：${it.message}") }
        }

        /** 配置变更后重启服务（简单粗暴但可靠） */
        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}
