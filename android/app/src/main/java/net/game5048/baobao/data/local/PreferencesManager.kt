package net.game5048.baobao.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 本地偏好存储。
 *
 * 分两个文件：
 *  - `baobao_prefs`       普通设置（服务器地址、上报开关、间隔），用普通 SharedPreferences
 *  - `baobao_secure_prefs` 敏感信息（device_id、pair_code），用 EncryptedSharedPreferences
 *    底层由 Android Keystore 里的 AES256-GCM 主密钥加密，即使 root 直接读 XML 也拿不到配对码。
 *
 * 读取端（UI、Service、Collector）统一通过 [settings] 这个 StateFlow 观察配置变化，
 * 而不是各自去读 SharedPreferences —— 修改配置后服务能立刻感知并重启采集器。
 */
class PreferencesManager private constructor(context: Context) {

    /** 对外暴露的只读配置快照 */
    data class Settings(
        val deviceId: String,
        val pairCode: String,
        val serverHost: String,
        val serverPort: Int,
        /** 是否上报定位 */
        val reportLocation: Boolean,
        /** 是否上报使用行为 */
        val reportUsage: Boolean,
        /** 是否上报电量 */
        val reportBattery: Boolean,
        /** 定位上报间隔（秒） */
        val locationIntervalSec: Int,
        /** 电量上报间隔（秒） */
        val batteryIntervalSec: Int,
        /** 使用行为轮询间隔（秒） */
        val usagePollSec: Int,
        /** 用户是否开启了“后台常驻” */
        val serviceEnabled: Boolean,
        /** 地图是否显示对方轨迹 */
        val showTrack: Boolean,
        /** 地图上是否显示自己的位置 */
        val showSelf: Boolean
    ) {
        /** 配置是否完整到可以连接 */
        val readyToConnect: Boolean
            get() = pairCode.isNotBlank() && serverHost.isNotBlank() && serverPort in 1..65535
    }

    private val appContext = context.applicationContext

    /** 普通设置 */
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 敏感设置；创建失败（Keystore 异常、旧数据损坏）时降级为普通存储，保证 APP 可用 */
    private val securePrefs: SharedPreferences = createSecurePrefs(appContext)

    private val _settings = MutableStateFlow(readSettings())

    /** UI / Service 观察的配置流 */
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = readSettings()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
        // 首次运行生成设备 ID（写进加密存储）
        ensureDeviceId()
        _settings.value = readSettings()
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private fun readSettings(): Settings = Settings(
        deviceId = ensureDeviceId(),
        pairCode = securePrefs.getString(KEY_PAIR_CODE, "") ?: "",
        serverHost = prefs.getString(KEY_SERVER_HOST, "") ?: "",
        serverPort = prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT),
        reportLocation = prefs.getBoolean(KEY_REPORT_LOCATION, true),
        reportUsage = prefs.getBoolean(KEY_REPORT_USAGE, true),
        reportBattery = prefs.getBoolean(KEY_REPORT_BATTERY, true),
        locationIntervalSec = prefs.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL_SEC),
        batteryIntervalSec = prefs.getInt(KEY_BATTERY_INTERVAL, DEFAULT_BATTERY_INTERVAL_SEC),
        usagePollSec = prefs.getInt(KEY_USAGE_INTERVAL, DEFAULT_USAGE_POLL_SEC),
        serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false),
        showTrack = prefs.getBoolean(KEY_SHOW_TRACK, true),
        showSelf = prefs.getBoolean(KEY_SHOW_SELF, true)
    )

    // ------------------------------------------------------------------
    // 写入（每次写入都会通过 listener 刷新 settings 流）
    // ------------------------------------------------------------------

    /**
     * 设置配对码。配对码变化意味着密钥变化，需要清掉旧的派生密钥缓存。
     */
    fun setPairCode(code: String) {
        val normalized = code.trim()
        if (normalized == currentPairCode()) return
        securePrefs.edit().putString(KEY_PAIR_CODE, normalized).apply()
        net.game5048.baobao.data.network.CryptoUtils.clearCache()
        _settings.value = readSettings()
    }

    fun setServer(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_SERVER_HOST, host.trim())
            .putInt(KEY_SERVER_PORT, port.coerceIn(1, 65535))
            .apply()
    }

    fun setReportLocation(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REPORT_LOCATION, enabled).apply()

    fun setReportUsage(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REPORT_USAGE, enabled).apply()

    fun setReportBattery(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REPORT_BATTERY, enabled).apply()

    fun setLocationInterval(sec: Int) =
        prefs.edit().putInt(KEY_LOCATION_INTERVAL, sec.coerceIn(30, 3600)).apply()

    fun setBatteryInterval(sec: Int) =
        prefs.edit().putInt(KEY_BATTERY_INTERVAL, sec.coerceIn(60, 3600)).apply()

    fun setUsagePollInterval(sec: Int) =
        prefs.edit().putInt(KEY_USAGE_INTERVAL, sec.coerceIn(10, 600)).apply()

    fun setServiceEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()

    fun setShowTrack(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SHOW_TRACK, enabled).apply()

    fun setShowSelf(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SHOW_SELF, enabled).apply()

    /** 清空配对信息（换对象时用） */
    fun clearPairing() {
        securePrefs.edit().remove(KEY_PAIR_CODE).apply()
        net.game5048.baobao.data.network.CryptoUtils.clearCache()
        _settings.value = readSettings()
    }

    fun currentPairCode(): String = securePrefs.getString(KEY_PAIR_CODE, "") ?: ""

    fun currentDeviceId(): String = ensureDeviceId()

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 设备 ID：UUID 去掉横线，用于服务端唯一标识这台手机。
     * 首次访问时生成并持久化；写入加密存储，防止被其它应用读取用于伪造上报。
     */
    private fun ensureDeviceId(): String {
        securePrefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString().replace("-", "")
        securePrefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    private fun createSecurePrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // 常见原因：从旧手机迁移数据导致 Keystore 内主密钥失效。
        // 处理方式：删掉损坏文件重新创建，用户需要重新填一次配对码。
        Log.e(TAG, "加密存储不可用，降级为普通存储", t)
        context.deleteSharedPreferences(SECURE_PREFS_NAME)
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "PreferencesManager"

        private const val PREFS_NAME = "baobao_prefs"
        private const val SECURE_PREFS_NAME = "baobao_secure_prefs"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIR_CODE = "pair_code"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_REPORT_LOCATION = "report_location"
        private const val KEY_REPORT_USAGE = "report_usage"
        private const val KEY_REPORT_BATTERY = "report_battery"
        private const val KEY_LOCATION_INTERVAL = "location_interval_sec"
        private const val KEY_BATTERY_INTERVAL = "battery_interval_sec"
        private const val KEY_USAGE_INTERVAL = "usage_poll_sec"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_SHOW_TRACK = "show_track"
        private const val KEY_SHOW_SELF = "show_self"

        /** 与服务端 config.py 的 DEFAULT_PORT 保持一致 */
        const val DEFAULT_PORT = 9527
        const val DEFAULT_LOCATION_INTERVAL_SEC = 180
        const val DEFAULT_BATTERY_INTERVAL_SEC = 300
        const val DEFAULT_USAGE_POLL_SEC = 30

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager =
            instance ?: synchronized(this) {
                instance ?: PreferencesManager(context).also { instance = it }
            }
    }
}
