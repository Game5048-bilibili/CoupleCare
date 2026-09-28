package net.game5048.baobao.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.BatteryInfo
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.data.model.LocationInfo
import net.game5048.baobao.data.model.PartnerState
import net.game5048.baobao.data.model.UsageInfo
import net.game5048.baobao.data.model.UsageSession
import net.game5048.baobao.data.network.Protocol
import net.game5048.baobao.data.network.TcpClient
import net.game5048.baobao.util.AvatarStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 唯一的数据仓库（单例）。
 *
 * 数据流向：
 * ```
 * 采集器 ──send──▶ TcpClient ──TCP──▶ 服务端 ──转发──▶ 对方
 * 对方 ──▶ 服务端 ──▶ TcpClient.incoming ──解密/解析──▶ TrackerRepository._partnerState ──▶ Compose UI
 * ```
 *
 * 对外只暴露两个东西：
 *  - [partnerState]：UI 需要的全部对方状态（一个 StateFlow，Compose 直接 collectAsState）
 *  - 一组 `sendXxx()`：采集器把数据丢进来
 */
class TrackerRepository private constructor(
    private val appContext: Context,
    private val prefs: PreferencesManager,
    private val client: TcpClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _partnerState = MutableStateFlow(PartnerState())

    /** 对方状态快照，UI 唯一数据源 */
    val partnerState: StateFlow<PartnerState> = _partnerState.asStateFlow()

    /** 链路状态（转发自 TcpClient，方便 UI 只依赖 Repository） */
    val connectionState: StateFlow<ConnectionState> = client.connectionState

    /** 心跳往返延迟 */
    val rttMs: StateFlow<Long> = client.rttMs

    /** 服务端错误提示流 */
    val errors: SharedFlow<String> = client.errors

    /** 使用会话列表，最新在前 */
    private val sessions = mutableListOf<UsageSession>()

    /** 上次拉取历史的时间，避免频繁请求 */
    @Volatile
    private var lastHistoryRequestAt = 0L

    private var started = false

    // ==================================================================
    // 启动
    // ==================================================================

    /** 在 Application.onCreate 中调用一次即可。 */
    fun start() {
        if (started) return
        started = true

        // 0) 把本地已有的头像版本号读进状态，避免刚启动时 UI 以为「没有头像」
        _partnerState.update { state ->
            val ownFile = AvatarStore.selfAvatarFile(appContext)
            val partnerFile = AvatarStore.partnerAvatarFile(appContext)
            state.copy(
                ownAvatarAt = if (ownFile.exists()) ownFile.lastModified() else 0L,
                partnerAvatarAt = if (partnerFile.exists()) partnerFile.lastModified() else 0L
            )
        }

        // 1) 处理收到的信封
        scope.launch {
            client.incoming.collect { envelope ->
                runCatching { handleEnvelope(envelope) }
                    .onFailure { Log.w(TAG, "处理 ${envelope.type} 失败", it) }
            }
        }

        // 2) 连接状态变化：认证成功后主动补一次历史数据，让新装的 APP 立刻有内容
        scope.launch {
            client.connectionState.collect { state ->
                if (state == ConnectionState.AUTHENTICATED) {
                    requestHistory(Protocol.DATA_LOCATION, HISTORY_LIMIT)
                    requestHistory(Protocol.DATA_USAGE, HISTORY_LIMIT)
                    requestHistory(Protocol.DATA_BATTERY, 50)
                    // 头像也补一次：对方可能在我们离线期间换过
                    requestHistory(Protocol.DATA_AVATAR, 1)
                } else if (state != ConnectionState.AUTHENTICATED) {
                    // 掉线时把对方标为离线，避免 UI 一直显示“在线”但数据不动
                    _partnerState.update { it.copy(partnerOnline = false) }
                }
            }
        }

        // 3) 服务端错误 -> 状态里带一份，UI 用 Snackbar 弹
        scope.launch {
            client.errors.collect { message ->
                _partnerState.update { it.copy(lastError = message) }
            }
        }
    }

    // ==================================================================
    // 发送（供采集器调用）
    // ==================================================================

    fun sendBattery(info: BatteryInfo) {
        if (!prefs.settings.value.reportBattery) return
        client.send(Protocol.TYPE_BATTERY, info.toJson())
    }

    fun sendLocation(info: LocationInfo) {
        if (!prefs.settings.value.reportLocation) return
        // 自己的位置也留在本地，地图上画“我”
        _partnerState.update { it.copy(ownLocation = info) }
        client.send(Protocol.TYPE_LOCATION, info.toJson())
    }

    fun sendUsage(info: UsageInfo) {
        if (!prefs.settings.value.reportUsage) return
        client.send(Protocol.TYPE_USAGE, info.toJson())
    }

    /**
     * 上传自己的头像（256x256 JPEG 字节）。
     *
     * 头像不是高频数据，只有用户主动裁剪保存时才调用一次。
     * 经 Base64 后约 20~40 KB，远小于 1 MiB 的帧上限。
     *
     * @param jpeg 已经裁剪并压缩好的 JPEG 字节
     * @param timestamp 版本时间戳，用于对方判断「这比我手上的新吗」
     */
    fun sendAvatar(jpeg: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (jpeg.isEmpty()) return
        val encoded = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("mime", "image/jpeg")
            put("data", encoded)
            put("timestamp", timestamp)
        }
        client.send(Protocol.TYPE_AVATAR, payload)
        // 自己的头像也要立刻反映到本机状态里
        _partnerState.update { it.copy(ownAvatarAt = timestamp) }
    }

    /** 主动向服务端要一次对方的头像 */
    fun requestPartnerAvatar() {
        requestHistory(Protocol.DATA_AVATAR, 1)
    }

    /**
     * 向服务端请求对方的历史数据。
     *
     * @param dataType "location" / "usage" / "battery"
     */
    fun requestHistory(dataType: String, limit: Int = HISTORY_LIMIT) {
        lastHistoryRequestAt = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("target_device_id", _partnerState.value.partnerDeviceId ?: "")
            put("data_type", dataType)
            put("limit", limit.coerceIn(1, 1000))
        }
        client.send(Protocol.TYPE_HISTORY_REQUEST, payload)
    }

    fun clearError() = _partnerState.update { it.copy(lastError = null) }

    // ==================================================================
    // 接收
    // ==================================================================

    private fun handleEnvelope(envelope: Protocol.Envelope) {
        val pairCode = prefs.currentPairCode()
        if (pairCode.isBlank()) return

        when (envelope.type) {
            Protocol.TYPE_AUTH_OK -> handleAuthOk(envelope, pairCode)

            Protocol.TYPE_BATTERY -> {
                val json = envelope.decryptPayload(pairCode) ?: return warnDecrypt(envelope)
                val info = BatteryInfo.fromJson(json)
                _partnerState.update { it.copy(partnerOnline = true, battery = info) }
            }

            Protocol.TYPE_LOCATION -> {
                val json = envelope.decryptPayload(pairCode) ?: return warnDecrypt(envelope)
                val info = LocationInfo.fromJson(json)
                _partnerState.update { current ->
                    current.copy(
                        partnerOnline = true,
                        location = info,
                        track = appendTrack(current.track, info)
                    )
                }
            }

            Protocol.TYPE_USAGE -> {
                val json = envelope.decryptPayload(pairCode) ?: return warnDecrypt(envelope)
                val info = UsageInfo.fromJson(json)
                if (info.packageName.isBlank()) return
                mergeUsage(info)
            }

            Protocol.TYPE_STATUS -> {
                // 在线状态：优先读密文里的，读不到就退回明文扩展字段
                val json = envelope.decryptPayload(pairCode)
                val online = json?.optBoolean("online") ?: envelope.raw.optBoolean("online", false)
                val partnerId = json?.optString("partner_device_id")?.takeIf { it.isNotBlank() }
                    ?: envelope.from
                _partnerState.update {
                    it.copy(
                        partnerOnline = online,
                        partnerDeviceId = partnerId ?: it.partnerDeviceId
                    )
                }
            }

            Protocol.TYPE_HISTORY_RESPONSE -> handleHistoryResponse(envelope, pairCode)

            Protocol.TYPE_AVATAR -> {
                // 对方换头像了：立刻存盘并刷新状态里的版本号
                val json = envelope.decryptPayload(pairCode) ?: return warnDecrypt(envelope)
                storePartnerAvatar(json)
            }

            Protocol.TYPE_PONG, Protocol.TYPE_PING -> Unit
        }
    }

    private fun handleAuthOk(envelope: Protocol.Envelope, pairCode: String) {
        val json = envelope.decryptPayload(pairCode)
        val partnerId = json?.optString("partner_device_id")?.takeIf { it.isNotBlank() && it != "null" }
            ?: envelope.raw.optString("partner_device_id").takeIf { it.isNotBlank() && it != "null" }
        val online = json?.optBoolean("online", false) ?: false
        val serverTime = json?.optLong("server_time", System.currentTimeMillis()) ?: System.currentTimeMillis()

        _partnerState.update {
            it.copy(
                paired = partnerId != null,
                partnerDeviceId = partnerId ?: it.partnerDeviceId,
                partnerOnline = online,
                serverTimeOffset = serverTime - System.currentTimeMillis()
            )
        }
    }

    private fun handleHistoryResponse(envelope: Protocol.Envelope, pairCode: String) {
        val json = envelope.decryptPayload(pairCode) ?: return warnDecrypt(envelope)
        val dataType = json.optString("data_type")
        val records: JSONArray = json.optJSONArray("records") ?: JSONArray()

        when (dataType) {
            Protocol.DATA_LOCATION -> {
                // 服务端按时间升序返回，直接顺序入轨迹
                val points = ArrayList<LocationInfo>(records.length())
                for (i in 0 until records.length()) {
                    records.optJSONObject(i)?.let { points += LocationInfo.fromJson(it) }
                }
                if (points.isEmpty()) return
                val merged = (points + _partnerState.value.track)
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp }
                    .takeLast(PartnerState.MAX_TRACK_POINTS)
                _partnerState.update {
                    it.copy(
                        track = merged,
                        location = it.location ?: merged.lastOrNull()
                    )
                }
            }

            Protocol.DATA_USAGE -> {
                val events = ArrayList<UsageInfo>(records.length())
                for (i in 0 until records.length()) {
                    records.optJSONObject(i)?.let { events += UsageInfo.fromJson(it) }
                }
                rebuildSessions(events)
            }

            Protocol.DATA_BATTERY -> {
                val last = if (records.length() > 0) records.optJSONObject(records.length() - 1) else null
                last?.let { obj ->
                    _partnerState.update { it.copy(battery = it.battery ?: BatteryInfo.fromJson(obj)) }
                }
            }

            Protocol.DATA_AVATAR -> {
                // 最多 1 条；没有说明对方还没设置过头像
                val record = if (records.length() > 0) records.optJSONObject(0) else null
                if (record != null) storePartnerAvatar(record)
            }
        }
    }

    /**
     * 把对方头像的 Base64 数据落到本地文件，并更新状态里的版本号。
     *
     * 状态里只放时间戳而不是 Bitmap：
     *  - Bitmap 放进 StateFlow 会参与 equals 比较，容易触发无意义的重组
     *  - 头像文件由 AvatarStore 统一管理，UI 用 remember(version) 去读，内存占用可控
     */
    private fun storePartnerAvatar(json: JSONObject) {
        val encoded = json.optString("data")
        if (encoded.isEmpty()) return

        val timestamp = json.optLong("timestamp", System.currentTimeMillis())

        // 版本没有变新就跳过，避免每次拉历史都重写一次文件
        if (timestamp <= _partnerState.value.partnerAvatarAt) return

        val bytes = try {
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "对方头像 Base64 解码失败：${t.message}")
            return
        }

        val saved = AvatarStore.savePartnerAvatar(appContext, bytes)
        if (saved) {
            _partnerState.update { it.copy(partnerAvatarAt = timestamp) }
            Log.i(TAG, "已更新对方头像（${bytes.size} 字节）")
        }
    }

    /** 解密失败通常意味着：对方换了配对码，或有人在篡改数据 */
    private fun warnDecrypt(envelope: Protocol.Envelope) {
        Log.w(TAG, "解密失败，丢弃 ${envelope.type} 帧（配对码不一致？）")
    }

    // ==================================================================
    // 轨迹维护
    // ==================================================================

    private fun appendTrack(track: List<LocationInfo>, point: LocationInfo): List<LocationInfo> {
        // 同一秒重复上报的点丢掉，避免轨迹抖动
        if (track.lastOrNull()?.timestamp == point.timestamp) return track
        val newTrack = ArrayList<LocationInfo>(track.size + 1).apply {
            addAll(track)
            add(point)
        }
        // 只保留最近 N 个点，长时间运行也不会吃光内存
        return if (newTrack.size > PartnerState.MAX_TRACK_POINTS) {
            newTrack.subList(newTrack.size - PartnerState.MAX_TRACK_POINTS, newTrack.size).toList()
        } else {
            newTrack
        }
    }

    // ==================================================================
    // 使用会话维护（start/end 事件配对成“一次使用”）
    // ==================================================================

    /**
     * 实时事件：增量更新。
     * 规则（与微信/数字健康的时间线一致）：
     *  - 收到 start：把其它仍在进行的会话在“此刻”关闭，然后新开一个
     *  - 收到 end：关闭对应包名的进行中会话
     */
    private fun mergeUsage(event: UsageInfo) {
        if (event.isStart) {
            for (i in sessions.indices) {
                val s = sessions[i]
                if (s.ongoing && s.packageName != event.packageName) {
                    sessions[i] = s.copy(endTime = event.timestamp, ongoing = false)
                }
            }
            val alreadyOpen = sessions.any { it.ongoing && it.packageName == event.packageName }
            if (!alreadyOpen) {
                sessions.add(
                    0,
                    UsageSession(
                        packageName = event.packageName,
                        appLabel = event.appLabel.ifBlank { event.packageName },
                        startTime = event.timestamp,
                        endTime = event.timestamp,
                        ongoing = true
                    )
                )
            }
        } else {
            val index = sessions.indexOfFirst { it.ongoing && it.packageName == event.packageName }
            if (index >= 0) {
                sessions[index] = sessions[index].copy(
                    endTime = event.timestamp.coerceAtLeast(sessions[index].startTime),
                    ongoing = false
                )
            }
        }

        trimSessions()
        publishSessions()
    }

    /** 历史批量重建：清空后按时间顺序重放一遍 */
    private fun rebuildSessions(events: List<UsageInfo>) {
        if (events.isEmpty()) return
        sessions.clear()
        events.sortedBy { it.timestamp }.forEach { event ->
            if (event.isStart) {
                for (i in sessions.indices) {
                    val s = sessions[i]
                    if (s.ongoing && s.packageName != event.packageName) {
                        sessions[i] = s.copy(endTime = event.timestamp, ongoing = false)
                    }
                }
                if (sessions.none { it.ongoing && it.packageName == event.packageName }) {
                    sessions.add(
                        0,
                        UsageSession(
                            event.packageName,
                            event.appLabel.ifBlank { event.packageName },
                            event.timestamp,
                            event.timestamp,
                            true
                        )
                    )
                }
            } else {
                val index = sessions.indexOfFirst { it.ongoing && it.packageName == event.packageName }
                if (index >= 0) {
                    sessions[index] = sessions[index].copy(
                        endTime = event.timestamp.coerceAtLeast(sessions[index].startTime),
                        ongoing = false
                    )
                }
            }
        }
        // 历史里最后一条 start 可能已经没有 end 了，但如果它发生在很久以前，
        // 说明只是对方关机/没上报，不应显示成“正在使用”
        val now = System.currentTimeMillis()
        for (i in sessions.indices) {
            val s = sessions[i]
            if (s.ongoing && now - s.startTime > ONGOING_TTL_MS) {
                sessions[i] = s.copy(endTime = s.startTime, ongoing = false)
            }
        }
        sessions.sortByDescending { it.startTime }
        trimSessions()
        publishSessions()
    }

    private fun trimSessions() {
        while (sessions.size > MAX_SESSIONS) {
            sessions.removeAt(sessions.lastIndex)
        }
    }

    private fun publishSessions() {
        val snapshot = sessions.toList()
        _partnerState.update { current ->
            current.copy(
                sessions = snapshot,
                currentForegroundApp = snapshot.firstOrNull { it.ongoing }?.appLabel
            )
        }
    }

    companion object {
        private const val TAG = "TrackerRepository"

        /** 一次拉多少条历史 */
        const val HISTORY_LIMIT = 200

        /** 本地最多保留多少个使用会话 */
        private const val MAX_SESSIONS = 300

        /** 超过这个时长还没收到 end，就不认为“正在使用”了（防止对方关机后一直显示在用） */
        private const val ONGOING_TTL_MS = 30 * 60 * 1000L

        @Volatile
        private var instance: TrackerRepository? = null

        fun getInstance(context: Context): TrackerRepository =
            instance ?: synchronized(this) {
                instance ?: TrackerRepository(
                    context.applicationContext,
                    PreferencesManager.getInstance(context),
                    TcpClient.getInstance(context)
                ).also { it.start(); instance = it }
            }
    }
}
