package net.game5048.baobao.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.UsageInfo
import net.game5048.baobao.data.repository.TrackerRepository

/**
 * 使用行为采集器。
 *
 * 原理：每 [PreferencesManager.Settings.usagePollSec] 秒（默认 30 秒）轮询一次
 * `UsageStatsManager.queryEvents()`，从最近的事件窗口里找出**最后一个进入前台的包名**，
 * 与上次记录对比：
 *  - 变了 → 给上一个应用补一条 `end`，给新应用发一条 `start`
 *  - 屏幕熄灭 → 给当前应用补一条 `end`
 *
 * 为什么不用 UsageStats 的聚合查询：`queryUsageStats` 只有总时长，没有“什么时候用的”，
 * 做不出对方要的“时间线”。事件流才有精确时间戳。
 *
 * 已知取舍：轮询间隔内如果快速来回切换（A→B→A），中间那次可能被漏掉。
 * 30 秒粒度对“互报备”场景足够，且比 5 秒轮询省电得多。
 */
class UsageCollector(
    context: Context,
    private val repository: TrackerRepository,
    private val prefs: PreferencesManager
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    /** 上次上报过的前台应用包名 */
    @Volatile
    private var lastPackage: String? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                runCatching { poll() }
                    .onFailure { Log.w(TAG, "轮询使用记录失败：${it.message}") }
                val interval = prefs.settings.value.usagePollSec * 1000L
                delay(interval.coerceIn(10_000L, 600_000L))
            }
        }
        Log.i(TAG, "使用行为采集已启动")
    }

    fun stop() {
        job?.cancel()
        job = null
        closeCurrent(System.currentTimeMillis())
    }

    /** 用户手动撤销权限或关闭开关后，把“正在使用”状态收尾，避免对方一直看到旧应用 */
    private fun closeCurrent(timestamp: Long) {
        val previous = lastPackage ?: return
        repository.sendUsage(
            UsageInfo(
                packageName = previous,
                appLabel = AppLabelResolver.label(appContext, previous),
                eventType = UsageInfo.EVENT_END,
                timestamp = timestamp
            )
        )
        lastPackage = null
    }

    // MOVE_TO_FOREGROUND 在 API 29+ 被标记为废弃（等价于 ACTIVITY_RESUMED），
    // 但它对全版本都有效，这里统一抑制废弃警告。
    @Suppress("DEPRECATION")
    private fun poll() {
        val settings = prefs.settings.value
        if (!settings.reportUsage) {
            closeCurrent(System.currentTimeMillis())
            return
        }
        if (!PermissionUtils.hasUsageStatsPermission(appContext)) {
            // 没有“使用情况访问权限”，静默返回；UI 会提示用户去开启
            return
        }

        val usageManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return

        val now = System.currentTimeMillis()
        // 多向前看一个轮询周期，避免事件刚好落在两次查询的缝隙里
        val begin = now - (settings.usagePollSec * 1000L + 15_000L)

        val events = usageManager.queryEvents(begin, now) ?: return
        val event = UsageEvents.Event()

        var foregroundPackage: String? = null
        var foregroundAt = 0L
        var screenOffAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // 注意：MOVE_TO_FOREGROUND 与 API 29 引入的 ACTIVITY_RESUMED 是同一个常量值(1)。
                // 只写一个分支反而对 5.0~14 全版本有效；写两个会被编译器判定为重复分支。
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (event.timeStamp >= foregroundAt) {
                        foregroundAt = event.timeStamp
                        foregroundPackage = event.packageName
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (event.timeStamp > screenOffAt) screenOffAt = event.timeStamp
                }
            }
        }

        // 屏幕熄灭：收尾当前应用
        if (screenOffAt > 0 && screenOffAt >= foregroundAt) {
            closeCurrent(screenOffAt)
            return
        }

        val packageName = foregroundPackage ?: return
        if (AppLabelResolver.isIgnored(packageName)) return
        // 忽略自己：不然对方时间线里全是“异地之约”
        if (packageName == appContext.packageName) return
        if (packageName == lastPackage) return

        val timestamp = if (foregroundAt > 0) foregroundAt else now
        closeCurrent(timestamp)
        repository.sendUsage(
            UsageInfo(
                packageName = packageName,
                appLabel = AppLabelResolver.label(appContext, packageName),
                eventType = UsageInfo.EVENT_START,
                timestamp = timestamp
            )
        )
        lastPackage = packageName
        Log.d(TAG, "前台应用切换 -> ${AppLabelResolver.label(appContext, packageName)}")
    }

    companion object {
        private const val TAG = "UsageCollector"
    }
}
