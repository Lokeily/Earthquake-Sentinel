package com.dianguard.app

import android.app.Application

/**
 * 应用入口（v1.0.17 新增）。
 *
 * 存在的唯一理由：深色模式必须在**任何 Activity 创建之前**生效，
 * 否则冷启动会先闪一帧浅色再切深色。把 AppConfig 初始化与夜间模式应用
 * 提前到 Application.onCreate，可彻底消除这一帧闪白（OLED 场景尤其明显）。
 *
 * 注意：EewService / BootCompletedReceiver 等组件仍各自调用 AppConfig.init()，
 * 因为进程可能由后台组件先拉起，AppConfig.init 幂等，重复调用无副作用。
 */
class DianguardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
        ThemeManager.apply(AppConfig.themeMode)
    }
}
