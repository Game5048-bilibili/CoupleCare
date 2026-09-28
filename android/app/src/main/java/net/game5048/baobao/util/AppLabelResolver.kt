package net.game5048.baobao.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 包名 -> 应用名 / 图标。
 *
 * 采集器需要把 `app_label` 上报给对方（对方手机不一定装了同一个 App，
 * 只有包名是没用的），UI 时间线需要显示图标。
 *
 * 用 [LruCache] 缓存，避免在列表里滚动时反复走 PackageManager（很慢）。
 */
object AppLabelResolver {

    private const val CACHE_SIZE = 256

    private val labelCache = LruCache<String, String>(CACHE_SIZE)
    private val iconCache = LruCache<String, Drawable>(CACHE_SIZE)

    /** 系统桌面/系统 UI 等内部包名，上报出去没有意义 */
    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.oppo.launcher",
        "com.coloros.launcher"
    )

    fun isIgnored(packageName: String): Boolean =
        packageName.isBlank() || packageName in IGNORED_PACKAGES

    /** 应用显示名；解析失败时退化为包名，绝不抛异常 */
    fun label(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        labelCache.get(packageName)?.let { return it }
        val name = try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            packageName
        }
        labelCache.put(packageName, name)
        return name
    }

    /** 应用图标；解析失败返回 null，UI 用占位图标 */
    fun icon(context: Context, packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        iconCache.get(packageName)?.let { return it }
        val drawable = try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationIcon(info)
        } catch (t: Throwable) {
            null
        }
        if (drawable != null) iconCache.put(packageName, drawable)
        return drawable
    }
}
