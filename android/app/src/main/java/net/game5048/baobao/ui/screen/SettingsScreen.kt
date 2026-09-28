package net.game5048.baobao.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.game5048.baobao.BuildConfig
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import net.game5048.baobao.ui.components.AMapKeyStatus
import net.game5048.baobao.ui.components.AvatarCropDialog
import net.game5048.baobao.ui.components.AvatarImage
import net.game5048.baobao.ui.components.PermissionSnapshot
import net.game5048.baobao.ui.components.StatusChip
import net.game5048.baobao.util.AvatarStore
import net.game5048.baobao.util.PermissionUtils
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * 从上到下依次是：连接状态 → 配对 → 服务器 → 上报开关与频率 → 保活与权限 → 关于。
 * 顺序是有讲究的：新用户打开这一页，从上往下照着填就能跑起来。
 */
@Composable
fun SettingsScreen(
    settings: PreferencesManager.Settings,
    connectionState: ConnectionState,
    rttMs: Long,
    deviceId: String,
    partnerDeviceId: String?,
    permissions: PermissionSnapshot,
    /** 自己头像的版本时间戳，0 表示还没设置过 */
    ownAvatarAt: Long,
    onSavePairCode: (String) -> Unit,
    onSaveServer: (String, String) -> Unit,
    onToggleReportLocation: (Boolean) -> Unit,
    onToggleReportBattery: (Boolean) -> Unit,
    onToggleReportUsage: (Boolean) -> Unit,
    onLocationIntervalChange: (Int) -> Unit,
    onBatteryIntervalChange: (Int) -> Unit,
    onUsageIntervalChange: (Int) -> Unit,
    onToggleTrack: (Boolean) -> Unit,
    onToggleSelf: (Boolean) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRefreshHistory: () -> Unit,
    /** 裁剪完成后的 256x256 JPEG 字节 */
    onAvatarCropped: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ---------------- 头像：选图 -> 裁剪 -> 上传 ----------------
    // 用系统相册选择器 PickVisualMedia：Android 13+ 走系统 Photo Picker，
    // 不需要申请任何存储权限；低版本自动回退到 ACTION_OPEN_DOCUMENT。
    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingAvatarUri = uri
    }

    val ownAvatarBitmap = remember(ownAvatarAt) {
        if (ownAvatarAt > 0) AvatarStore.load(context, isSelf = true) else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        // ==================== 我的头像 ====================
        item(key = "avatar") {
            SectionCard(
                icon = Icons.Filled.Person,
                title = "我的头像"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        bitmap = ownAvatarBitmap,
                        size = 62.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (ownAvatarBitmap != null) "已设置" else "还没设置头像",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "对方地图上会用这张头像标记你的位置，外面那圈箭头指向你正在走的方向",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) {
                        Text(if (ownAvatarBitmap != null) "更换" else "选择")
                    }
                }
            }
        }

        // ==================== 连接状态 ====================
        item(key = "status") {
            SectionCard(
                icon = Icons.Filled.Wifi,
                title = "连接状态"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        text = connectionState.displayName,
                        containerColor = if (connectionState.isOnline) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (connectionState.isOnline) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (settings.readyToConnect) {
                            "${settings.serverHost}:${settings.serverPort}"
                        } else {
                            "尚未配置服务器"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (rttMs > 0) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "延迟 ${rttMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                KeyValueRow("本机设备 ID", deviceId, copyable = true)
                KeyValueRow(
                    "对方设备 ID",
                    partnerDeviceId ?: "尚未配对上"
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshHistory,
                        enabled = settings.readyToConnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新历史")
                    }

                    if (settings.serviceEnabled) {
                        OutlinedButton(
                            onClick = onStopService,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("停止常驻")
                        }
                    } else {
                        Button(
                            onClick = onStartService,
                            enabled = settings.readyToConnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("开启常驻")
                        }
                    }
                }

                if (!settings.serviceEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "「常驻」关闭时 APP 不会在后台上报，双方都看不到数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // ==================== 配对码 ====================
        item(key = "pair_code") {
            var input by remember(settings.pairCode) { mutableStateOf(settings.pairCode) }

            SectionCard(
                icon = Icons.Filled.Key,
                title = "配对码"
            ) {
                Text(
                    text = "两台手机填完全相同的配对码即可互相绑定。配对码同时用于派生 AES 密钥，" +
                        "建议用 6~12 位数字或字母，不要用太简单的（如 1234）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim().take(32) },
                    label = { Text("配对码") },
                    placeholder = { Text("例如 528520") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onSavePairCode(input) },
                        enabled = input.trim() != settings.pairCode
                    ) {
                        Text("保存配对码")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    if (input.isNotBlank() && input.length < 6) {
                        Text(
                            text = "配对码太短，容易被别人试出来",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (settings.pairCode.isNotBlank()) {
                        Text(
                            text = "已设置，修改后会自动重连",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ==================== 服务器 ====================
        item(key = "server") {
            var host by remember(settings.serverHost) { mutableStateOf(settings.serverHost) }
            var port by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }

            SectionCard(
                icon = Icons.Filled.Storage,
                title = "服务器"
            ) {
                Text(
                    text = "填 Orange Pi 上内网穿透暴露出来的公网地址。比如用 frp 把服务端的 9527 " +
                        "映射到 frps 的 9527，这里就填 frps 所在服务器的域名/IP。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("服务器地址 / 域名") },
                    placeholder = { Text("例如 frp.example.com 或 1.2.3.4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                    label = { Text("端口") },
                    placeholder = { Text("9527") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onSaveServer(host, port) },
                    enabled = host.isNotBlank() && port.isNotBlank()
                ) {
                    Text("保存并重连")
                }
            }
        }

        // ==================== 上报设置 ====================
        item(key = "report") {
            SectionCard(
                icon = Icons.Filled.Tune,
                title = "上报设置"
            ) {
                SwitchRow(
                    title = "上报定位",
                    subtitle = "把你在哪告诉对方（需要定位权限）",
                    checked = settings.reportLocation,
                    onCheckedChange = onToggleReportLocation
                )
                SwitchRow(
                    title = "上报电量",
                    subtitle = "电量变化时 + 定时上报",
                    checked = settings.reportBattery,
                    onCheckedChange = onToggleReportBattery
                )
                SwitchRow(
                    title = "上报使用记录",
                    subtitle = "对方能看到你在用什么 App（需要「使用情况访问」权限）",
                    checked = settings.reportUsage,
                    onCheckedChange = onToggleReportUsage
                )

                Spacer(modifier = Modifier.height(6.dp))

                DiscreteSliderRow(
                    title = "定位间隔",
                    subtitle = "越短越实时，也越耗电。走路/通勤建议 2~5 分钟",
                    options = LOCATION_INTERVAL_OPTIONS,
                    value = settings.locationIntervalSec,
                    format = ::formatSeconds,
                    onChange = onLocationIntervalChange
                )

                DiscreteSliderRow(
                    title = "电量上报间隔",
                    subtitle = "电量变化时会立刻上报，这里只是兜底周期",
                    options = BATTERY_INTERVAL_OPTIONS,
                    value = settings.batteryIntervalSec,
                    format = ::formatSeconds,
                    onChange = onBatteryIntervalChange
                )

                DiscreteSliderRow(
                    title = "使用记录轮询间隔",
                    subtitle = "越小越灵敏，也越费电。30 秒是省电与实时的平衡点",
                    options = USAGE_INTERVAL_OPTIONS,
                    value = settings.usagePollSec,
                    format = ::formatSeconds,
                    onChange = onUsageIntervalChange
                )
            }
        }

        // ==================== 地图显示 ====================
        item(key = "map_display") {
            SectionCard(
                icon = Icons.Filled.Speed,
                title = "地图显示"
            ) {
                SwitchRow(
                    title = "显示对方轨迹",
                    subtitle = "在地图上用连线画出对方最近走过的路",
                    checked = settings.showTrack,
                    onCheckedChange = onToggleTrack
                )
                SwitchRow(
                    title = "显示我的位置",
                    subtitle = "在地图上同时标出你自己（只在本机显示，不会上传给对方之外的人）",
                    checked = settings.showSelf,
                    onCheckedChange = onToggleSelf
                )
            }
        }

        // ==================== 权限与保活 ====================
        item(key = "permissions") {
            SectionCard(
                icon = Icons.Filled.Security,
                title = "权限与保活"
            ) {
                Text(
                    text = "ColorOS 对后台管得很严。下面 6 项全部完成后，APP 才能稳定在后台运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                PermissionRow(
                    title = "通知权限",
                    subtitle = "常驻通知需要它；没授权时服务仍在跑但看不到通知",
                    granted = permissions.notification,
                    onClick = {
                        PermissionUtils.openAppDetailsSettings(context)
                    }
                )

                PermissionRow(
                    title = "定位权限",
                    subtitle = "位置共享的基础",
                    granted = permissions.location,
                    onClick = {
                        PermissionUtils.openAppDetailsSettings(context)
                    }
                )

                PermissionRow(
                    title = "后台定位（始终允许）",
                    subtitle = "不授权的话，锁屏或切到后台位置就断了",
                    granted = permissions.backgroundLocation,
                    onClick = {
                        PermissionUtils.openLocationPermissionSettings(context)
                    }
                )

                PermissionRow(
                    title = "使用情况访问权限",
                    subtitle = "特殊权限，只能在系统设置里手动开",
                    granted = permissions.usageStats,
                    onClick = {
                        PermissionUtils.openUsageAccessSettings(context)
                    }
                )

                PermissionRow(
                    title = "电池优化白名单",
                    subtitle = "加入后系统不会在息屏时冻结本应用",
                    granted = permissions.ignoreBatteryOptimization,
                    onClick = {
                        PermissionUtils.requestIgnoreBatteryOptimizations(context)
                    }
                )

                PermissionRow(
                    title = "自启动 / 后台运行",
                    subtitle = "ColorOS 手机管家里的「自启动」「关联启动」「后台运行」都要允许",
                    granted = null, // 无法用代码检测，只能人工确认
                    onClick = {
                        PermissionUtils.openAutoStartSettings(context)
                    }
                )
            }
        }

        // ==================== 关于 ====================
        item(key = "about") {
            SectionCard(
                icon = Icons.Filled.Info,
                title = "关于"
            ) {
                KeyValueRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueRow("包名", BuildConfig.APPLICATION_ID)
                KeyValueRow(
                    "高德地图 Key",
                    if (AMapKeyStatus.configured) "已配置" else "未配置（地图无法显示）"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "数据全部经过 AES-256-GCM 加密，密钥由配对码 PBKDF2 派生，" +
                        "服务端只能看到密文和「谁在什么时候发了什么类型」。\n" +
                        "地图使用高德地图 SDK：定位拿到的 WGS-84 坐标会先在本地转成 " +
                        "GCJ-02 再绘制，两边看到的都是真实位置。\n" +
                        "高德 SDK 会收集设备信息用于地图渲染，仅在本机与高德服务器之间发生，" +
                        "与你我之间的加密通道无关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

            item(key = "bottom") { Spacer(modifier = Modifier.height(12.dp)) }
        }

        // 裁剪对话框挂在 Box 外层，避免它随 LazyColumn 的 item 被回收
        pendingAvatarUri?.let { uri ->
            AvatarCropDialog(
                sourceUri = uri,
                onDismiss = { pendingAvatarUri = null },
                onCropped = { jpeg ->
                    pendingAvatarUri = null
                    onAvatarCropped(jpeg)
                }
            )
        }
    }
}

// ======================================================================
// 通用小组件
// ======================================================================

/** 带标题和图标的设置分组卡片 */
@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** 开关一行 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 离散滑块。
 *
 * 用「档位」而不是连续值，是为了避免用户拖出一个奇怪的数字（比如 47 秒），
 * 而且每档都对应一个明确的耗电预期。
 */
@Composable
private fun DiscreteSliderRow(
    title: String,
    subtitle: String,
    options: List<Int>,
    value: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit
) {
    var sliderIndex by remember(value) {
        mutableFloatStateOf(options.indexOf(value).coerceAtLeast(0).toFloat())
    }
    val currentIndex = sliderIndex.roundToInt().coerceIn(options.indices)
    val currentValue = options[currentIndex]

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = format(currentValue),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = sliderIndex,
            onValueChange = { sliderIndex = it },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            onValueChangeFinished = {
                onChange(options[sliderIndex.roundToInt().coerceIn(options.indices)])
            }
        )
    }
}

/**
 * 权限一行：左边是状态图标，右边是「去设置」按钮。
 *
 * @param granted null 表示无法检测（如自启动），显示为灰色问号
 */
@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean?,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (granted) {
                true -> Icons.Filled.CheckCircle
                false -> Icons.Filled.Warning
                null -> Icons.Filled.Info
            },
            contentDescription = null,
            tint = when (granted) {
                true -> scheme.primary
                false -> scheme.error
                null -> scheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (granted == true) {
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary
            )
        } else {
            TextButton(onClick = onClick) {
                Text(if (granted == false) "去开启" else "去查看")
            }
        }
    }
}

/** 「标签 — 值」一行，值可以复制 */
@Composable
private fun KeyValueRow(
    label: String,
    value: String,
    copyable: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (copyable) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(value)) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ======================================================================
// 档位与格式化
// ======================================================================

private val LOCATION_INTERVAL_OPTIONS = listOf(30, 60, 120, 180, 300, 600, 900, 1800)
private val BATTERY_INTERVAL_OPTIONS = listOf(60, 120, 300, 600, 900, 1800, 3600)
private val USAGE_INTERVAL_OPTIONS = listOf(10, 20, 30, 60, 120, 300)

/** 秒 -> "30 秒" / "3 分钟" / "1 小时" */
private fun formatSeconds(seconds: Int): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds % 3600 == 0 -> "${seconds / 3600} 小时"
    else -> "${seconds / 60} 分钟"
}
