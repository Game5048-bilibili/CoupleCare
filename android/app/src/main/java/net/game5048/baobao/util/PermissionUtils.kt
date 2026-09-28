package net.game5048.baobao.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限与系统设置跳转工具。
 *
 * 三件“难搞的事”都在这里：
 *  1. PACKAGE_USAGE_STATS —— 特殊权限，只能跳系统设置让用户手动开
 *  2. 电池优化白名单 —— 跳 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 *  3. ColorOS（OPPO/一加/真我）的自启动管理 —— 各家组件名不一样，逐个尝试
 */
object PermissionUtils {

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /** 精确定位或粗略定位任一即可（粗略定位配合 FusedLocation 也能用） */
    fun hasLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * 后台定位权限。Android 10（API 29）起才有这个概念，
     * 且必须**先授予前台定位**，再单独申请这个，否则系统直接拒绝。
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }

    /** Android 13+ 通知需要运行时授权；未授权时常驻通知不会显示，但服务依然在跑 */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * 是否已授予“使用情况访问权限”。
     * 这个权限无法用 requestPermissions 申请，只能读 AppOpsManager 判断。
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        if (mode == android.app.AppOpsManager.MODE_ALLOWED) return true
        // 部分 ROM 会把 MODE_DEFAULT 当作“按清单声明处理”，这里再兜底查一次包权限
        return context.packageManager.checkPermission(
            Manifest.permission.PACKAGE_USAGE_STATS,
            context.packageName
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 是否已在电池优化白名单（true = 不受 Doze 限制，后台更稳） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 跳转
    // ------------------------------------------------------------------

    /** 跳“使用情况访问权限”列表页，用户需手动找到本应用并打开 */
    fun openUsageAccessSettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** 跳电池优化设置；带包名可以直接弹系统确认框 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!safeStart(context, direct)) {
            safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 跳本应用详情页（权限、通知开关都能在这里找到） */
    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        safeStart(context, intent)
    }

    /** 跳定位权限页，方便用户把“仅在使用时允许”改成“始终允许” */
    fun openLocationPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            safeStart(context, intent)
        } else {
            safeStart(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    /**
     * ColorOS / realme UI / OxygenOS 的自启动管理页。
     *
     * 这些页面都不是公开 API，组件名随系统版本变化，因此逐个尝试；
     * 全部失败则退回应用详情页，并在 UI 上给出文字说明。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = listOf(
            // ColorOS 12+ / OPlus
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            // ColorOS 7 ~ 11
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.phonemanager", "com.coloros.phonemanager.startupapp.StartupAppListActivity"),
            // 更老的 OPPO / 一加
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            // vivo / 小米 / 华为的常见入口，多个机型都能用
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )

        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (safeStart(context, intent)) return true
        }
        // 都不行就退回应用详情页，让用户自己去手机管家找
        openAppDetailsSettings(context)
        return false
    }

    /** startActivity 失败（ActivityNotFoundException / SecurityException）时返回 false */
    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }
}
