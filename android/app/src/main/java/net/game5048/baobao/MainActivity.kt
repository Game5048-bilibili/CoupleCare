package net.game5048.baobao

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.game5048.baobao.ui.MainViewModel
import net.game5048.baobao.ui.components.StatusChip
import net.game5048.baobao.ui.components.rememberPermissionSnapshot
import net.game5048.baobao.ui.screen.MapScreen
import net.game5048.baobao.ui.screen.SettingsScreen
import net.game5048.baobao.ui.screen.UsageScreen
import net.game5048.baobao.ui.theme.BaobaoTheme
import net.game5048.baobao.util.PermissionUtils

/**
 * 唯一的 Activity。
 *
 * 整个 APP 就是一个 Compose 单页：底部三个 Tab（地图 / 使用记录 / 设置），
 * 用 [rememberSaveable] 记录当前 Tab，不引入 Navigation 组件 —— 三个平级页面
 * 不需要路由栈，加了反而是负担。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏/异形屏适配：内容延伸到状态栏与导航栏下面，由 Scaffold 自己处理内边距
        enableEdgeToEdge()

        setContent {
            BaobaoTheme {
                RootScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScreen(vm: MainViewModel = viewModel()) {

    val partnerState by vm.partnerState.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rttMs by vm.rttMs.collectAsStateWithLifecycle()
    val permissions = rememberPermissionSnapshot()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_MAP) }

    // 服务端返回的错误（配对码不一致、请求非法……）用 Snackbar 提示
    LaunchedEffect(Unit) {
        vm.errors.collect { message ->
            snackbarHostState.showSnackbar(message)
            vm.clearError()
        }
    }

    // ---------------- 首次进入引导授权 ----------------
    // 只申请「运行时权限」；后台定位和使用情况访问是特殊权限，无法弹窗申请，
    // 统一放到设置页用按钮跳转（Android 11+ 的规范做法）。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果会在回到前台时由 rememberPermissionSnapshot 自动刷新 */ }

    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (requestedOnce) return@LaunchedEffect
        requestedOnce = true

        val toRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !permissions.notification
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!permissions.location) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        // 前台定位和后台定位不能一起申请：Android 11+ 要求先拿到前台定位，
        // 再单独引导用户去设置里选「始终允许」，否则会一次性被拒绝。
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "异地之约",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        SpacerWidth(8.dp)
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
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshHistory() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新历史")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_MAP,
                    onClick = { selectedTab = TAB_MAP },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text("地图") }
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_USAGE,
                    onClick = { selectedTab = TAB_USAGE },
                    icon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                    label = { Text("使用记录") }
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 配置好了但没开常驻 —— 这是最常见的“为什么没数据”的原因，直接挂在顶部提醒
            if (settings.readyToConnect && !settings.serviceEnabled) {
                ServiceBanner(onStart = { vm.startService() })
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    TAB_MAP -> MapScreen(
                        partnerState = partnerState,
                        connectionState = connectionState,
                        settings = settings,
                        onOpenSettings = { selectedTab = TAB_SETTINGS },
                        onRefreshHistory = { vm.refreshHistory() }
                    )

                    TAB_USAGE -> UsageScreen(
                        partnerState = partnerState,
                        settings = settings,
                        usagePermissionGranted = permissions.usageStats,
                        onRequestUsagePermission = {
                            PermissionUtils.openUsageAccessSettings(context)
                        },
                        onRefresh = { vm.refreshHistory() }
                    )

                    else -> SettingsScreen(
                        settings = settings,
                        connectionState = connectionState,
                        rttMs = rttMs,
                        deviceId = vm.deviceId,
                        partnerDeviceId = partnerState.partnerDeviceId,
                        permissions = permissions,
                        ownAvatarAt = partnerState.ownAvatarAt,
                        onSavePairCode = { vm.savePairCode(it) },
                        onSaveServer = { host, port -> vm.saveServer(host, port) },
                        onToggleReportLocation = { vm.setReportLocation(it) },
                        onToggleReportBattery = { vm.setReportBattery(it) },
                        onToggleReportUsage = { vm.setReportUsage(it) },
                        onLocationIntervalChange = { vm.setLocationInterval(it) },
                        onBatteryIntervalChange = { vm.setBatteryInterval(it) },
                        onUsageIntervalChange = { vm.setUsagePollInterval(it) },
                        onToggleTrack = { vm.setShowTrack(it) },
                        onToggleSelf = { vm.setShowSelf(it) },
                        onStartService = { vm.startService() },
                        onStopService = { vm.stopService() },
                        onRefreshHistory = { vm.refreshHistory() },
                        onAvatarCropped = { jpeg -> vm.updateAvatar(jpeg) }
                    )
                }
            }
        }
    }
}

/** 未开启常驻时的顶部提醒条 */
@Composable
private fun ServiceBanner(onStart: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "后台常驻未开启，双方看不到实时数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStart) {
                Text("立即开启")
            }
        }
    }
}

/** 小工具：固定宽度占位（避免为了一个 Spacer 引入额外 import 混乱） */
@Composable
private fun SpacerWidth(width: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(width))
}

private const val TAB_MAP = 0
private const val TAB_USAGE = 1
private const val TAB_SETTINGS = 2
