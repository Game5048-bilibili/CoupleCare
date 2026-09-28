package net.game5048.baobao.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.game5048.baobao.data.local.PreferencesManager

/**
 * 开机自启 / 应用更新后自动拉起服务。
 *
 * 注意：Android 10+ 系统会禁止大部分后台启动行为，ColorOS 更是默认拦截。
 * 必须让用户手动在「手机管家 → 自启动管理」里允许本应用（见 README 保活章节），
 * 否则这个 Receiver 收不到 BOOT_COMPLETED。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = PreferencesManager.getInstance(context)
        if (!prefs.settings.value.serviceEnabled) {
            Log.i(TAG, "$action：用户未开启常驻，忽略")
            return
        }

        Log.i(TAG, "$action：正在拉起常驻服务")
        TrackerService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
