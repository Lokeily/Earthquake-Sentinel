package com.dianguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * 设置页（v1.0.17）：底部导航“设置”内容区。
 * 内容由旧 SettingsActivity 迁来：参考位置 / 预警阈值 / 远震提醒 / 测试 / 系统自检；
 * 新增「外观」深色模式三档 Spinner（跟随系统 / 浅色 / 深色 OLED 纯黑）与「检查更新」入口。
 */
class SettingsFragment : Fragment() {

    private lateinit var spinnerIntensity: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var tvIntensityTrigger: TextView
    private lateinit var tvThemeTrigger: TextView
    private lateinit var tvLocationDisplay: TextView
    private lateinit var btnLoc: Button
    private lateinit var rowTest: View
    private lateinit var rowMoreSettings: View

    // BeeCLD 用户自注册源（可选数据源）
    private lateinit var btnBeeCLDRegister: Button
    private lateinit var etBeeCLDApi: EditText
    private lateinit var btnBeeCLDSave: Button
    private lateinit var btnBeeCLDDisable: Button
    private lateinit var tvBeeCLDStatus: TextView

    // 防止初始化 setSelection 触发一次多余的 recreate
    private var themeReady = false
    // 防止 recreate 期间 Spinner 的 onItemSelected 再次触发（v1.1.1 修复频闪循环）
    @Volatile private var themeChanging = false

    // 底部悬浮提示（BeeCLD 连接成功时浮现后隐去）
    private var floatTip: TextView? = null

    // 标记“刚点击了保存并连接、正在等待 beecld 连接成功广播”，避免对后续自动重连重复弹提示
    @Volatile private var awaitingBeeCLD = false

    /** 监听数据源连接成功广播（仅关心 beecld），由 EewConnectionManager.onOpen 发送 */
    private val sourceConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != EewService.ACTION_SOURCE_CONNECTED) return
            val id = intent.getStringExtra(EewService.EXTRA_SOURCE_ID) ?: return
            if (id == "beecld" && awaitingBeeCLD) {
                awaitingBeeCLD = false
                // onOpen 可能在 OkHttp 工作线程回调，切回主线程操作 UI
                requireActivity().runOnUiThread {
                    showBottomFloat(getString(R.string.beecld_connected_success))
                    refreshBeeCLDStatus()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        spinnerIntensity = root.findViewById(R.id.spinner_intensity)
        spinnerTheme = root.findViewById(R.id.spinner_theme)
        tvIntensityTrigger = root.findViewById(R.id.tv_intensity_trigger)
        tvThemeTrigger = root.findViewById(R.id.tv_theme_trigger)
        tvLocationDisplay = root.findViewById(R.id.tv_location_display)
        btnLoc = root.findViewById(R.id.btn_loc)
        rowTest = root.findViewById(R.id.row_test)
        rowMoreSettings = root.findViewById(R.id.row_more_settings)
        btnBeeCLDRegister = root.findViewById(R.id.btn_beecld_register)
        etBeeCLDApi = root.findViewById(R.id.et_beecld_api)
        btnBeeCLDSave = root.findViewById(R.id.btn_beecld_save)
        btnBeeCLDDisable = root.findViewById(R.id.btn_beecld_disable)
        tvBeeCLDStatus = root.findViewById(R.id.tv_beecld_status)
        floatTip = root.findViewById(R.id.float_tip)

        AppConfig.init(requireContext())
        setupIntensitySpinner()
        setupThemeSpinner()
        refreshLocationDisplay()

        // 注册数据源连接成功广播（仅当本页可见时接收，避免后台重复弹提示）
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            sourceConnectedReceiver,
            IntentFilter(EewService.ACTION_SOURCE_CONNECTED)
        )

        btnLoc.setOnClickListener { fetchCurrentLocation() }
        rowTest.setOnClickListener {
            startActivity(Intent(requireContext(), TestAlarmActivity::class.java))
        }
        rowMoreSettings.setOnClickListener {
            (activity as? MainActivity)?.openMoreSettings()
        }

        // BeeCLD：回填已保存的 token，并接线引导 / 保存 / 停用
        etBeeCLDApi.setText(AppConfig.beecldToken)
        btnBeeCLDRegister.setOnClickListener { openBeeCLDGuide() }
        btnBeeCLDSave.setOnClickListener { saveBeeCLDConfig() }
        btnBeeCLDDisable.setOnClickListener { disableBeeCLD() }
        refreshBeeCLDStatus()

        return root
    }

    // ===================== 烈度阈值（覆盖层 TextView + 隐藏 Spinner） =====================

    private fun setupIntensitySpinner() {
        val entries = resources.getStringArray(R.array.intensity_threshold_entries)
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, entries)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerIntensity.adapter = adapter

        val values = resources.getIntArray(R.array.intensity_threshold_values)
        val saved = AppConfig.minIntensity.toInt()
        var idx = values.indexOf(saved)
        // R5：默认/推荐 3°（明显有感）；2° 保留为高级选项（更灵敏但易频繁打扰）
        if (idx < 0) idx = values.indexOf(3)
        spinnerIntensity.setSelection(idx)
        tvIntensityTrigger.text = entries[idx]

        // 点击可见的覆盖层 TextView → 触发隐藏 Spinner 的下拉弹窗
        tvIntensityTrigger.setOnClickListener { spinnerIntensity.performClick() }

        spinnerIntensity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                AppConfig.minIntensity = values[position].toDouble()
                tvIntensityTrigger.text = entries[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>) { }
        }
    }

    // ===================== 深色模式（覆盖层 TextView + 隐藏 Spinner） =====================

    private fun setupThemeSpinner() {
        val entries = resources.getStringArray(R.array.theme_mode_entries)
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, entries)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerTheme.adapter = adapter
        spinnerTheme.setSelection(AppConfig.themeMode)
        tvThemeTrigger.text = entries[AppConfig.themeMode]

        tvThemeTrigger.setOnClickListener { spinnerTheme.performClick() }

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (!themeReady) {
                    themeReady = true
                    return
                }
                if (themeChanging) return
                if (position == AppConfig.themeMode) return

                themeChanging = true
                AppConfig.themeMode = position
                ThemeManager.apply(position)
                requireActivity().recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>) { }
        }
    }

    // ===================== 定位抓取（只读展示） =====================

    private fun fetchCurrentLocation() {
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1002
            )
            return
        }
        Toast.makeText(requireContext(), "正在获取当前位置…", Toast.LENGTH_SHORT).show()
        // 用户主动点“重新获取位置”：强制取实时 GPS 定位，绕过缓存，确保拿到新位置
        LocationHelper.fetchLocation(requireContext(), object : LocationHelper.LocationResult {
            override fun onResult(lat: Double, lon: Double, address: String?) {
                AppConfig.homeLat = lat
                AppConfig.homeLon = lon
                AppConfig.hasLocation = true
                // 优先用详细逆编码（省+市+区/县）；失败也至少给省级兜底，绝不直接显示经纬度
                val name = if (!address.isNullOrBlank()) {
                    address
                } else {
                    LocationHelper.provinceFallback(lat, lon)
                }
                if (!name.isNullOrBlank()) {
                    AppConfig.locationName = name
                    val suffix = if (address.isNullOrBlank()) "\n（网络逆编码暂不可用，已精确到省级）" else ""
                    tvLocationDisplay.text = "现在获取到的位置：\n$name$suffix"
                } else {
                    // 既无网络逆编码、又不在省级表范围内（如模拟器默认 0,0 点）
                    tvLocationDisplay.text = "当前位置无法识别所在区域。\n" +
                        "若是模拟器，请在系统/模拟器中设置所在城市位置后再试；真实设备请检查网络。"
                }
            }

            override fun onError(msg: String) {
                Toast.makeText(requireContext(), "定位失败：$msg", Toast.LENGTH_SHORT).show()
            }
        }, true)
    }

    private fun refreshLocationDisplay() {
        val name = AppConfig.locationName
        tvLocationDisplay.text = if (name.isNotBlank()) {
            "现在获取到的位置：\n$name"
        } else {
            getString(R.string.location_display_empty)
        }
    }

    private fun saveConfig() {
    }

    // ===================== BeeCLD 用户自注册源（可选） =====================

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
     * 用 ViewPropertyAnimator 做透明度过渡；浮层布局位于 fragment_settings 根 FrameLayout 底部居中。
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 1002 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            fetchCurrentLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开页面/切到后台时注销广播，避免接收已不需要的连接成功回调
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(sourceConnectedReceiver)
        saveConfig()
    }
}
