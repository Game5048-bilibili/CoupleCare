package net.game5048.baobao.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.data.model.PartnerState
import net.game5048.baobao.ui.components.AMapKeyStatus
import net.game5048.baobao.ui.components.BatteryCard
import net.game5048.baobao.ui.components.PartnerMap
import net.game5048.baobao.ui.components.StatusChip
import net.game5048.baobao.util.AvatarStore
import net.game5048.baobao.util.GeoUtils
import net.game5048.baobao.util.TimeFormatter

/**
 * 地图页。
 *
 * 全屏高德地图打底，顶部压一张状态卡片（对方电量 + 最后更新），
 * 左下角一条小信息条（相距多远 / 精度 / 轨迹点数 / 数据时间）。
 *
 * 之所以把状态卡压在地图上而不是另起一屏：打开 APP 就能同时看到
 * “对象在哪” 和 “对象手机还有多少电”，这两个是最高频的信息。
 */
@Composable
fun MapScreen(
    partnerState: PartnerState,
    connectionState: ConnectionState,
    settings: PreferencesManager.Settings,
    onOpenSettings: () -> Unit,
    onRefreshHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 头像用「版本号」做 key 去读文件，而不是把 Bitmap 放进 StateFlow：
    // 换头像时 partnerAvatarAt 变化 -> remember 失效 -> 重新读盘，逻辑简单且不占常驻内存
    val partnerAvatar = remember(partnerState.partnerAvatarAt) {
        if (partnerState.partnerAvatarAt > 0) AvatarStore.load(context, isSelf = false) else null
    }
    val ownAvatar = remember(partnerState.ownAvatarAt) {
        if (partnerState.ownAvatarAt > 0) AvatarStore.load(context, isSelf = true) else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        // --------------------- 地图本体 ---------------------
        PartnerMap(
            partnerLocation = partnerState.location,
            ownLocation = partnerState.ownLocation,
            track = partnerState.track,
            showTrack = settings.showTrack,
            showSelf = settings.showSelf,
            partnerLabel = "对方",
            partnerAvatar = partnerAvatar,
            ownAvatar = ownAvatar,
            modifier = Modifier.fillMaxSize()
        )

        // --------------------- 顶部状态卡 ---------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            BatteryCard(
                battery = partnerState.battery,
                connectionState = connectionState,
                partnerOnline = partnerState.partnerOnline,
                partnerLabel = if (partnerState.paired) "对方" else "尚未配对",
                currentApp = partnerState.currentForegroundApp
            )

            // 还没配置好服务器/配对码时，直接在地图上给出入口
            AnimatedVisibility(visible = !settings.readyToConnect) {
                Spacer(modifier = Modifier.height(8.dp))
                SetupPromptCard(onOpenSettings = onOpenSettings)
            }

            // 高德 Key 没配的话地图是空白的，必须明确告诉用户原因
            AnimatedVisibility(visible = !AMapKeyStatus.configured) {
                Spacer(modifier = Modifier.height(8.dp))
                AMapKeyPromptCard()
            }
        }

        // --------------------- 底部信息条 ---------------------
        val location = partnerState.location
        if (location != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 24.dp, end = 88.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoChip(
                        icon = Icons.Filled.LocationOn,
                        text = TimeFormatter.relative(location.timestamp)
                    )

                    if (location.accuracy > 0) {
                        InfoChip(
                            icon = Icons.Filled.GpsFixed,
                            text = "±${location.accuracy.toInt()}m"
                        )
                    }

                    if (settings.showTrack && partnerState.track.size >= 2) {
                        InfoChip(
                            icon = Icons.Filled.Timeline,
                            text = "${partnerState.track.size} 点"
                        )
                    }

                    // 双方都在国内时这个距离挺有意义的（“相距 1280 公里”）
                    val self = partnerState.ownLocation
                    if (self != null) {
                        val distance = GeoUtils.distanceMeters(
                            self.lat, self.lng, location.lat, location.lng
                        )
                        if (distance > 100f) {
                            StatusChip(
                                text = "相距 ${GeoUtils.formatDistance(distance)}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // --------------------- 无数据提示 ---------------------
        if (location == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (partnerState.paired) "还没有收到对方的位置" else "还没有配对成功",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (partnerState.paired) {
                            "对方开启定位上报后，这里就会出现 TA 的位置"
                        } else {
                            "两台手机填同一个配对码即可互相看到"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRefreshHistory) {
                        Text("拉取历史轨迹")
                    }
                }
            }
        }
    }
}

/** 未配置时的引导卡片 */
@Composable
private fun SetupPromptCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "还差一步就能用了",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "填写服务器地址和配对码，然后打开后台常驻",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
    }
}

/** 高德 API Key 未配置时的提示卡片 */
@Composable
private fun AMapKeyPromptCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "地图还没配置高德 Key",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "去高德开放平台申请 Key（绑定包名和签名 SHA1），" +
                        "填进 android/gradle.properties 的 AMAP_API_KEY 后重新编译",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/** 底部信息条里的小图标 + 文字 */
@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
