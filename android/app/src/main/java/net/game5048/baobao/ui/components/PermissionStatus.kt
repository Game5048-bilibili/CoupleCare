package net.game5048.baobao.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.game5048.baobao.util.PermissionUtils

/**
 * 当前权限快照。
 *
 * 权限是「系统状态」而不是「APP 状态」，用户可能随时去系统设置里改，
 * 因此这里在每次 `ON_RESUME`（从设置页返回）时重新读一次。
 */
data class PermissionSnapshot(
    /** 前台定位（粗略或精确） */
    val location: Boolean,
    /** 后台定位（Android 10+ 才有） */
    val backgroundLocation: Boolean,
    /** 通知（Android 13+ 才有） */
    val notification: Boolean,
    /** 使用情况访问权限（特殊权限，只能手动开） */
    val usageStats: Boolean,
    /** 是否已加入电池优化白名单（true = 不会被 Doze 限制） */
    val ignoreBatteryOptimization: Boolean
) {
    /** 定位相关权限是否齐全（含后台） */
    val locationReady: Boolean get() = location && backgroundLocation
}

@Composable
fun rememberPermissionSnapshot(): PermissionSnapshot {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到前台就自增，触发重新读取
    var refreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(refreshTick) {
        PermissionSnapshot(
            location = PermissionUtils.hasLocationPermission(context),
            backgroundLocation = PermissionUtils.hasBackgroundLocationPermission(context),
            notification = PermissionUtils.hasNotificationPermission(context),
            usageStats = PermissionUtils.hasUsageStatsPermission(context),
            ignoreBatteryOptimization = PermissionUtils.isIgnoringBatteryOptimizations(context)
        )
    }
}
