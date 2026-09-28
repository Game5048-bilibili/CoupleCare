package net.game5048.baobao.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.game5048.baobao.data.model.BatteryInfo
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.ui.theme.LOW_BATTERY_THRESHOLD
import net.game5048.baobao.util.TimeFormatter

/**
 * 顶部状态卡片：环形电量 + 充电状态 + 最后更新时间 + 当前前台应用。
 *
 * 这是打开 APP 第一眼看到的东西，所以信息密度要刚刚好：
 * 最重要的电量放在左边用大号环形图，其余信息用小字排在右边。
 */
@Composable
fun BatteryCard(
    battery: BatteryInfo?,
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    partnerLabel: String,
    currentApp: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatteryGauge(
                level = battery?.level,
                charging = battery?.charging == true
            )

            Spacer(modifier = Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = partnerLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusChip(
                        text = if (partnerOnline) "在线" else "离线",
                        containerColor = if (partnerOnline) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (partnerOnline) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                InfoLine(
                    icon = {
                        when {
                            battery == null -> Icon(
                                Icons.Filled.BatteryFull,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )

                            battery.charging -> Icon(
                                Icons.Filled.BatteryChargingFull,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )

                            else -> Icon(
                                Icons.Filled.BatteryFull,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    },
                    text = when {
                        battery == null -> "等待对方数据…"
                        battery.charging -> "${battery.level}% · 正在充电"
                        else -> "${battery.level}% · 未充电"
                    }
                )

                Spacer(modifier = Modifier.height(2.dp))

                InfoLine(
                    icon = {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    text = if (battery != null) {
                        "更新于 ${TimeFormatter.relative(battery.timestamp)}"
                    } else {
                        "尚未收到任何上报"
                    }
                )

                if (!currentApp.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    InfoLine(
                        icon = {
                            Icon(
                                Icons.Filled.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        text = "正在使用 $currentApp"
                    )
                }

                if (connectionState != ConnectionState.AUTHENTICATED) {
                    Spacer(modifier = Modifier.height(2.dp))
                    InfoLine(
                        icon = {
                            Icon(
                                if (connectionState == ConnectionState.STOPPED) {
                                    Icons.Filled.CloudOff
                                } else {
                                    Icons.Filled.Wifi
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        text = "服务器${connectionState.displayName}"
                    )
                }
            }
        }
    }
}

/**
 * 环形电量表。用 Canvas 手绘而不是 CircularProgressIndicator：
 *  - 起点角度做成 135°（正下方留口），视觉上更像仪表盘
 *  - 充电时用青色、低电量用橙色，一眼看出异常
 */
@Composable
fun BatteryGauge(
    level: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 78.dp,
    strokeWidth: Dp = 9.dp
) {
    val scheme = MaterialTheme.colorScheme
    val progress = ((level ?: 0).coerceIn(0, 100)) / 100f

    val barColor = when {
        level == null -> scheme.outlineVariant
        charging -> Color(0xFF00897B)
        level <= LOW_BATTERY_THRESHOLD -> Color(0xFFD84315)
        else -> scheme.primary
    }

    // 数值平滑过渡，避免跳变
    val animated by animateFloatAsState(targetValue = progress, label = "batteryProgress")

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)

            // 底槽
            drawArc(
                color = scheme.outlineVariant.copy(alpha = 0.5f),
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // 进度
            if (level != null) {
                drawArc(
                    color = barColor,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = level?.let { "$it%" } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (charging) {
                Icon(
                    Icons.Filled.FlashOn,
                    contentDescription = "充电中",
                    tint = barColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** 小圆角标签，用于“在线/离线/充电中”等状态 */
@Composable
fun StatusChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 图标 + 文字的一行信息 */
@Composable
private fun InfoLine(
    icon: @Composable () -> Unit,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) { icon() }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 仪表盘起始角：135° 即从左下开始，顺时针扫 270° */
private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
