package net.game5048.baobao.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.BatteryInfo
import net.game5048.baobao.data.repository.TrackerRepository

/**
 * 电量采集器。
 *
 * 两个触发源：
 *  1. 系统 `ACTION_BATTERY_CHANGED` 广播 —— 电量每次变化都会收到，**秒级**及时
 *  2. 定时兜底 —— 按用户设置的间隔（默认 5 分钟）无条件上报一次，
 *     避免“一直在充电、电量不变”导致对方看到的数据一直是几小时前的
 *
 * 只有电量或充电状态发生变化时才通过广播路径发送，省电又省流量。
 */
class BatteryCollector(
    context: Context,
    private val repository: TrackerRepository,
    private val prefs: PreferencesManager
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var periodicJob: Job? = null

    /** 上次发送过的值，用于去重 */
    @Volatile
    private var lastLevel = -1

    @Volatile
    private var lastCharging: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val info = parse(intent) ?: return
            if (info.level != lastLevel || info.charging != lastCharging) {
                send(info)
            }
        }
    }

    fun start() {
        if (periodicJob?.isActive == true) return

        // 注册监听；ACTION_BATTERY_CHANGED 是系统广播，用 NOT_EXPORTED 更安全
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册瞬间系统会立刻回调一次粘性广播，这里再补一次以防 ROM 行为差异
        sticky?.let { parse(it)?.let { info -> send(info) } }

        periodicJob = scope.launch {
            while (true) {
                val intervalMs = prefs.settings.value.batteryIntervalSec * 1000L
                delay(intervalMs)
                readCurrent()?.let { send(it) }
            }
        }
        Log.i(TAG, "电量采集已启动")
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "注销电量广播失败：${it.message}") }
    }

    /** 连接刚建立时立刻补报一次，让对方马上看到当前电量 */
    fun sendCurrentNow() {
        readCurrent()?.let { send(it) }
    }

    private fun send(info: BatteryInfo) {
        lastLevel = info.level
        lastCharging = info.charging
        repository.sendBattery(info)
    }

    /** 读取当前电量：优先用 BatteryManager 属性，充电状态从粘性广播里取 */
    private fun readCurrent(): BatteryInfo? {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.getOrNull()

        val charging = sticky?.let { isCharging(it) } ?: (manager?.isCharging == true)
        return if (level in 0..100) BatteryInfo(level, charging) else null
    }

    private fun parse(intent: Intent): BatteryInfo? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        return BatteryInfo(percent, isCharging(intent))
    }

    /**
     * 充电判定：状态为 CHARGING/FULL，或已插入任意电源（USB/AC/无线）。
     * 有些 ROM 在涓流阶段会把 status 报成 NOT_CHARGING，所以 plugged 也要看。
     */
    private fun isCharging(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    companion object {
        private const val TAG = "BatteryCollector"
    }
}
