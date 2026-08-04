package com.dianguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 开机 / 应用更新后自启预警监听。
 * 仅在用户曾开启"预警监听"（serviceEnabled = true）时才拉起前台服务，
 * 避免用户不知情时后台常驻。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AppConfig.init(context)
            if (AppConfig.serviceEnabled) {
                val serviceIntent = Intent(context, EewService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
