package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 地震哨兵主页容器（v1.0.17 起）：
 *   - 上方 Fragment 内容区 + 下方自绘底部导航栏（3 个 Tab：主页 / 预警历史 / 设置）；
 *   - 三个 Fragment 通过 show/hide 切换（保留实例状态，不每次重建）；
 *   - 打开 App 即做崩溃自记录，并自动检查 GitHub 最新 Release。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_HISTORY = 1
        const val TAB_SETTINGS = 2

        /** 跨 recreate（如切换深色模式）保留当前所在的 Tab */
        @Volatile
        var currentTabIndex: Int = TAB_HOME
    }

    private val defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()

    private lateinit var tabHome: android.view.View
    private lateinit var tabHistory: android.view.View
    private lateinit var tabSettings: android.view.View
    private lateinit var tabHomeIcon: ImageView
    private lateinit var tabHomeText: TextView
    private lateinit var tabHistoryIcon: ImageView
    private lateinit var tabHistoryText: TextView
    private lateinit var tabSettingsIcon: ImageView
    private lateinit var tabSettingsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashSaver()
        setContentView(R.layout.activity_main)

        AppConfig.init(this)
        EewVoice.init(application)

        tabHome = findViewById(R.id.tab_home)
        tabHistory = findViewById(R.id.tab_history)
        tabSettings = findViewById(R.id.tab_settings)
        tabHomeIcon = findViewById(R.id.tab_home_icon)
        tabHomeText = findViewById(R.id.tab_home_text)
        tabHistoryIcon = findViewById(R.id.tab_history_icon)
        tabHistoryText = findViewById(R.id.tab_history_text)
        tabSettingsIcon = findViewById(R.id.tab_settings_icon)
        tabSettingsText = findViewById(R.id.tab_settings_text)

        tabHome.setOnClickListener { selectTab(TAB_HOME) }
        tabHistory.setOnClickListener { selectTab(TAB_HISTORY) }
        tabSettings.setOnClickListener { selectTab(TAB_SETTINGS) }

        // 首次进入默认选中主页；重建后保留上次所在 Tab
        selectTab(currentTabIndex)

        // 打开 App 即自动检测 GitHub 最新 Release，有新版本则提示更新
        maybeCheckUpdate()
    }

    /** 切换到指定 Tab：显示对应 Fragment、隐藏其余，并刷新选中态着色 */
    fun selectTab(index: Int) {
        currentTabIndex = index
        // 若正位于「更多设置」二级页，切 Tab 前先退出该页，避免叠加
        val fmPre = supportFragmentManager
        if (fmPre.backStackEntryCount > 0) fmPre.popBackStackImmediate()
        val (home, history, settings) = ensureFragments()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        // 切换 Tab 时内容交叉淡入淡出，避免生硬硬切
        tx.setCustomAnimations(R.anim.frag_fade_in, R.anim.frag_fade_out)
        val map = mapOf(TAB_HOME to home, TAB_HISTORY to history, TAB_SETTINGS to settings)
        map.forEach { (i, f) ->
            if (i == index) tx.show(f) else tx.hide(f)
        }
        tx.commitNow()
        updateTabUi(index)
    }

    /**
     * 进入「更多设置」二级菜单：隐藏设置页，叠加 MoreSettingsFragment 并加入返回栈。
     * 返回键（系统或页内「返回」）会 pop 回设置页。
     */
    fun openMoreSettings() {
        val fm = supportFragmentManager
        val settings = fm.findFragmentByTag("settings") as? SettingsFragment
        var more = fm.findFragmentByTag("more_settings") as? MoreSettingsFragment
        val tx = fm.beginTransaction()
        if (settings != null) tx.hide(settings)
        if (more == null) {
            more = MoreSettingsFragment()
            tx.add(R.id.fragment_container, more, "more_settings")
        } else {
            tx.show(more)
        }
        // 柔和过场：二级页从右侧滑入 + 淡入；返回时滑出，避免生硬跳变
        tx.setCustomAnimations(R.anim.slide_in_right, 0, 0, R.anim.slide_out_right)
        tx.addToBackStack("more_settings")
        tx.commit()
    }

    /** 返回键：优先退出「更多设置」二级页，否则走默认（退出 Activity） */
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }

    /** 确保三个 Fragment 已 add 到容器（重建时复用系统保留的实例） */
    private fun ensureFragments(): Triple<HomeFragment, HistoryFragment, SettingsFragment> {
        val fm = supportFragmentManager
        var home = fm.findFragmentByTag("home") as? HomeFragment
        var history = fm.findFragmentByTag("history") as? HistoryFragment
        var settings = fm.findFragmentByTag("settings") as? SettingsFragment
        val tx = fm.beginTransaction()
        var needCommit = false
        if (home == null) {
            home = HomeFragment()
            tx.add(R.id.fragment_container, home, "home")
            needCommit = true
        }
        if (history == null) {
            history = HistoryFragment()
            tx.add(R.id.fragment_container, history, "history")
            needCommit = true
        }
        if (settings == null) {
            settings = SettingsFragment()
            tx.add(R.id.fragment_container, settings, "settings")
            needCommit = true
        }
        if (needCommit) tx.commitNow()
        return Triple(home, history, settings)
    }

    /** Tab 选中态：选中用 ios_blue 着色图标与文字，未选中用 ios_label_secondary */
    private fun updateTabUi(index: Int) {
        setTabSelected(TAB_HOME, index == TAB_HOME)
        setTabSelected(TAB_HISTORY, index == TAB_HISTORY)
        setTabSelected(TAB_SETTINGS, index == TAB_SETTINGS)
    }

    private fun setTabSelected(tab: Int, selected: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (selected) R.color.ios_blue else R.color.ios_label_secondary
        )
        when (tab) {
            TAB_HOME -> {
                tabHomeIcon.setColorFilter(color)
                tabHomeText.setTextColor(color)
            }
            TAB_HISTORY -> {
                tabHistoryIcon.setColorFilter(color)
                tabHistoryText.setTextColor(color)
            }
            TAB_SETTINGS -> {
                tabSettingsIcon.setColorFilter(color)
                tabSettingsText.setTextColor(color)
            }
        }
    }

    /**
     * 自动检查更新：节流（默认 30 分钟内只查一次，规避 GitHub API 匿名限流），
     * 且同一版本用户选择“稍后”后不再重复弹窗。
     */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        val interval = 30 * 60 * 1000L
        if (now - AppConfig.lastUpdateCheckMs < interval) return
        AppConfig.lastUpdateCheckMs = now
        AppUpdateChecker.check { info ->
            if (info.available && info.latestVersion != AppConfig.dismissedUpdateVersion) {
                runOnUiThread { AppUpdater.showUpdateDialog(this, info) }
            }
        }
    }

    /** 崩溃自记录：把未捕获异常堆栈写入外部存储，便于无 adb 时排查“一开就闪退” */
    private fun installCrashSaver() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                // 崩溃日志可能包含用户精确坐标，写入前先脱敏，避免外部存储泄露位置隐私。
                val cleaned = redactCoordinates("time=${System.currentTimeMillis()}\n${sw}")
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash.txt").writeText(cleaned)
            } catch (_: Exception) { }
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 将崩溃日志中的精确坐标类字段脱敏，防止位置隐私随 crash.txt 泄露 */
    private fun redactCoordinates(text: String): String {
        return text
            .replace(Regex("(?i)(home_?lat|latitude|lat)[\\s=:]+[-\\d.]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)(home_?lon|longitude|lon)[\\s=:]+[-\\d.]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)(location_?name|address)[\\s=:]+\\S+"), "\$1=[REDACTED]")
    }
}
