package net.game5048.baobao.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.data.model.PartnerState
import net.game5048.baobao.data.network.Protocol
import net.game5048.baobao.data.repository.TrackerRepository
import net.game5048.baobao.service.TrackerService
import net.game5048.baobao.util.AvatarStore

/**
 * 主 ViewModel。
 *
 * MVVM 里 ViewModel 只做「把 Repository 的流暴露给 Compose + 转发用户操作」，
 * 不持有任何 UI 状态，也不做数据加工 —— 加工在 Repository，渲染在 Composable。
 *
 * 使用 AndroidViewModel 是为了拿 Application 去启动/停止前台服务。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager.getInstance(application)
    private val repository = TrackerRepository.getInstance(application)

    /** 对方状态（地图、使用记录、设置页都用它） */
    val partnerState: StateFlow<PartnerState> = repository.partnerState

    /** 链路状态 */
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    /** 本地配置 */
    val settings: StateFlow<PreferencesManager.Settings> = prefs.settings

    /** 心跳延迟 */
    val rttMs: StateFlow<Long> = repository.rttMs

    /** 服务端错误消息 */
    val errors: SharedFlow<String> = repository.errors

    /** 本机设备 ID（设置页展示，方便排查“配对到哪台机器了”） */
    val deviceId: String get() = prefs.currentDeviceId()

    // ------------------------------------------------------------------
    // 配置修改
    // ------------------------------------------------------------------

    /**
     * 保存配对码。
     *
     * 配对码一变，密钥也就变了，因此必须让连接重来一遍（清空轨迹缓存也该做，
     * 但轨迹是内存态，重启服务时会自然清掉，这里只做最小动作）。
     */
    fun savePairCode(code: String) {
        val normalized = code.trim()
        if (normalized == prefs.currentPairCode()) return
        prefs.setPairCode(normalized)
        reconnect()
    }

    fun saveServer(host: String, port: String) {
        val parsedPort = port.trim().toIntOrNull() ?: PreferencesManager.DEFAULT_PORT
        prefs.setServer(host, parsedPort)
        reconnect()
    }

    fun setReportLocation(enabled: Boolean) = prefs.setReportLocation(enabled)

    fun setReportBattery(enabled: Boolean) = prefs.setReportBattery(enabled)

    fun setReportUsage(enabled: Boolean) = prefs.setReportUsage(enabled)

    fun setLocationInterval(seconds: Int) = prefs.setLocationInterval(seconds)

    fun setBatteryInterval(seconds: Int) = prefs.setBatteryInterval(seconds)

    fun setUsagePollInterval(seconds: Int) = prefs.setUsagePollInterval(seconds)

    fun setShowTrack(enabled: Boolean) = prefs.setShowTrack(enabled)

    fun setShowSelf(enabled: Boolean) = prefs.setShowSelf(enabled)

    // ------------------------------------------------------------------
    // 服务控制
    // ------------------------------------------------------------------

    fun startService() = TrackerService.start(getApplication<Application>())

    fun stopService() = TrackerService.stop(getApplication<Application>())

    /**
     * 配置变化后重连：
     *  - 常驻服务开着 → 重启服务（最省事、最可靠）
     *  - 服务没开 → 直接让 TcpClient 用新配置重连
     */
    private fun reconnect() {
        val app = getApplication<Application>()
        if (prefs.settings.value.serviceEnabled) {
            TrackerService.restart(app)
        }
    }

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    /** 手动拉取一次对方历史（地图页下拉刷新 / 设置页按钮） */
    fun refreshHistory() {
        repository.requestHistory(Protocol.DATA_LOCATION, TrackerRepository.HISTORY_LIMIT)
        repository.requestHistory(Protocol.DATA_USAGE, TrackerRepository.HISTORY_LIMIT)
        repository.requestPartnerAvatar()
    }

    /**
     * 保存并上传自己的头像。
     *
     * 流程：裁剪结果(256x256 JPEG) -> 写入本地 filesDir -> 通过 TCP 上传给服务端 -> 转发给对方。
     * 本地先落盘再上传：即使此刻断网，重连后头像也还在，不会因为发送失败而丢失。
     *
     * @param jpeg 已经裁剪压缩好的 JPEG 字节
     */
    fun updateAvatar(jpeg: ByteArray) {
        val app = getApplication<Application>()
        if (jpeg.isEmpty()) return

        if (!AvatarStore.saveSelfAvatar(app, jpeg)) {
            return
        }
        // 用文件修改时间当版本号：重启 APP 后从文件读回来的值和现在一致，
        // 不会出现「重启之后以为头像过期、又重传一次」的情况
        val version = AvatarStore.selfAvatarFile(app).lastModified()
        repository.sendAvatar(jpeg, version)
    }

    fun clearError() = repository.clearError()
}
