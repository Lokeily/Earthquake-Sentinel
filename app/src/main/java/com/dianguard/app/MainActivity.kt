package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 地震哨兵主页容器（v1.0.17 起，v1.4.2 正式软件化）：
 *   - 上方 Fragment 内容区 + 下方自绘底部导航栏（3 个 Tab：主页 / 预警历史 / 设置）；
 *   - 三个 Fragment 通过 show/hide 切换（保留实例状态，不每次重建）；
 *   - 崩溃自记录已上移到 Application（CrashSaver），此处仅负责：
 *       · 启动时若有未读崩溃记录 → 弹窗提示并引导查看/清除；
 *       · 打开 App 自动检查 GitHub 最新 Release。
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
        AppConfig.init(this)
        maybeNotifyCrashRecovery()

        // 首次进入：必须先阅读并同意《用户服务协议》与《免责声明协议》，
        // 未同意（或协议已重大更新需重新确认）前不进入主页、不申请任何权限。
        if (!AppConfig.disclaimerAccepted ||
            AppConfig.disclaimerAcceptedVersion < AppConfig.DISCLAIMER_VERSION
        ) {
            startActivity(
                Intent(this, DisclaimerActivity::class.java)
                    .putExtra(DisclaimerActivity.EXTRA_FORCE, true)
            )
            finish()
            return
        }

        setContentView(R.layout.activity_main)
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

        // 统一交互反馈：Tab 切换伴随轻微触觉脉冲（v1.4.8），与涟漪、淡入淡出同节奏
        val haptic = { v: android.view.View ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectTab(when (v.id) {
                R.id.tab_home -> TAB_HOME
                R.id.tab_history -> TAB_HISTORY
                else -> TAB_SETTINGS
            })
        }
        tabHome.setOnClickListener(haptic)
        tabHistory.setOnClickListener(haptic)
        tabSettings.setOnClickListener(haptic)

        // 首次进入默认选中主页；重建后保留上次所在 Tab
        selectTab(currentTabIndex)

        // 返回键处理（OnBackPressedDispatcher，替代已废弃的 onBackPressed）
        setupBackHandler()

        // 打开 App 即自动检测 GitHub 最新 Release，有新版本则提示更新
        maybeCheckUpdate()
    }

    /** 切换到指定 Tab：显示对应 Fragment、隐藏其余，并刷新选中态着色 */
    fun selectTab(index: Int) {
        currentTabIndex = index
        // 若正位于「更多设置」二级页，切 Tab 前先退出该页，避免叠加；
        // popBackStackImmediate 在状态保存后调用会抛 IllegalStateException，安全吞掉
        val fmPre = supportFragmentManager
        if (fmPre.backStackEntryCount > 0) {
            runCatching { fmPre.popBackStackImmediate() }
        }
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
        // 立即执行事务：否则快速双击「更多设置」时 findFragmentByTag 仍返回 null，
        // 会叠加两个二级页（需按两次返回才能退出）
        fm.executePendingTransactions()
    }

    /** 返回键：优先退出「更多设置」二级页，否则走默认（退出 Activity）。v1.4.3 迁移到 OnBackPressedDispatcher。 */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
     * debug 测试版禁用：线上 Release 与 debug 包名/签名均不同，引导安装只会失败。
     */
    private fun maybeCheckUpdate() {
        if (BuildConfig.IS_DEBUG) return
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

    /** 崩溃自记录已上移 CrashSaver（Application 级），此处仅在启动时检查是否有未读崩溃记录 */
    private fun maybeNotifyCrashRecovery() {
        try {
            if (!CrashSaver.hasPendingCrash(application)) return
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上次运行异常退出")
                .setMessage("检测到上次运行 App 发生异常，为避免影响预警接收已自动记录。\n\n" +
                    "可点击「查看日志」查看崩溃详情（仅保存在本机），或直接「忽略」。")
                .setPositiveButton("查看日志") { _, _ ->
                    val log = CrashSaver.readCrashLog(application)
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("崩溃日志（本机）")
                        .setMessage(log.ifBlank { "（空）" })
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton("清除记录") { _, _ -> CrashSaver.clearCrash(application) }
                        .show()
                }
                .setNegativeButton("忽略") { _, _ -> CrashSaver.clearCrash(application) }
                .setCancelable(false)
                .show()
        } catch (_: Exception) { }
    }
}
