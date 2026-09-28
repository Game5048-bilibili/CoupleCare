package net.game5048.baobao.util

import android.location.Location
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * 地理计算小工具。
 *
 * ## 为什么需要坐标纠偏
 * 手机的 GPS / 网络定位给出的都是 **WGS-84** 坐标（国际标准）。
 * 而国内地图（高德、腾讯、百度）用的是 **GCJ-02**（俗称「火星坐标系」，国家测绘局加密偏移）。
 * 两者在国内相差 **几十米到几百米**，直接把 WGS-84 画到高德地图上，
 * 标记会偏离真实位置，看起来「人在马路对面」。
 *
 * 所以约定：
 *  - **协议里传的、服务端存的、算距离用的，一律是 WGS-84 原始坐标**（不做任何加工）
 *  - **只有画到高德地图上之前，才调用 [wgs84ToGcj02] 纠偏**
 *
 * 这样数据是「干净」的：以后想换地图（百度要再转 BD-09）或者做别的分析都不受影响。
 */
object GeoUtils {

    /** 克拉索夫斯基椭球长半轴（米） */
    private const val EARTH_A = 6378245.0

    /** 椭球偏心率平方 */
    private const val EARTH_EE = 0.00669342162296594323

    private val distanceResult = FloatArray(3)

    // ------------------------------------------------------------------
    // 距离 / 方向
    // ------------------------------------------------------------------

    /**
     * 两点间距离（米）。使用 Android 自带的 Vincenty 实现，比手写 Haversine 更准。
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        Location.distanceBetween(lat1, lng1, lat2, lng2, distanceResult)
        return distanceResult[0]
    }

    /**
     * 从 A 点看向 B 点的方位角（0~360，正北为 0，顺时针）。
     *
     * `Location.distanceBetween` 会顺带算出起始方位角，放在 results[1] 里，
     * 比手写球面三角公式少一次三角函数误差。
     */
    fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        Location.distanceBetween(lat1, lng1, lat2, lng2, distanceResult)
        val bearing = distanceResult[1]
        return if (bearing < 0) bearing + 360f else bearing
    }

    /** 人类可读的距离："320 米" / "1.2 公里" / "1234 公里" */
    fun formatDistance(meters: Float): String = when {
        meters < 1000f -> "${meters.roundToInt()} 米"
        meters < 100_000f -> String.format(java.util.Locale.CHINA, "%.1f 公里", meters / 1000f)
        else -> "${(meters / 1000f).roundToInt()} 公里"
    }

    /** 把任意角度归一到 [0, 360) */
    fun normalizeBearing(bearing: Float): Float {
        if (bearing.isNaN()) return 0f
        var value = bearing % 360f
        if (value < 0) value += 360f
        return value
    }

    // ------------------------------------------------------------------
    // WGS-84 -> GCJ-02
    // ------------------------------------------------------------------

    /**
     * WGS-84 转 GCJ-02（火星坐标）。
     *
     * 算法是公开的标准实现，精度在米级，足够地图展示用。
     * 注意：国外坐标不做偏移（[outOfChina] 判定），直接原样返回。
     *
     * @return `doubleArrayOf(lat, lng)`
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) {
            return doubleArrayOf(lat, lng)
        }

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EARTH_EE * magic * magic
        val sqrtMagic = sqrt(magic)

        dLat = (dLat * 180.0) / ((EARTH_A * (1 - EARTH_EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (EARTH_A / sqrtMagic * cos(radLat) * Math.PI)

        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    /** 粗略判断是否在中国境外（境外不做加密偏移） */
    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
