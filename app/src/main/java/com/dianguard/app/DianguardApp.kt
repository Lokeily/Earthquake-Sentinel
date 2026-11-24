package com.dianguard.app

import android.app.Application

/**
 * 应用入口（v1.0.17 新增，v1.4.2 正式软件化）。
 *
 * 存在的理由：
 *  - 深色模式必须在**任何 Activity 创建之前**生效，否则冷启动会先闪一帧浅色再切深色。
 *    AppConfig 初始化与夜间模式应用提前到 Application.onCreate，彻底消除这一帧闪白。
 *  - 崩溃自记录（CrashSaver）在此安装：Application 级全局捕获，任何进程
 *    （前台 UI / 后台 EewService / 广播接收器）崩溃都能写入本地 crash.txt，
 *    比旧版放在 MainActivity（仅 Activity 进程）覆盖更全。
 *
 * 注意：EewService / BootCompletedReceiver 等组件仍各自调用 AppConfig.init()，
 * 因为进程可能由后台组件先拉起，AppConfig.init 幂等，重复调用无副作用。
 */
class DianguardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
        CrashSaver.install(this)
        ThemeManager.apply(AppConfig.themeMode)
    }
}
