package com.dianguard.app

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * 深色模式管理（v1.0.17 新增）。
 *
 * 面向「插电常驻老手机」场景做了针对性取舍：
 *  - 深色档位使用**纯黑 #000000** 背景（见 values-night/colors.xml）。
 *    OLED / AMOLED 屏幕上纯黑像素直接熄灭，既省电又能显著减少长期常亮的烧屏风险；
 *  - 卡片仅比背景亮一档（#0E0E10），保证层级可辨的同时不引入大面积高亮区域；
 *  - 正文文字用 #E4E4E7 而非纯白，降低静态高亮像素的亮度峰值（防烧屏 + 夜间不刺眼）。
 *
 * 三档语义：
 *  - MODE_SYSTEM：跟随系统（Android 10+ 有系统级深色开关；更早的机型退化为「省电模式自动深色」）
 *  - MODE_LIGHT ：始终浅色
 *  - MODE_DARK  ：始终深色（OLED 纯黑）
 */
object ThemeManager {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    /** 把配置档位换算为 AppCompat 的 NightMode 并全局生效 */
    fun apply(mode: Int) {
        val nightMode = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else ->
                // Android 10 起才有系统级深色开关；更早的机型退化为跟随省电模式，
                // 对「插电常驻」场景来说等价于常浅色，用户可手动切到 MODE_DARK。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                } else {
                    AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
                }
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** 设置页展示用的档位名称 */
    fun label(mode: Int): String = when (mode) {
        MODE_LIGHT -> "浅色"
        MODE_DARK -> "深色（OLED 纯黑省电）"
        else -> "跟随系统"
    }
}
