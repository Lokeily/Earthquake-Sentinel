package com.dianguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 设置页（v1.0.17）：底部导航“设置”内容区。
 * 内容由旧 SettingsActivity 迁来：参考位置 / 预警阈值 / 远震提醒 / 测试 / 系统自检；
 * 新增「外观」深色模式三档 Spinner（跟随系统 / 浅色 / 深色 OLED 纯黑）与「检查更新」入口。
 */
class SettingsFragment : Fragment() {

    private lateinit var spinnerIntensity: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var tvLocationDisplay: TextView
    private lateinit var swDistant: Switch
    private lateinit var btnLoc: Button
    private lateinit var btnTest: Button
    private lateinit var btnSelfCheck: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnDisclaimer: Button
    private lateinit var tvAboutVersion: TextView

    // 防止初始化 setSelection 触发一次多余的 recreate
    private var themeReady = false
    // 防止 recreate 期间 Spinner 的 onItemSelected 再次触发（v1.1.1 修复频闪循环）
    @Volatile private var themeChanging = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        spinnerIntensity = root.findViewById(R.id.spinner_intensity)
        spinnerTheme = root.findViewById(R.id.spinner_theme)
        tvLocationDisplay = root.findViewById(R.id.tv_location_display)
        swDistant = root.findViewById(R.id.sw_distant)
        btnLoc = root.findViewById(R.id.btn_loc)
        btnTest = root.findViewById(R.id.btn_test)
        btnSelfCheck = root.findViewById(R.id.btn_selfcheck)
        btnCheckUpdate = root.findViewById(R.id.btn_check_update)
        btnClearHistory = root.findViewById(R.id.btn_clear_history)
        btnDisclaimer = root.findViewById(R.id.btn_disclaimer)
        tvAboutVersion = root.findViewById(R.id.tv_about_version)

        AppConfig.init(requireContext())
        setupIntensitySpinner()
        setupThemeSpinner()
        refreshLocationDisplay()
        swDistant.isChecked = AppConfig.distantNotify

        swDistant.setOnCheckedChangeListener { _, on ->
            AppConfig.distantNotify = on
            Toast.makeText(
                requireContext(),
                if (on) "已开启：未达阈值的地震将通过通知栏提醒"
                else "已关闭：仅达阈值的地震才会提醒",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnLoc.setOnClickListener { fetchCurrentLocation() }
        btnTest.setOnClickListener {
            saveConfig()
            startActivity(Intent(requireContext(), TestAlarmActivity::class.java))
        }
        btnSelfCheck.setOnClickListener { SelfCheck.showDialog(requireContext()) }
        btnCheckUpdate.setOnClickListener { checkUpdate() }
        btnDisclaimer.setOnClickListener {
            startActivity(Intent(requireContext(), DisclaimerActivity::class.java))
        }
        btnClearHistory.setOnClickListener { confirmClearHistory() }

        try {
            tvAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        } catch (_: Exception) {
            tvAboutVersion.text = ""
        }
        return root
    }

    // ===================== 烈度阈值下拉 =====================

    private fun setupIntensitySpinner() {
        val entries = resources.getStringArray(R.array.intensity_threshold_entries)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIntensity.adapter = adapter

        val values = resources.getIntArray(R.array.intensity_threshold_values)
        val saved = AppConfig.minIntensity.toInt()
        var idx = values.indexOf(saved)
        if (idx < 0) idx = values.indexOf(3)
        spinnerIntensity.setSelection(idx)

        spinnerIntensity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                AppConfig.minIntensity = values[position].toDouble()
            }

            override fun onNothingSelected(parent: AdapterView<*>) { }
        }
    }

    // ===================== 深色模式下拉 =====================

    private fun setupThemeSpinner() {
        val entries = resources.getStringArray(R.array.theme_mode_entries)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter
        spinnerTheme.setSelection(AppConfig.themeMode)

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (!themeReady) {
                    themeReady = true
                    return
                }
                // 防重入：recreate 期间 Spinner 重新 setSelection 会再次触发此回调
                if (themeChanging) return
                if (position == AppConfig.themeMode) return // 值未变，跳过

                themeChanging = true
                AppConfig.themeMode = position
                ThemeManager.apply(position)
                // 应用主题并重建 Activity（旧方案恢复，搭配 themeChanging 防重入）
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
        LocationHelper.fetchLocation(requireContext(), object : LocationHelper.LocationResult {
            override fun onResult(lat: Double, lon: Double, address: String?) {
                AppConfig.homeLat = lat
                AppConfig.homeLon = lon
                AppConfig.hasLocation = true
                if (!address.isNullOrBlank()) {
                    AppConfig.locationName = address
                    tvLocationDisplay.text = "现在获取到的位置：\n$address"
                } else {
                    tvLocationDisplay.text = "现在获取到的位置：纬度 %.5f，经度 %.5f".format(lat, lon)
                }
            }

            override fun onError(msg: String) {
                Toast.makeText(requireContext(), "定位失败：$msg", Toast.LENGTH_SHORT).show()
            }
        })
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
        AppConfig.distantNotify = swDistant.isChecked
    }

    /** 清空预警历史记录（带确认弹窗） */
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

    // ===================== 检查更新（手动） =====================

    private fun checkUpdate() {
        Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show()
        AppUpdateChecker.check(requireContext()) { info ->
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
        saveConfig()
    }
}
