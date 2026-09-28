package net.game5048.baobao.util

import android.content.Context
import android.util.Log
import com.amap.api.maps.MapsInitializer

/**
 * 高德地图 SDK 的隐私合规开关。
 *
 * ## 为什么必须要有这一步
 * 自 2021 年起，高德 SDK 强制要求在**任何地图能力被调用之前**声明「是否已向用户展示并取得同意」。
 * 漏掉的话地图不会渲染，控制台只会反复打印提示，排查起来很费时间。
 *
 * ## 本 APP 的实际情况
 * 这是两个人自己用的私密工具，双方都清楚 APP 会采集并互报位置——知情且同意。
 * 因此这里直接置为已同意。**如果以后要给别人用或者上架，必须改成真正的弹窗确认，
 * 并在隐私政策里写明高德 SDK 会收集设备信息（设备号、网络状态等）用于地图渲染。**
 */
object AMapPrivacy {

    private const val TAG = "AMapPrivacy"

    @Volatile
    private var agreed = false

    private val lock = Any()

    /**
     * 幂等：Application 启动时先调一次，地图组件创建前再调一次兜底。
     * 任何异常都不能让 APP 崩，最坏情况只是地图显示不出来。
     */
    fun ensure(context: Context) {
        if (agreed) return
        synchronized(lock) {
            if (agreed) return
            try {
                val appContext = context.applicationContext

                // updatePrivacyShow(上下文, 隐私政策中是否包含高德SDK说明, 是否已向用户展示)
                MapsInitializer.updatePrivacyShow(appContext, true, true)
                // updatePrivacyAgree(上下文, 用户是否已同意)
                MapsInitializer.updatePrivacyAgree(appContext, true)

                agreed = true
                Log.i(TAG, "高德 SDK 隐私合规已声明")
            } catch (t: Throwable) {
                // 常见于：SDK 版本不匹配（方法签名变了）、Key 未配置。
                // 这里只记录，交给地图自己显示错误，不要让整个 APP 起不来。
                Log.w(TAG, "高德隐私合规初始化失败：${t.javaClass.simpleName} - ${t.message}")
            }
        }
    }
}
