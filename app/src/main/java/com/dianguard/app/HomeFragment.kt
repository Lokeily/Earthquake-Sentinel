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
import androidx.localbroadcastmanager.content.LocalBroadcastManager

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

    private val sourceRows = mutableListOf<SourceRow>()
    private val sourceTickHandler = Handler(Looper.getMainLooper())
    private var sourceTickRunnable: Runnable? = null

    /** 单路数据源行视图引用 */
    private data class SourceRow(val dot: View, val name: TextView, val status: TextView)

    private val REQ_RUNTIME = 2001
    private val REQ_OVERLAY = 2002

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == EewService.ACTION_STATUS) {
                tvStatus.text = intent.getStringExtra(EewService.EXTRA_STATUS) ?: tvStatus.text
            }
        }
    }

    private val freshnessReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != EewService.ACTION_FRESHNESS) return
            val lastMs = intent.getLongExtra(EewService.EXTRA_LAST_DATA_MS, 0L)
            updateFreshness(lastMs)
        }
    }

    /**
     * 数据新鲜度：仅展示“最近一次地震预警”的时间，属于正常信息而非告警。
     * 没有地震时长时间无报文是正常现象，因此永远用中性灰色、绝不标红。
     */
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

        tvSourcesTitle.text = getString(R.string.sources_title, EEW_SOURCES.size)
        tvSourcesHint.text = getString(R.string.sources_hint_disabled, EEW_SOURCES.size)

        setupSourceRows()
        refreshEnableUi()

        btnEnable.setOnClickListener { toggleService() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnGuide.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        return root
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(statusReceiver, android.content.IntentFilter(EewService.ACTION_STATUS))
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(freshnessReceiver, android.content.IntentFilter(EewService.ACTION_FRESHNESS))

        refreshEnableUi()
        // 回到前台补一次位置抓取（如刚授予定位权限）
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchLocationOnOpen()
        }
    }

    override fun onPause() {
        super.onPause()
        stopSourceTick()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(freshnessReceiver)
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
            name.text = src.name
            sourcesList.addView(row)
            sourceRows.add(SourceRow(dot, name, status))
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
            val (c, text) = describeSource(st)
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
     *  - 已连接        → 绿“已连接”
     *  - 未连接·重试中  → 灰“连接中…”
     *  - 未连接·多次失败 → 橙“重连中…”（不标红，单源抖动是移动网络常态）
     */
    private fun describeSource(st: SourceUiState): Pair<Int, String> {
        return if (!st.connected) {
            if (st.failCount >= EewService.CONN_FAIL_WARN_THRESHOLD) {
                color(R.color.source_orange) to "重连中…"
            } else {
                color(R.color.source_gray) to "连接中…"
            }
        } else {
            color(R.color.ios_green) to "已连接"
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private fun toggleService() {
        if (AppConfig.serviceEnabled) {
            requireContext().stopService(Intent(requireContext(), EewService::class.java))
            AppConfig.serviceEnabled = false
            // 立即更新头条状态（stopService 是异步的，onDestroy 中的重置可能延迟）
            EewService.headlineState = "监听未开启"
            Toast.makeText(requireContext(), "已停止预警监听", Toast.LENGTH_SHORT).show()
        } else {
            if (!AppConfig.disclaimerAccepted) {
                showDisclaimerBeforeEnable()
                return
            }
            val intent = Intent(requireContext(), EewService::class.java)
            ContextCompat.startForegroundService(requireContext(), intent)
            AppConfig.serviceEnabled = true
            Toast.makeText(requireContext(), "地震哨兵已开始监听震前预警", Toast.LENGTH_SHORT).show()
            showEnableGuide()
        }
        refreshEnableUi()
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
                (requireActivity() as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
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
        val ctx = requireContext()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val locGranted = fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED

        val runtimeMissing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runtimeMissing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!locGranted) {
            runtimeMissing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            runtimeMissing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (runtimeMissing.isNotEmpty()) {
            requestPermissions(runtimeMissing.toTypedArray(), REQ_RUNTIME)
        }

        // 悬浮窗权限属于特殊权限，需跳转系统设置页授予
        if (!Settings.canDrawOverlays(ctx)) {
            requestOverlayPermission()
        }

        if (locGranted) {
            fetchLocationOnOpen()
        }

        if (AppConfig.firstLaunch) {
            AppConfig.firstLaunch = false
            showFirstLaunchGuide()
        }
    }

    private var lastLocationFetchMs = 0L

    /** 打开即把当前位置写入 AppConfig，作为预警参考点（带频率限制） */
    private fun fetchLocationOnOpen() {
        val now = System.currentTimeMillis()
        if (now - lastLocationFetchMs < 30_000L) return
        lastLocationFetchMs = now
        LocationHelper.fetchLocation(requireContext(), object : LocationHelper.LocationResult {
            override fun onResult(lat: Double, lon: Double, address: String?) {
                AppConfig.homeLat = lat
                AppConfig.homeLon = lon
                AppConfig.hasLocation = true
                if (!address.isNullOrBlank()) AppConfig.locationName = address
            }

            override fun onError(msg: String) { }
        })
    }

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

    private fun showFirstLaunchGuide() {
        AlertDialog.Builder(requireContext())
            .setTitle("欢迎使用地震哨兵")
            .setMessage(
                "为确保地震预警能在锁屏、后台、甚至 App 未运行时也能强制弹出，首次使用会自动检查以下必需设置是否就绪：\n\n" +
                    "· 通知权限（预警推送）\n" +
                    "· 定位权限（计算震中距离）\n" +
                    "· 悬浮窗权限（锁屏强制弹出）\n" +
                    "· 电池优化豁免（防后台被杀）\n" +
                    "· 预警监听开启\n\n" +
                    "马上为你检查各项权限是否已开启。"
            )
            .setPositiveButton("开始检查") { _, _ -> SelfCheck.showDialog(requireContext()) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun showPermissionMandatoryDialog() {
        val ctx = requireContext()
        val missing = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing.append("· 通知权限\n")
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED)
            missing.append("· 定位权限\n")
        if (!Settings.canDrawOverlays(ctx)) missing.append("· 悬浮窗权限\n")

        AlertDialog.Builder(ctx)
            .setTitle("必需权限未开启")
            .setMessage(
                "地震哨兵需要以下权限才能正常弹出来电式地震预警，请全部开启：\n\n$missing" +
                    "未开启将无法及时收到预警。"
            )
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                } catch (_: Exception) { }
            }
            .setNegativeButton("稍后", null)
            .show()
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
            val allRuntimeGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allRuntimeGranted || !Settings.canDrawOverlays(requireContext())) {
                showPermissionMandatoryDialog()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY && !Settings.canDrawOverlays(requireContext())) {
            showPermissionMandatoryDialog()
        }
    }
}
