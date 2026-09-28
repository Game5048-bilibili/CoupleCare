package net.game5048.baobao.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import net.game5048.baobao.BuildConfig
import net.game5048.baobao.data.model.LocationInfo
import net.game5048.baobao.util.AMapPrivacy
import net.game5048.baobao.util.AvatarMarkerFactory
import net.game5048.baobao.util.GeoUtils

/**
 * 高德地图组件（Compose 封装）。
 *
 * ## 为什么用高德而不是 OSMDroid
 * 国内直连 OpenStreetMap 瓦片经常被限速甚至打不开，而高德在国行手机上
 * 不需要 Google 服务框架、瓦片加载快、路网数据对国内更准。
 *
 * ## 三个必须注意的点
 *
 * 1. **坐标纠偏**：手机定位给的是 WGS-84，高德用的是 GCJ-02，国内差几十到几百米。
 *    协议和服务端里存的都是原始 WGS-84，只有在这里、画图之前才转成 GCJ-02
 *    （见 `GeoUtils.wgs84ToGcj02`）。**千万别在发送端转**，否则距离计算和以后
 *    换地图都会被污染。
 *
 * 2. **隐私合规**：高德 SDK 强制要求在**创建 MapView 之前**调用
 *    `MapsInitializer.updatePrivacyShow/updatePrivacyAgree`，否则地图不渲染并打错误日志。
 *    这里在 [ensurePrivacyAgreed] 中做，`BaobaoApp` 也会在启动时先调一次兜底。
 *
 * 3. **API Key**：高德要求 Key 与「包名 + 签名 SHA1」绑定。Key 通过
 *    `gradle.properties` 的 `AMAP_API_KEY` 注入到 Manifest 的 meta-data。
 *    没配 Key 时地图会显示水印错误，[isKeyConfigured] 供 UI 提前提示。
 *
 * ## 图层结构
 * 每个位置放两个 Marker：
 *  - 头像 Marker（不旋转）：圆形头像，照片不会跟着转
 *  - 方向环 Marker（`rotateAngle = bearing`）：头像外面一圈细环 + 箭头，指向行进方向
 */
@Composable
fun PartnerMap(
    partnerLocation: LocationInfo?,
    ownLocation: LocationInfo?,
    track: List<LocationInfo>,
    showTrack: Boolean,
    showSelf: Boolean,
    partnerLabel: String,
    partnerAvatar: Bitmap?,
    ownAvatar: Bitmap?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    var followPartner by remember { mutableStateOf(true) }

    val density = context.resources.displayMetrics.density
    val avatarSizePx = (44 * density).toInt()
    val ringSizePx = (avatarSizePx * AvatarMarkerFactory.RING_SCALE).toInt()
    val selfDotSizePx = (18 * density).toInt()

    // 记住 overlay 引用，避免每次重组都重新创建（AMap 上 Marker 创建有开销）
    val holder = remember { MapOverlayHolder() }

    val mapView = remember {
        AMapPrivacy.ensure(context)
        MapView(context).apply {
            // AMap 的 MapView 必须显式走一遍 onCreate，否则内部地图引擎不会初始化
            onCreate(Bundle())
        }
    }

    // ---------------- 生命周期 ----------------
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // onDestroy 会释放地图引擎与瓦片线程；漏掉它会泄漏整个 Activity
            mapView.onDestroy()
        }
    }

    // ---------------- 跟随对方 ----------------
    LaunchedEffect(partnerLocation?.timestamp, followPartner) {
        val location = partnerLocation ?: return@LaunchedEffect
        if (!followPartner) return@LaunchedEffect
        val target = toGcj(location)
        val aMap = mapView.safeMap() ?: return@LaunchedEffect
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16f))
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            // 注意：AndroidView 的 factory 参数是 (Context) -> T
            factory = { _ ->
                mapView.apply {
                    safeMap()?.let { aMap ->
                        aMap.mapType = AMap.MAP_TYPE_NORMAL
                        aMap.uiSettings.isZoomControlsEnabled = false
                        aMap.uiSettings.isMyLocationButtonEnabled = false

                        holder.trackLine = aMap.addPolyline(
                            PolylineOptions()
                                .width(9f)
                                .color(scheme.primary.copy(alpha = 0.85f).toArgb())
                        )

                        holder.selfMarker = aMap.addMarker(
                            MarkerOptions()
                                .position(DEFAULT_CENTER)
                                .anchor(0.5f, 0.5f)
                                .icon(
                                    BitmapDescriptorFactory.fromBitmap(
                                        AvatarMarkerFactory.buildSelfDot(selfDotSizePx)
                                    )
                                )
                                .zIndex(3f)
                                .visible(false)
                        )

                        // 方向环在下层，头像在上层，避免箭头压到脸上
                        holder.partnerRingMarker = aMap.addMarker(
                            MarkerOptions()
                                .position(DEFAULT_CENTER)
                                .anchor(0.5f, 0.5f)
                                .icon(
                                    BitmapDescriptorFactory.fromBitmap(
                                        AvatarMarkerFactory.buildHeadingRing(ringSizePx)
                                    )
                                )
                                .zIndex(1f)
                                .visible(false)
                        )

                        holder.partnerAvatarMarker = aMap.addMarker(
                            MarkerOptions()
                                .position(DEFAULT_CENTER)
                                .anchor(0.5f, 0.5f)
                                .icon(
                                    BitmapDescriptorFactory.fromBitmap(
                                        AvatarMarkerFactory.buildAvatarMarker(
                                            partnerAvatar, avatarSizePx
                                        )
                                    )
                                )
                                .zIndex(2f)
                                .visible(false)
                        )
                    }
                }
            },
            update = { _ ->
                val aMap = mapView.safeMap()
                if (aMap != null) {
                    updateOverlays(
                        holder = holder,
                        aMap = aMap,
                        partnerLocation = partnerLocation,
                        ownLocation = ownLocation,
                        track = track,
                        showTrack = showTrack,
                        showSelf = showSelf,
                        partnerLabel = partnerLabel,
                        partnerAvatar = partnerAvatar,
                        ownAvatar = ownAvatar,
                        avatarSizePx = avatarSizePx,
                        ringSizePx = ringSizePx
                    )
                }
            }
        )

        // 重新跟随按钮
        FilledTonalIconButton(
            onClick = {
                followPartner = true
                partnerLocation?.let {
                    mapView.safeMap()?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(toGcj(it), 16f)
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp)
        ) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = "回到对方位置",
                tint = if (followPartner) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

// ======================================================================
// 内部实现
// ======================================================================

/** 持有地图上的各个 overlay，避免每次重组都重建 */
private class MapOverlayHolder {
    var trackLine: Polyline? = null
    var selfMarker: Marker? = null
    var partnerAvatarMarker: Marker? = null
    var partnerRingMarker: Marker? = null

    /** 上一次设置到头像 Marker 上的位图，用于避免无意义的重建 */
    var lastAvatarRef: Bitmap? = null
    var lastOwnAvatarRef: Bitmap? = null

    /** 是否已经做过「首次自动跳到对方位置」，只做一次 */
    var cameraInitialized: Boolean = false
}

/** 北京天安门，作为拿到第一个坐标之前的默认视角 */
private val DEFAULT_CENTER = LatLng(39.9087, 116.3975)

/** WGS-84 -> GCJ-02。协议里全是 WGS-84，只有这里转 */
private fun toGcj(location: LocationInfo): LatLng {
    val converted = GeoUtils.wgs84ToGcj02(location.lat, location.lng)
    return LatLng(converted[0], converted[1])
}

/**
 * 安全获取 AMap 实例。
 *
 * `MapView.getMap()` 在 Key 无效 / 初始化失败时可能返回 null，
 * 直接当非空用会在低端机上崩，所以统一走这里。
 */
private fun MapView.safeMap(): AMap? = runCatching { this.map }.getOrNull()

private fun updateOverlays(
    holder: MapOverlayHolder,
    aMap: AMap,
    partnerLocation: LocationInfo?,
    ownLocation: LocationInfo?,
    track: List<LocationInfo>,
    showTrack: Boolean,
    showSelf: Boolean,
    partnerLabel: String,
    partnerAvatar: Bitmap?,
    ownAvatar: Bitmap?,
    avatarSizePx: Int,
    ringSizePx: Int
) {
    // ---------------- 轨迹 ----------------
    val line = holder.trackLine
    if (line != null) {
        if (showTrack && track.size >= 2) {
            line.points = track.map { toGcj(it) }
            line.isVisible = true
        } else {
            line.isVisible = false
        }
    }

    // ---------------- 对方 ----------------
    val partner = partnerLocation
    if (partner != null) {
        val position = toGcj(partner)
        holder.partnerAvatarMarker?.position = position
        holder.partnerRingMarker?.position = position

        // 只有头像位图真的换了才重新 setIcon（setIcon 会触发一次纹理上传）
        if (holder.lastAvatarRef !== partnerAvatar) {
            holder.lastAvatarRef = partnerAvatar
            // 注意：高德的 Marker 只有 setIcon()，没有 getIcon()，所以不能用属性语法
            // （Kotlin 只为「有 getter」的 Java 方法生成合成属性）
            holder.partnerAvatarMarker?.setIcon(
                BitmapDescriptorFactory.fromBitmap(
                    AvatarMarkerFactory.buildAvatarMarker(partnerAvatar, avatarSizePx)
                )
            )
        }

        holder.partnerAvatarMarker?.title = partnerLabel.ifBlank { "对方" }
        // bearing < 0 表示方向未知，此时箭头回到正北，视觉上表达「不知道方向」
        val bearing = partner.bearing
        holder.partnerRingMarker?.rotateAngle =
            if (bearing >= 0) AvatarMarkerFactory.toAmapRotation(bearing) else 0f

        holder.partnerAvatarMarker?.isVisible = true
        holder.partnerRingMarker?.isVisible = true
    } else {
        holder.partnerAvatarMarker?.isVisible = false
        holder.partnerRingMarker?.isVisible = false
    }

    // ---------------- 自己 ----------------
    val self = ownLocation
    if (self != null && showSelf) {
        holder.selfMarker?.position = toGcj(self)
        if (holder.lastOwnAvatarRef !== ownAvatar) {
            holder.lastOwnAvatarRef = ownAvatar
            holder.selfMarker?.setIcon(
                BitmapDescriptorFactory.fromBitmap(
                    AvatarMarkerFactory.buildAvatarMarker(
                        ownAvatar,
                        (avatarSizePx * 0.78f).toInt(),
                        ringColor = android.graphics.Color.WHITE
                    )
                )
            )
        }
        holder.selfMarker?.isVisible = true
    } else {
        holder.selfMarker?.isVisible = false
    }

    // 初始视角：第一次拿到对方位置时直接跳过去，避免用户看到一片空白
    if (partner != null && !holder.cameraInitialized) {
        holder.cameraInitialized = true
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(toGcj(partner), 16f))
    }
}

@Volatile
private var privacyAgreed = false

private val PartnerMapLock = Any()

/** 判断是否已经在 gradle.properties 里配好高德 Key（MapScreen 用来提示用户） */
object AMapKeyStatus {
    val configured: Boolean
        get() = BuildConfig.AMAP_API_KEY.isNotBlank()
}
