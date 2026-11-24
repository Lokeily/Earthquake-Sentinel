package com.dianguard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 更多设置（二级菜单，v1.4.2 信息架构重构）：由设置页「更多设置」行下钻进入。
 * 顶部「返回」或系统返回键均 pop 回设置页。
 * 内容：数据源（BeeCLD 用户自注册源）、通用（测试报警 / 系统自检 / 检查更新 /
 *       预警回放·测试版 / 清空回放 / 清空历史）、关于（免责声明与用户协议 / 版本号）。
 */
class MoreSettingsFragment : Fragment() {

    // BeeCLD 用户自注册源（可选数据源，自一级设置页迁入）
    private lateinit var btnBeeCLDRegister: Button
    private lateinit var etBeeCLDApi: EditText
    private lateinit var btnBeeCLDSave: Button
    private lateinit var btnBeeCLDDisable: Button
    private lateinit var tvBeeCLDStatus: TextView

    // 底部悬浮提示（BeeCLD 连接成功时浮现后隐去）
    private var floatTip: TextView? = null

    // 标记“刚点击了保存并连接、正在等待 beecld 连接成功广播”，避免对后续自动重连重复弹提示
    @Volatile private var awaitingBeeCLD = false

    // 演练控制台（v1.4.5，仅 debug 演练包显示；release 完全剥离）
    private var drillSection: View? = null
    private var etDrillEndpoint: EditText? = null
    private var tvDrillStatus: TextView? = null
    private var etDrillLat: EditText? = null
    private var etDrillLon: EditText? = null
    private var tvDrillLocation: TextView? = null
    private var drillLocationJob: Job? = null
    @Volatile private var awaitingDrill = false

    /** 监听数据源连接成功事件（替代已废弃的 BroadcastReceiver），由 EewConnectionManager.onOpen 发送 */
    private var sourceConnectedJob: Job? = null

    private fun handleSourceConnected(ev: AppEvents.SourceConnectedEvent) {
        if (ev.sourceId == "beecld" && awaitingBeeCLD) {
            awaitingBeeCLD = false
            showBottomFloat(getString(R.string.beecld_connected_success))
            refreshBeeCLDStatus()
        }
        if (ev.sourceId == "drill" && awaitingDrill) {
            awaitingDrill = false
            showBottomFloat("演练控制台已连接")
            refreshDrillStatus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_more_settings, container, false)

        val ivBack = root.findViewById<View>(R.id.iv_back)
        val rowTest = root.findViewById<View>(R.id.row_test)
        val rowSelfCheck = root.findViewById<View>(R.id.row_selfcheck)
        val rowUpdate = root.findViewById<View>(R.id.row_update)
        val rowReplay = root.findViewById<View>(R.id.row_replay)
        val rowClear = root.findViewById<View>(R.id.row_clear)
        val rowLicense = root.findViewById<View>(R.id.row_license)
        val rowCrashLog = root.findViewById<View>(R.id.row_crash_log)
        val rowDisclaimer = root.findViewById<View>(R.id.row_disclaimer)
        val tvAboutVersion = root.findViewById<TextView>(R.id.tv_about_version)

        // BeeCLD 控件（数据源区）
        btnBeeCLDRegister = root.findViewById(R.id.btn_beecld_register)
        etBeeCLDApi = root.findViewById(R.id.et_beecld_api)
        btnBeeCLDSave = root.findViewById(R.id.btn_beecld_save)
        btnBeeCLDDisable = root.findViewById(R.id.btn_beecld_disable)
        tvBeeCLDStatus = root.findViewById(R.id.tv_beecld_status)
        floatTip = root.findViewById(R.id.float_tip)

        ivBack.setOnClickListener { activity?.supportFragmentManager?.popBackStack() }
        rowTest.setOnClickListener {
            startActivity(Intent(requireContext(), TestAlarmActivity::class.java))
        }
        rowSelfCheck.setOnClickListener { SelfCheck.showDialog(requireContext()) }
        rowUpdate.setOnClickListener { checkUpdate() }
        // 预警回放仅限测试版（debug）：release 正式版自动剥离此入口
        val rowClearReplay = root.findViewById<View>(R.id.row_clear_replay)
        if (BuildConfig.IS_DEBUG) {
            rowReplay.visibility = View.VISIBLE
            rowReplay.setOnClickListener {
                startActivity(Intent(requireContext(), ReplayActivity::class.java))
            }
            rowClearReplay.visibility = View.VISIBLE
            rowClearReplay.setOnClickListener { confirmClearReplay() }
        } else {
            // 行隐藏时同步隐藏其上下分隔线，避免残留双线残影
            rowReplay.visibility = View.GONE
            rowClearReplay.visibility = View.GONE
            root.findViewById<View>(R.id.sep_replay_top).visibility = View.GONE
            root.findViewById<View>(R.id.sep_replay_bottom).visibility = View.GONE
            root.findViewById<View>(R.id.sep_replay_clear_bottom).visibility = View.GONE
        }
        rowClear.setOnClickListener { confirmClearHistory() }
        rowLicense.setOnClickListener { showLicenseDialog() }
        rowCrashLog.setOnClickListener { showCrashLogDialog() }
        rowDisclaimer.setOnClickListener {
            startActivity(Intent(requireContext(), DisclaimerActivity::class.java))
        }

        // BeeCLD：回填已保存的 token，并接线引导 / 保存 / 停用
        etBeeCLDApi.setText(AppConfig.beecldToken)
        btnBeeCLDRegister.setOnClickListener { openBeeCLDGuide() }
        btnBeeCLDSave.setOnClickListener { saveBeeCLDConfig() }
        btnBeeCLDDisable.setOnClickListener { disableBeeCLD() }
        refreshBeeCLDStatus()

        // 演练控制台：仅 debug 演练包显示入口，release 正式包连布局都不挂载
        if (BuildConfig.IS_DEBUG) {
            drillSection = root.findViewById(R.id.drill_section)
            etDrillEndpoint = root.findViewById(R.id.et_drill_endpoint)
            tvDrillStatus = root.findViewById(R.id.tv_drill_status)
            drillSection?.visibility = View.VISIBLE
            etDrillEndpoint?.setText(AppConfig.drillEndpoint)
            root.findViewById<Button>(R.id.btn_drill_connect).setOnClickListener { saveDrillConfig() }
            root.findViewById<Button>(R.id.btn_drill_disconnect).setOnClickListener { disableDrill() }
            etDrillLat = root.findViewById(R.id.et_drill_lat)
            etDrillLon = root.findViewById(R.id.et_drill_lon)
            tvDrillLocation = root.findViewById(R.id.tv_drill_location)
            root.findViewById<Button>(R.id.btn_drill_apply_location).setOnClickListener { applyDrillLocation() }
            root.findViewById<Button>(R.id.btn_drill_restore_gps).setOnClickListener { restoreAutoLocation() }
            refreshDrillStatus()
            refreshDrillLocation()
        }

        try {
            tvAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        } catch (_: Exception) {
            tvAboutVersion.text = ""
        }
        return root
    }

    // ===================== BeeCLD 用户自注册源（可选数据源） =====================

    /** 打开引导弹窗，说明如何注册并获取 API，再由用户点「前往注册」跳官网 */
    private fun openBeeCLDGuide() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.beecld_guide_title)
            .setMessage(R.string.beecld_guide_msg)
            .setPositiveButton(R.string.beecld_guide_go) { _, _ -> openBeeCLDWebsite() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 用浏览器打开 BeeCLD 注册/登录官网 */
    private fun openBeeCLDWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://auth.beecld.com/"))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                "无法打开浏览器，请手动访问 https://auth.beecld.com/",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 保存 token 并启用；服务在运行时直接起连，否则仅持久化待监听启动后由 connectAll 自动接入 */
    private fun saveBeeCLDConfig() {
        val token = etBeeCLDApi.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.beecld_empty), Toast.LENGTH_SHORT).show()
            return
        }
        AppConfig.beecldToken = token
        AppConfig.beecldEnabled = true
        val svc = EewService.instance
        if (svc != null) {
            svc.applyBeeCLDConfig()
            awaitingBeeCLD = true
            Toast.makeText(requireContext(), getString(R.string.beecld_saved), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.beecld_service_off), Toast.LENGTH_LONG).show()
        }
        refreshBeeCLDStatus()
    }

    /**
     * 底部悬浮提示：淡入显示 msg，约 2.2s 后淡出隐去（模拟用户要求的“悬浮窗弹出又隐去”）。
     * 用 ViewPropertyAnimator 做透明度过渡；浮层布局位于 fragment_more_settings 根 FrameLayout 底部居中。
     */
    private fun showBottomFloat(msg: String) {
        val tip = floatTip ?: return
        tip.text = msg
        tip.alpha = 0f
        tip.translationY = 20f
        tip.scaleX = 0.96f
        tip.scaleY = 0.96f
        tip.visibility = View.VISIBLE
        tip.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                tip.postDelayed({
                    tip.animate().alpha(0f).translationY(20f)
                        .setDuration(360)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction { tip.visibility = View.GONE }
                }, 2200)
            }
    }

    /** 停用 BeeCLD：断开连接，但保留 token 以便日后一键重新启用 */
    private fun disableBeeCLD() {
        if (!AppConfig.beecldEnabled && AppConfig.beecldToken.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.beecld_status_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        AppConfig.beecldEnabled = false
        EewService.instance?.applyBeeCLDConfig()
        Toast.makeText(requireContext(), getString(R.string.beecld_disabled), Toast.LENGTH_SHORT).show()
        refreshBeeCLDStatus()
    }

    /** 刷新 BeeCLD 状态文本：依据启用状态与实时连接情况 */
    private fun refreshBeeCLDStatus() {
        val enabled = AppConfig.beecldEnabled
        val connected = EewService.instance?.connMgr?.connectedSources?.contains("beecld") == true
        tvBeeCLDStatus.text = when {
            !enabled -> getString(R.string.beecld_status_disabled)
            connected -> getString(R.string.beecld_status_connected)
            else -> getString(R.string.beecld_status_enabled)
        }
    }

    // ===================== 演练控制台（v1.4.5，仅 debug 演练包） =====================

    /** 保存后台生成的接入地址并连接。只接受完整 ws:// / wss:// 地址，绝不拼接默认主机。 */
    private fun saveDrillConfig() {
        val url = etDrillEndpoint?.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("ws://", ignoreCase = true) &&
            !url.startsWith("wss://", ignoreCase = true)) {
            Toast.makeText(requireContext(), "请填入后台复制的完整 ws:// 接入地址", Toast.LENGTH_LONG).show()
            return
        }
        AppConfig.drillEndpoint = url
        AppConfig.drillEnabled = true
        val svc = EewService.instance
        if (svc != null) {
            svc.applyDrillConfig()
            awaitingDrill = true
            Toast.makeText(requireContext(), "正在连接演练控制台…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "配置已保存；开启「预警监听」后自动连接", Toast.LENGTH_LONG).show()
        }
        refreshDrillStatus()
    }

    /** 断开演练通道并停用；真实预警数据源完全不受影响。 */
    private fun disableDrill() {
        AppConfig.drillEnabled = false
        EewService.instance?.applyDrillConfig()
        awaitingDrill = false
        Toast.makeText(requireContext(), "已断开演练控制台", Toast.LENGTH_SHORT).show()
        refreshDrillStatus()
    }

    private fun refreshDrillStatus() {
        val connected = EewService.instance?.connMgr?.connectedSources?.contains("drill") == true
        tvDrillStatus?.text = when {
            !AppConfig.drillEnabled -> "未启用 · 正式用户不会收到任何演练数据"
            connected -> "已连接 · 演练通道就绪（仅本设备）"
            else -> "已启用 · 连接中（请确认手机与电脑处于同一局域网）"
        }
    }

    /** 手动输入经纬度：写入演练覆盖并立即回报后台，后台会自动跟随该坐标。 */
    private fun applyDrillLocation() {
        val lat = etDrillLat?.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = etDrillLon?.text?.toString()?.trim()?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(requireContext(), "请输入合法经纬度（纬度 -90~90，经度 -180~180）", Toast.LENGTH_LONG).show()
            return
        }
        AppConfig.setDrillLocation(lat, lon, "manual", "手动定位 · 演示机")
        EewService.instance?.reportDrillStatusNow()
        refreshDrillLocation()
        Toast.makeText(requireContext(), "参考位置已应用并同步后台", Toast.LENGTH_SHORT).show()
    }

    /** 关掉演练覆盖，恢复 GPS 自动定位（正式行为不变）。 */
    private fun restoreAutoLocation() {
        AppConfig.drillLocationOverride = false
        EewService.instance?.reportDrillStatusNow()
        refreshDrillLocation()
        Toast.makeText(requireContext(), "已恢复自动定位（GPS）", Toast.LENGTH_SHORT).show()
    }

    private fun refreshDrillLocation() {
        val tv = tvDrillLocation ?: return
        if (!AppConfig.drillLocationActive) {
            tv.text = if (AppConfig.hasLocation) {
                "当前：GPS 参考位置 %.4f, %.4f".format(AppConfig.homeLat, AppConfig.homeLon)
            } else {
                "当前：未设置参考位置（演练时请输入经纬度或等后台下发）"
            }
            return
        }
        val src = when (AppConfig.drillLocationSource) {
            "console" -> "后台下发"
            "manual" -> "手机手动输入"
            else -> "演练覆盖"
        }
        tv.text = "当前：%.4f, %.4f · %s · %s".format(
            AppConfig.homeLat, AppConfig.homeLon, src, AppConfig.locationName
        )
        // 后台远程改坐标时自动回填输入框（用户正在编辑时不打断）
        if (etDrillLat?.isFocused != true) etDrillLat?.setText("%.4f".format(AppConfig.homeLat))
        if (etDrillLon?.isFocused != true) etDrillLon?.setText("%.4f".format(AppConfig.homeLon))
    }

    override fun onResume() {
        super.onResume()
        // 订阅数据源连接成功事件流（替代已废弃的 BroadcastReceiver），
        // 保证从二级页返回（hide/show 不重建视图）后监听仍然有效
        sourceConnectedJob = lifecycleScope.launch {
            AppEvents.sourceConnected.collect { handleSourceConnected(it) }
        }
        // 后台下发坐标时即时刷新（含 hide/show 返回后补一次）
        drillLocationJob = lifecycleScope.launch {
            AppEvents.drillLocation.collect { refreshDrillLocation() }
        }
        refreshDrillLocation()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面/切到后台时注销，避免接收已不需要的连接成功回调
        sourceConnectedJob?.cancel()
        drillLocationJob?.cancel()
    }

    // ===================== 关于：开源许可 / 崩溃日志 =====================

    /** 开源许可（双许可）说明弹窗 */
    private fun showLicenseDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_license_dialog_title)
            .setMessage(R.string.about_license_dialog_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 查看本机崩溃日志（脱敏后），提供「清除记录」 */
    private fun showCrashLogDialog() {
        val log = CrashSaver.readCrashLog(requireContext().applicationContext)
        if (log.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.about_crash_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_crash_log_title)
            .setMessage(log)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.about_crash_clear) { _, _ ->
                CrashSaver.clearCrash(requireContext().applicationContext)
                Toast.makeText(requireContext(), "已清除崩溃记录", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ===================== 检查更新（手动） =====================

    private fun checkUpdate() {
        // debug 测试版禁用：线上 Release 与 debug 包名/签名均不同，更新引导无意义
        if (BuildConfig.IS_DEBUG) {
            Toast.makeText(requireContext(), "测试版无需检查更新", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show()
        AppUpdateChecker.check { info ->
            val act = activity ?: return@check
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (info.available) {
                    AppUpdater.showUpdateDialog(act as AppCompatActivity, info)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.update_none), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===================== 清空预警历史记录（带确认弹窗） =====================

    private fun confirmClearHistory() {
        if (QuakeHistory.all().isEmpty()) {
            Toast.makeText(requireContext(), "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                HistoryFragment.clearAll()
                Toast.makeText(requireContext(), "已清空预警历史记录", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===================== 清空回放数据（测试版，带确认弹窗） =====================

    private fun confirmClearReplay() {
        if (ReplayHistory.all().isEmpty()) {
            Toast.makeText(requireContext(), "暂无回放数据", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.replay_clear_settings)
            .setMessage(R.string.replay_clear_confirm)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                ReplayHistory.clear()
                Toast.makeText(requireContext(), "已清空回放数据，可重新同步", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
