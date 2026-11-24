package com.dianguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 主页（v1.0.17）：底部导航“主页”页内容。
 *  - 当前状态 + 高亮“开启预警监听”按钮；
 *  - 数据来源板块：开启后实时展示各路数据源状态 + 备用探测源一行 + 灰色“数据更新于多久前”小字；
 *  - 保活入口（关闭电池优化 / 保活设置引导）；
 *  - 打开即强制校验并申请必需权限（通知 / 定位 / 悬浮窗），并抓取当前位置作为参考点。
 */
class HomeFragment : Fragment() {

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnEnable: Button
    private lateinit var sourcesList: LinearLayout
    private lateinit var tvSourcesTitle: TextView
    private lateinit var tvSourcesHint: TextView
    private lateinit var backupDot: View
    private lateinit var tvBackupStatus: TextView
    private lateinit var tvLastData: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnGuide: Button

    // 数据来源面板折叠控件（v1.3.1+）
    private lateinit var rowSourcesTitle: View
    private lateinit var ivSourcesChevron: android.widget.ImageView
    private lateinit var llSourcesBody: View

    private val sourceRows = mutableListOf<SourceRow>()
    private val sourceTickHandler = Handler(Looper.getMainLooper())
    private var sourceTickRunnable: Runnable? = null

    // P1-2 迁移：事件流订阅 Job（lifecycleScope 自动随 Fragment 生命周期取消）
    private var statusJob: kotlinx.coroutines.Job? = null
    private var freshnessJob: kotlinx.coroutines.Job? = null

    /** 单路数据源行视图引用 + 所属 EewSource（用于状态文案翻译） */
    private data class SourceRow(val dot: View, val name: TextView, val status: TextView, val src: EewSource)

    private val REQ_RUNTIME = 2001
    private val REQ_OVERLAY = 2002

    /** 用户点击「开启预警监听」后正在等待权限授权，授权完成后继续开启流程 */
    private var pendingEnable = false

    /** 数据新鲜度：仅展示“最近一次地震预警”的时间，属于正常信息而非告警。 */
    private fun updateFreshness(lastMs: Long) {
        val secondary = ContextCompat.getColor(requireContext(), R.color.ios_label_secondary)
        tvLastData.setTextColor(secondary)
        if (lastMs == 0L) {
            tvLastData.setText(R.string.freshness_init)
            return
        }
        val diffMs = System.currentTimeMillis() - lastMs
        val days = (diffMs / (24 * 3600_000L)).toInt()
        val hours = (diffMs / 3600_000L).toInt()
        val minutes = (diffMs / 60_000L).toInt()
        tvLastData.text = when {
            minutes <= 0 -> getString(R.string.freshness_just)
            days >= 1 -> getString(R.string.freshness_days, days)
            hours >= 1 -> getString(R.string.freshness_hours, hours)
            else -> getString(R.string.freshness_minutes, minutes)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        statusDot = root.findViewById(R.id.status_dot)
        tvStatus = root.findViewById(R.id.tv_status)
        btnEnable = root.findViewById(R.id.btn_enable)
        sourcesList = root.findViewById(R.id.sources_list)
        tvSourcesTitle = root.findViewById(R.id.tv_sources_title)
        tvSourcesHint = root.findViewById(R.id.tv_sources_hint)
        backupDot = root.findViewById(R.id.backup_dot)
        tvBackupStatus = root.findViewById(R.id.tv_backup_status)
        tvLastData = root.findViewById(R.id.tv_last_data)
        btnBattery = root.findViewById(R.id.btn_battery)
        btnGuide = root.findViewById(R.id.btn_guide)
        rowSourcesTitle = root.findViewById(R.id.row_sources_title)
        ivSourcesChevron = root.findViewById(R.id.iv_sources_chevron)
        llSourcesBody = root.findViewById(R.id.ll_sources_body)

        tvSourcesTitle.text = getString(R.string.sources_title, EEW_SOURCES.size)
        tvSourcesHint.text = getString(R.string.sources_hint_disabled, EEW_SOURCES.size)

        setupSourceRows()
        setupSourcesPanelCollapse()
        refreshEnableUi()

        btnEnable.setOnClickListener { toggleService() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnGuide.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        return root
    }

    override fun onResume() {
        super.onResume()
        // P1-2 迁移：通过 lifecycleScope 订阅 AppEvents 事件流，替代 LocalBroadcastManager
        statusJob = lifecycleScope.launch {
            AppEvents.status.collect { event ->
                if (isAdded) tvStatus.text = event.text
            }
        }
        freshnessJob = lifecycleScope.launch {
            AppEvents.freshness.collect { event ->
                if (isAdded) updateFreshness(event.lastDataMs)
            }
        }

        refreshEnableUi()

        // 自愈（可靠性）：开关持久化为「已开启」但服务实例不存在——典型场景是整个进程
        // 被系统杀死且 START_STICKY 未生效。若不处理，主页显示“监听中”而实际无人监听，
        // 用户误以为受保护。此处自动重新拉起服务；onStartCommand 的防僵尸守卫保证
        // 即使状态不一致也不会错误常驻。
        if (AppConfig.serviceEnabled && EewService.instance == null) {
            Log.i("HomeFragment", "检测到监听开关开启但服务未运行，自动恢复")
            val intent = Intent(requireContext(), EewService::class.java)
            ContextCompat.startForegroundService(requireContext(), intent)
        }

        // 回到前台时若已授权定位但尚未获取过位置，自动抓取一次
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if ((fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED)
            && !AppConfig.hasLocation) {
            fetchLocationOnOpen()
        }
    }

    override fun onPause() {
        super.onPause()
        stopSourceTick()
        statusJob?.cancel()
        freshnessJob?.cancel()
        statusJob = null
        freshnessJob = null
    }

    // 主页创建即触发：权限校验 + 抓取位置 + 首次启动引导（等价于原 MainActivity 的 ensureRequiredSetup）
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureRequiredSetup()
    }

    private fun refreshEnableUi() {
        val on = AppConfig.serviceEnabled
        btnEnable.text = if (on) getString(R.string.home_btn_disable) else getString(R.string.home_btn_enable)
        tvStatus.text = EewService.headlineState
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), if (on) R.color.ios_green else R.color.source_gray)
        )
        updateSourceSection()
    }

    // ===================== 数据来源实时状态 =====================

    /** 按 EEW_SOURCES 顺序动态生成数据源展示行 */
    private fun setupSourceRows() {
        sourcesList.removeAllViews()
        sourceRows.clear()
        val inflater = LayoutInflater.from(requireContext())
        for (src in EEW_SOURCES) {
            val row = inflater.inflate(R.layout.source_row, sourcesList, false)
            val dot = row.findViewById<View>(R.id.src_dot)
            val name = row.findViewById<TextView>(R.id.src_name)
            val status = row.findViewById<TextView>(R.id.src_status)
            // 官方直连源打「官方」标签，让用户一眼看出权威来源（转发源不标）
            name.text = if (src.isOfficial) "${src.name} · 官方" else src.name
            sourcesList.addView(row)
            sourceRows.add(SourceRow(dot, name, status, src))
        }
    }

    private fun updateSourceSection() {
        val on = AppConfig.serviceEnabled
        sourcesList.visibility = if (on) View.VISIBLE else View.GONE
        tvSourcesHint.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            updateSourceRows()
            startSourceTick()
        } else {
            stopSourceTick()
            tvBackupStatus.setText(R.string.status_down)
            backupDot.backgroundTintList = ColorStateList.valueOf(color(R.color.source_gray))
        }
    }

    // ===================== 数据来源面板：可折叠/展开 =====================

    /**
     * 初始化面板折叠控件：根据用户上次偏好恢复状态（不动画），并绑定点击切换。
     * - 收起时：body 整体 GONE，chevron 旋转 180°（朝上）；标题始终可见，方便随时展开。
     * - 展开时：body 恢复 VISIBLE，chevron 旋转回 0°（朝下，展开指示）。
     * - 偏好持久化在 AppConfig.sourcesCollapsed（SharedPreferences），跨会话保留。
     */
    private fun setupSourcesPanelCollapse() {
        applySourcesPanelCollapsed(AppConfig.sourcesCollapsed, animate = false)
        rowSourcesTitle.setOnClickListener { toggleSourcesPanel() }
    }

    /** 切换展开/收起：更新状态 + 写盘 + 播放动画 */
    private fun toggleSourcesPanel() {
        val newCollapsed = !AppConfig.sourcesCollapsed
        AppConfig.sourcesCollapsed = newCollapsed
        applySourcesPanelCollapsed(newCollapsed, animate = true)
    }

    private fun applySourcesPanelCollapsed(collapsed: Boolean, animate: Boolean) {
        val targetRotation = if (collapsed) 180f else 0f
        if (!animate) {
            ivSourcesChevron.rotation = targetRotation
            llSourcesBody.visibility = if (collapsed) View.GONE else View.VISIBLE
            llSourcesBody.alpha = 1f
            return
        }
        if (!collapsed) {
            // 展开：body 先设为可见再淡入，chevron 从上转回下
            llSourcesBody.alpha = 0f
            llSourcesBody.visibility = View.VISIBLE
            llSourcesBody.animate().alpha(1f).setDuration(180L).start()
            ivSourcesChevron.animate().rotation(0f).setDuration(180L).start()
        } else {
            // 收起：body 淡出后 GONE，chevron 从下转向上
            llSourcesBody.animate().alpha(0f).setDuration(160L)
                .withEndAction { llSourcesBody.visibility = View.GONE }
                .start()
            ivSourcesChevron.animate().rotation(180f).setDuration(180L).start()
        }
    }

    private fun startSourceTick() {
        stopSourceTick()
        sourceTickRunnable = object : Runnable {
            override fun run() {
                updateSourceRows()
                sourceTickHandler.postDelayed(this, 800L) // 800ms 刷新，状态变化感知更快
            }
        }
        sourceTickHandler.postDelayed(sourceTickRunnable!!, 200L) // 首次快速刷新
    }

    private fun stopSourceTick() {
        sourceTickRunnable?.let { sourceTickHandler.removeCallbacks(it) }
        sourceTickRunnable = null
    }

    /** 从 EewService 读取每路数据源快照，更新圆点颜色与状态文案；同时刷新备用探测源一行 */
    private fun updateSourceRows() {
        if (!AppConfig.serviceEnabled) return
        val states = EewService.sourceStatuses
        for (i in sourceRows.indices) {
            val row = sourceRows[i]
            val st = states.getOrNull(i) ?: continue
            val (c, text) = describeSource(row.src, st)
            row.dot.backgroundTintList = ColorStateList.valueOf(c)
            row.status.text = text
        }
        // 备用探测源行
        if (EewService.backupActive) {
            backupDot.backgroundTintList = ColorStateList.valueOf(color(R.color.ios_green))
            tvBackupStatus.text = EewService.backupNote.ifBlank { "已激活" }
        } else {
            backupDot.backgroundTintList = ColorStateList.valueOf(color(R.color.source_gray))
            tvBackupStatus.text = EewService.backupNote.ifBlank { getString(R.string.status_down) }
        }
    }

    /**
     * 单路数据源状态翻译为（圆点颜色, 状态文案），语气保持平静：
     *  - 用户自注册 BeeCLD 未启用 → 灰「未启用」（避免误以为连接中）
     *  - HTTP 降级中     → 橙「HTTP降级·…」（WS 不通但同源 HTTP 仍在接收，预警未中断）
     *  - 已连接          → 绿“已连接”
     *  - 未连接·重试中   → 灰“连接中…”
     *  - 未连接·多次失败 → 橙“重连中…”（不标红，单源抖动是移动网络常态）
     */
    private fun describeSource(src: EewSource, st: SourceUiState): Pair<Int, String> {
        // 用户自注册 BeeCLD：未启用时显式标注"未启用"
        if (src.id == "beecld" && !AppConfig.beecldEnabled) {
            return color(R.color.source_gray) to getString(R.string.source_status_disabled)
        }
        // 演练控制台（仅 debug 包存在此源）：未启用时不要显示成"连接中…"，避免误导
        if (src.id == "drill" && !AppConfig.drillEnabled) {
            return color(R.color.source_gray) to "未启用（演练）"
        }
        // HTTP 降级状态优先显示：WS 虽不通，但同机构 HTTP 端点仍在接收/轮询，
        // 用户需要知道「预警未中断」，而不是看到吓人的「源异常」
        st.fallbackNote?.let { note ->
            return color(R.color.source_orange) to note
        }
        return if (!st.connected) {
            // 转发源（wolfx 镜像）异常时：若官方直连源（ICL）仍可用，用安抚文案，
            // 避免「源异常·503」让用户误以为整个预警系统都坏了；
            // 官方源自身异常则如实标注。
            if (st.serverError != null) {
                if (src.isOfficial) {
                    color(R.color.source_orange) to "官方源异常·${st.serverError}"
                } else if (isAnyOfficialAlive()) {
                    color(R.color.source_orange) to "转发异常·官方源可用"
                } else {
                    color(R.color.source_orange) to "转发异常·${st.serverError}"
                }
            } else if (st.failCount >= EewService.CONN_FAIL_WARN_THRESHOLD) {
                color(R.color.source_orange) to "重连中…"
            } else {
                color(R.color.source_gray) to "连接中…"
            }
        } else {
            color(R.color.ios_green) to "已连接"
        }
    }

    /** 是否存在任一官方直连源在线（用于转发源异常时的安抚文案） */
    private fun isAnyOfficialAlive(): Boolean {
        val states = EewService.sourceStatuses
        return EEW_SOURCES.filter { it.isOfficial }
            .any { src -> states.any { st -> st.id == src.id && st.connected } }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private fun toggleService() {
        // 弹窗按钮的回调可能在 Fragment detach（如旋转重建）后才触发，
        // 届时 requireContext()/requireActivity() 会抛 IllegalStateException；
        // 统一提前捕获 applicationContext，回调用它完成与服务相关的操作。
        if (!isAdded) return
        val appCtx = requireContext().applicationContext
        if (AppConfig.serviceEnabled) {
            // 关闭预警监听：播放提示音 + 确认弹窗
            playServiceAudio(R.raw.disable_notify)
            AlertDialog.Builder(requireContext())
                .setTitle("关闭预警监听")
                .setMessage("关闭预警监听功能，预警系统将失效，请注意确认！")
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭") { _, _ ->
                    appCtx.stopService(Intent(appCtx, EewService::class.java))
                    AppConfig.serviceEnabled = false
                    EewService.updateState { copy(headlineState = "监听未开启") }
                    if (isAdded) refreshEnableUi()
                }
                .show()
        } else {
            if (!AppConfig.disclaimerAccepted) {
                showDisclaimerBeforeEnable()
                return
            }
            // 权限前置：点「开启」时才申请权限（通知 / 定位 / 悬浮窗），
            // 全部就绪后再进入自检与启动；首次进入 App 不再弹出任何权限请求。
            val missing = collectMissingRuntimePermissions()
            if (missing.isNotEmpty()) {
                pendingEnable = true
                requestPermissions(missing.toTypedArray(), REQ_RUNTIME)
                return
            }
            if (!hasOverlayPermission(requireContext())) {
                pendingEnable = true
                requestOverlayPermission()
                return
            }
            proceedEnableService()
        }
    }

    /** 收集尚未授予的运行时权限（通知 / 定位） */
    private fun collectMissingRuntimePermissions(): List<String> {
        val ctx = requireContext()
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return missing
    }

    /**
     * 权限就绪后的开启流程：检查参考位置 → 自检 → 启动服务。
     * R5 修复：必须已设定参考位置才能开启预警监听——烈度告警依赖「用户所在地」的
     * 烈度衰减计算，未设位置时无法判定是否告警；旧版静默回退兜底坐标会造成错误距离与误报。
     */
    private fun proceedEnableService() {
        if (!isAdded) return
        val appCtx = requireContext().applicationContext
        if (!AppConfig.hasLocation) {
            AlertDialog.Builder(requireContext())
                .setTitle("请先设置参考位置")
                .setMessage("地震预警需要知道您所在的位置，才能计算地震到您这里的距离与预估烈度。\n\n" +
                    "请点击“获取当前位置”，然后再开启预警监听。")
                .setPositiveButton("获取当前位置") { _, _ -> fetchLocationOnOpen() }
                .setNeutralButton("前往设置") { _, _ ->
                    (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // 系统自检 → 确认 → 播放提示音 → 启动服务
        SelfCheck.showDialog(requireContext()) {
            playServiceAudio(R.raw.enable_notify)
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, EewService::class.java))
            AppConfig.serviceEnabled = true
            if (isAdded) {
                refreshEnableUi()
                showEnableGuide()
            }
        }
    }

    /** 播放服务状态提示音时持有的 MediaPlayer 引用（避免方法返回后对象被 GC 而中途断音） */
    private var serviceTonePlayer: android.media.MediaPlayer? = null

    /**
     * 播放服务状态提示音（开启/关闭预警监听时的“嘀”提示）。
     * 修复点：
     *  1) 用字段持有 MediaPlayer，避免 prepareAsync 期间局部引用被回收导致只播一半；
     *  2) 用 USAGE_ALARM 而非 USAGE_MEDIA，与 EewVoice 告警音一致，确保不被媒体音量静音、可靠可闻。
     */
    private fun playServiceAudio(resId: Int) {
        try {
            // 先释放上一次可能还没播完的提示音，避免叠加/串音
            try { serviceTonePlayer?.release() } catch (_: Exception) { }
            serviceTonePlayer = null

            val ctx = requireContext().applicationContext
            val uri = Uri.parse("android.resource://${ctx.packageName}/$resId")
            val mp = android.media.MediaPlayer()
            serviceTonePlayer = mp
            mp.setDataSource(ctx, uri)
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = false
            mp.setOnPreparedListener { player ->
                try { player.start() } catch (_: Exception) { }
            }
            mp.setOnCompletionListener { player ->
                try { player.release() } catch (_: Exception) { }
                if (serviceTonePlayer === player) serviceTonePlayer = null
            }
            mp.setOnErrorListener { player, _, _ ->
                try { player.release() } catch (_: Exception) { }
                if (serviceTonePlayer === player) serviceTonePlayer = null
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) { }
    }

    /**
     * 首次开启预警监听前的免责声明弹窗。
     * 用户点"不同意"则什么都不发生（不启动服务、不持久化）；
     * 点"我已阅读并同意"则记录同意状态，并重新触发开启流程。
     */
    private fun showDisclaimerBeforeEnable() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.disclaimer_first_launch_title)
            .setMessage(R.string.disclaimer_first_launch_msg)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                AppConfig.disclaimerAccepted = true
                toggleService()
            }
            .setNeutralButton("查看完整条款") { _, _ ->
                startActivity(Intent(requireContext(), DisclaimerActivity::class.java))
                // 看完条款回主页后，用户再次点"开启预警监听"时会再次弹出此对话框
            }
            .setNegativeButton(R.string.disclaimer_decline, null)
            .setCancelable(true)
            .show()
    }

    /** 开启后告诉用户：要真正生效，还需完成哪些设置（跳到“设置”页） */
    private fun showEnableGuide() {
        AlertDialog.Builder(requireContext())
            .setTitle("预警监听已开启")
            .setMessage(R.string.guide_enable_text)
            .setPositiveButton("前往设置") { _, _ ->
                // activity 可空安全取用：detach 后点击不崩溃
                (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
            }
            .setNegativeButton("我知道了", null)
            .show()
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    // ===================== 打开即执行的必需设置 =====================

    private fun ensureRequiredSetup() {
        // 首次进入不再自动申请任何权限——权限申请已移至「开启预警监听」时
        // （见 toggleService → proceedEnableService）。此处仅重置首次启动标记。
        if (AppConfig.firstLaunch) {
            AppConfig.firstLaunch = false
        }
    }

    private var lastLocationFetchMs = 0L

    /** 首次进入 App 或首次授予定位权限时，把当前位置写入 AppConfig 作为预警参考点（带 30s 频率限制） */
    private fun fetchLocationOnOpen() {
        val now = System.currentTimeMillis()
        if (now - lastLocationFetchMs < 30_000L) return
        lastLocationFetchMs = now
        Toast.makeText(requireContext(), "正在自动获取当前位置…", Toast.LENGTH_LONG).show()
        LocationHelper.fetchLocation(requireContext(), object : LocationHelper.LocationResult {
            override fun onResult(lat: Double, lon: Double, address: String?, accuracyM: Float) {
                AppConfig.homeLat = lat
                AppConfig.homeLon = lon
                AppConfig.hasLocation = true
                val name = if (!address.isNullOrBlank()) address
                           else LocationHelper.provinceFallback(lat, lon)
                if (!name.isNullOrBlank()) AppConfig.locationName = name
            }

            override fun onError(msg: String) {
                Toast.makeText(requireContext(), "自动定位失败：$msg\n请到设置页手动获取", Toast.LENGTH_LONG).show()
            }
        }, forceFresh = true)
    }

    /**
     * 悬浮窗权限：Android 6.0（API 23）以下无运行时悬浮窗权限模型，
     * SYSTEM_ALERT_WINDOW 在安装时即授予，故旧版本直接视为已授权，避免调用
     * Settings.canDrawOverlays() 在 API 21–22 上抛 NoSuchMethodError 崩溃。
     */
    private fun hasOverlayPermission(context: android.content.Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_OVERLAY)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RUNTIME) {
            val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                fetchLocationOnOpen()
            }
            if (pendingEnable) {
                pendingEnable = false
                // 继续开启流程：再检查悬浮窗权限
                if (!hasOverlayPermission(requireContext())) {
                    pendingEnable = true
                    requestOverlayPermission()
                } else {
                    proceedEnableService()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (pendingEnable) {
                pendingEnable = false
                if (hasOverlayPermission(requireContext())) {
                    proceedEnableService()
                } else {
                    Toast.makeText(requireContext(), "悬浮窗权限未开启，锁屏时可能无法弹出预警", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
