package net.game5048.baobao.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.LocationInfo
import net.game5048.baobao.data.repository.TrackerRepository

/**
 * 定位采集器。
 *
 * ## 双引擎设计（很重要）
 * 国行 OPPO / ColorOS 手机**通常不带 Google 服务框架（GMS）**，
 * 这种情况下 `FusedLocationProviderClient` 拿不到任何位置。
 * 所以这里做了自动降级：
 *
 *  1. **优先**：Google Play Services 的 FusedLocationProvider
 *     （融合 GPS + WiFi + 基站，最省电、室内也能定位）
 *  2. **降级**：系统原生 `LocationManager`
 *     （GPS_PROVIDER + NETWORK_PROVIDER 多路并行，无需任何 Google 组件）
 *
 * 启动时用 `GoogleApiAvailability.isGooglePlayServicesAvailable()` 探测一次，
 * 之后整条链路都按同一个引擎走，不会中途切换导致重复回调。
 *
 * ## 其它要点
 *  - 间隔默认 3 分钟、最小位移 50 米（满足其一即回调）
 *  - 内部每 5 秒检查一次配置与权限：用户改间隔或刚授予权限，无需重启 Service 即生效
 *  - 连接刚建立时先用 `lastKnownLocation` 补一个点，避免地图空白好几分钟
 */
class LocationCollector(
    context: Context,
    private val repository: TrackerRepository,
    private val prefs: PreferencesManager
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前设备是否可用 Google Play 服务（国行机型通常为 false） */
    private val gmsAvailable: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            false
        }
    }

    /** 引擎 1：GMS 融合定位（懒加载，没 GMS 时根本不碰它） */
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    /** 引擎 2：系统 LocationManager */
    private val locationManager: LocationManager? by lazy {
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private var watchJob: Job? = null

    /** 当前已请求的间隔，用于判断是否需要重新请求 */
    private var currentIntervalMs = -1L

    /** 是否已把缓存位置补报过 */
    @Volatile
    private var sentCachedFix = false

    // ---------------- 方向角推算用的参考点 ----------------
    @Volatile
    private var lastLat = Double.NaN

    @Volatile
    private var lastLng = Double.NaN

    /** 上一次的有效方向；一开始是「未知」 */
    @Volatile
    private var lastBearing = LocationInfo.UNKNOWN_BEARING

    // ---------------- GMS 回调 ----------------

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { publish(it) }
        }
    }

    // ---------------- 系统 LocationManager 回调 ----------------
    // 四个方法全部显式实现：API 30 以下框架会调用 onStatusChanged，
    // 只实现 onLocationChanged 的话在老机型上会抛 AbstractMethodError。

    private val frameworkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publish(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    fun start() {
        if (watchJob?.isActive == true) return

        Log.i(TAG, "定位引擎：${if (gmsAvailable) "GMS 融合定位" else "系统 LocationManager（无 Google 服务）"}")

        watchJob = scope.launch {
            while (true) {
                val settings = prefs.settings.value
                val enabled = settings.reportLocation &&
                    PermissionUtils.hasLocationPermission(appContext)

                if (!enabled) {
                    // 没开上报或没权限：撤掉位置请求，等条件满足再重新请求
                    if (currentIntervalMs != -1L) {
                        stopUpdates()
                        currentIntervalMs = -1L
                        sentCachedFix = false
                    }
                    delay(5_000)
                    continue
                }

                val intervalMs = settings.locationIntervalSec * 1000L
                if (intervalMs != currentIntervalMs) {
                    stopUpdates()
                    requestUpdates(intervalMs)
                    currentIntervalMs = intervalMs
                }

                // 补报一次缓存位置
                if (!sentCachedFix) {
                    sendCachedLocation()
                }

                delay(5_000)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        stopUpdates()
    }

    /** 用户手动刷新 / 连接建立时调用 */
    fun sendCachedLocation() {
        if (!PermissionUtils.hasLocationPermission(appContext)) return
        if (!prefs.settings.value.reportLocation) return

        try {
            if (gmsAvailable) {
                fusedClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            sentCachedFix = true
                            publish(location)
                        }
                    }
                    .addOnFailureListener { Log.w(TAG, "读取缓存位置失败：${it.message}") }
            } else {
                val manager = locationManager ?: return
                // 依次尝试：GPS -> 网络 -> 被动（被动是别的应用定位时顺带给的）
                val last = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                ).firstNotNullOfOrNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                if (last != null) {
                    sentCachedFix = true
                    publish(last)
                }
            }
        } catch (t: SecurityException) {
            Log.w(TAG, "无定位权限：${t.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "读取缓存位置异常：${t.message}")
        }
    }

    // ==================================================================
    // 请求位置更新
    // ==================================================================

    private fun requestUpdates(intervalMs: Long) {
        // 间隔下限保护：太频繁会显著耗电
        val safeInterval = intervalMs.coerceIn(30_000L, 3_600_000L)
        if (gmsAvailable) {
            requestFused(safeInterval)
        } else {
            requestFramework(safeInterval)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFused(safeInterval: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, safeInterval)
            .setMinUpdateDistanceMeters(50f)      // 移动不足 50 米不重复上报
            .setMinUpdateIntervalMillis(safeInterval / 2)
            .setWaitForAccurateLocation(false)    // 不等高精度，避免长时间不回调
            .setMaxUpdateDelayMillis(safeInterval * 2)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            Log.i(TAG, "GMS 定位已请求，间隔 ${safeInterval / 1000} 秒")
        } catch (t: SecurityException) {
            Log.w(TAG, "请求 GMS 定位失败（权限不足）：${t.message}")
        } catch (t: Throwable) {
            // GMS 不可用（例如被卸载/被系统禁用）时降级到系统定位
            Log.w(TAG, "GMS 定位不可用，降级到系统 LocationManager：${t.message}")
            requestFramework(safeInterval)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFramework(safeInterval: Long) {
        val manager = locationManager ?: run {
            Log.w(TAG, "设备没有 LocationManager，无法定位")
            return
        }

        // 同时监听 GPS 与网络：室内靠网络、室外靠 GPS，谁先给就用谁
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            }

        if (providers.isEmpty()) {
            Log.w(TAG, "GPS 与网络定位都未开启，请在系统设置里打开位置信息")
            return
        }

        providers.forEach { provider ->
            try {
                manager.requestLocationUpdates(
                    provider,
                    safeInterval,
                    50f,                        // 最小位移 50 米
                    frameworkListener,
                    Looper.getMainLooper()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "请求 $provider 失败：${t.message}")
            }
        }
        Log.i(TAG, "系统定位已请求（$providers），间隔 ${safeInterval / 1000} 秒")
    }

    private fun stopUpdates() {
        if (gmsAvailable) {
            runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
                .onFailure { Log.w(TAG, "移除 GMS 定位回调失败：${it.message}") }
        }
        runCatching { locationManager?.removeUpdates(frameworkListener) }
            .onFailure { Log.w(TAG, "移除系统定位回调失败：${it.message}") }
    }

    private fun publish(location: Location) {
        // 先算方向（要用到上一个点），再更新参考点
        val bearing = resolveBearing(location)
        rememberPoint(location)

        val info = LocationInfo(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            provider = location.provider ?: if (gmsAvailable) "fused" else "android",
            bearing = bearing,
            timestamp = if (location.time > 0) location.time else System.currentTimeMillis()
        )
        repository.sendLocation(info)
    }

    /**
     * 求行进方向角，三级降级：
     *
     *  1. **系统给的方向**：GPS 在移动中会直接给出航向（`Location.hasBearing()` 为真），最准
     *  2. **自己算**：拿当前点和上一个点做 `bearingBetween`，但要移动超过 [MIN_BEARING_DISTANCE_M]
     *     才有意义 —— 站在原地 GPS 抖动几十厘米算出来的方向是纯噪声
     *  3. **沿用上次**：都拿不到就保持上一次的方向，总比让箭头乱转强
     *
     * 一个额外的好处：手机在原地打转（人转身）时，第 1 条会让箭头跟着转，
     * 这也是「箭头」这个设计想要的效果。
     */
    private fun resolveBearing(location: Location): Float {
        // 1) 系统航向
        if (location.hasBearing() && !location.bearing.isNaN()) {
            return GeoUtils.normalizeBearing(location.bearing)
        }

        // 2) 用上一个点推算
        val prevLat = lastLat
        val prevLng = lastLng
        if (!prevLat.isNaN() && !prevLng.isNaN()) {
            val distance = GeoUtils.distanceMeters(
                prevLat, prevLng, location.latitude, location.longitude
            )
            if (distance >= MIN_BEARING_DISTANCE_M) {
                val bearing = GeoUtils.bearingBetween(
                    prevLat, prevLng, location.latitude, location.longitude
                )
                lastBearing = bearing
                return bearing
            }
        }

        // 3) 沿用上次
        return lastBearing
    }

    /** 记录本次位置作为「上一个点」，供下次算方向 */
    private fun rememberPoint(location: Location) {
        val prevLat = lastLat
        val prevLng = lastLng
        val movedEnough = prevLat.isNaN() || prevLng.isNaN() ||
            GeoUtils.distanceMeters(
                prevLat, prevLng, location.latitude, location.longitude
            ) >= MIN_BEARING_DISTANCE_M

        if (movedEnough) {
            lastLat = location.latitude
            lastLng = location.longitude
        }
    }

    companion object {
        private const val TAG = "LocationCollector"

        /** 小于这个位移不更新方向/参考点，避免 GPS 抖动把箭头甩来甩去 */
        private const val MIN_BEARING_DISTANCE_M = 12f
    }
}
