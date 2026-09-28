package net.game5048.baobao

import android.app.Application
import android.util.Log
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.repository.TrackerRepository
import net.game5048.baobao.service.NotificationHelper
import net.game5048.baobao.service.TrackerService
import net.game5048.baobao.util.AMapPrivacy

/**
 * Application 入口。
 *
 * 只做四件事：
 *  1. 声明高德 SDK 隐私合规（必须在任何地图调用之前，越早越好）
 *  2. 创建通知渠道（必须在任何通知发出前完成）
 *  3. 预热 [TrackerRepository]（它内部会订阅 TcpClient 的消息流，越早订阅越不会丢消息）
 *  4. 如果用户此前开启过常驻，进程被拉起时确保服务在运行
 */
class BaobaoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 高德要求在使用任何地图能力前完成隐私合规声明，放在最前面最稳妥
        AMapPrivacy.ensure(this)

        NotificationHelper.createChannels(this)

        // 触发单例初始化 + 开始消费服务端推送
        TrackerRepository.getInstance(this)

        val prefs = PreferencesManager.getInstance(this)
        if (prefs.settings.value.serviceEnabled) {
            Log.i(TAG, "用户已开启常驻，确保服务运行")
            TrackerService.start(this)
        }
    }

    companion object {
        private const val TAG = "BaobaoApp"
    }
}
