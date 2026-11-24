package com.dianguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 设置页（v1.4.2 信息架构重构）：一级主设置页，只保留高频核心配置——
 * 参考位置 / 预警阈值（纯烈度：白天/夜间）/ 外观（深色模式）。
 * 数据源（BeeCLD）、工具（测试报警）、通用（自检/更新/回放）、关于 全部收进
 * 二级页 MoreSettingsFragment（由底部「更多设置」行下钻）。
 */
class SettingsFragment : Fragment() {

    private lateinit var spinnerIntensityDay: Spinner
    private lateinit var spinnerIntensityNight: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var tvIntensityDayTrigger: TextView
    private lateinit var tvIntensityNightTrigger: TextView
    private lateinit var tvThemeTrigger: TextView
    private lateinit var tvLocationDisplay: TextView
    private lateinit var btnLoc: Button
    private lateinit var rowMoreSettings: View

    // 防止初始化 setSelection 触发一次多余的 recreate：
    // 用「上次选中位置」比对替代布尔标志——Spinner 的首次异步回调 position 与当前模式相同，
    // 自然被忽略；不存在“首次回调未触发导致用户第一次选择被吞”的时序竞态
    private var lastThemeSelection = ThemeManager.MODE_DARK

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        spinnerIntensityDay = root.findViewById(R.id.spinner_intensity_day)
        spinnerIntensityNight = root.findViewById(R.id.spinner_intensity_night)
        spinnerTheme = root.findViewById(R.id.spinner_theme)
        tvIntensityDayTrigger = root.findViewById(R.id.tv_intensity_day_trigger)
        tvIntensityNightTrigger = root.findViewById(R.id.tv_intensity_night_trigger)
        tvThemeTrigger = root.findViewById(R.id.tv_theme_trigger)
        tvLocationDisplay = root.findViewById(R.id.tv_location_display)
        btnLoc = root.findViewById(R.id.btn_loc)
        rowMoreSettings = root.findViewById(R.id.row_more_settings)

        AppConfig.init(requireContext())
        setupDayIntensitySpinner()
        setupNightIntensitySpinner()
        setupThemeSpinner()
        refreshLocationDisplay()

        btnLoc.setOnClickListener { fetchCurrentLocation() }
        rowMoreSettings.setOnClickListener {
            (activity as? MainActivity)?.openMoreSettings()
        }

        return root
    }

    // ===================== 预警阈值（纯烈度：白天/夜间） =====================

    /** 白天烈度阈值（ICL 推荐 2 度） */
    private fun setupDayIntensitySpinner() {
        val entries = resources.getStringArray(R.array.intensity_threshold_entries)
        val values = resources.getIntArray(R.array.intensity_threshold_values).map { it.toDouble() }
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, entries)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerIntensityDay.adapter = adapter

        val saved = AppConfig.minIntensityDay
        var idx = values.indexOfFirst { kotlin.math.abs(it - saved) < 0.01 }
        if (idx < 0) idx = values.indexOfFirst { kotlin.math.abs(it - AppConfig.DEFAULT_MIN_INTENSITY_DAY) < 0.01 }
        spinnerIntensityDay.setSelection(idx)
        tvIntensityDayTrigger.text = entries[idx]

        tvIntensityDayTrigger.setOnClickListener { spinnerIntensityDay.performClick() }

        spinnerIntensityDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                AppConfig.minIntensityDay = values[position]
                tvIntensityDayTrigger.text = entries[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>) { }
        }
    }

    /** 夜间烈度阈值（ICL 默认 3 度） */
    private fun setupNightIntensitySpinner() {
        val entries = resources.getStringArray(R.array.intensity_threshold_entries)
        val values = resources.getIntArray(R.array.intensity_threshold_values).map { it.toDouble() }
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, entries)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerIntensityNight.adapter = adapter

        val saved = AppConfig.minIntensityNight
        var idx = values.indexOfFirst { kotlin.math.abs(it - saved) < 0.01 }
        if (idx < 0) idx = values.indexOfFirst { kotlin.math.abs(it - AppConfig.DEFAULT_MIN_INTENSITY_NIGHT) < 0.01 }
        spinnerIntensityNight.setSelection(idx)
        tvIntensityNightTrigger.text = entries[idx]

        tvIntensityNightTrigger.setOnClickListener { spinnerIntensityNight.performClick() }

        spinnerIntensityNight.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                AppConfig.minIntensityNight = values[position]
                tvIntensityNightTrigger.text = entries[position]
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
        lastThemeSelection = AppConfig.themeMode

        tvThemeTrigger.setOnClickListener { spinnerTheme.performClick() }

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                // 初始异步回调 / 重复选中：position 未变化，直接忽略
                if (position == lastThemeSelection) return
                lastThemeSelection = position
                AppConfig.themeMode = position
                ThemeManager.apply(position)
                activity?.recreate()
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
            override fun onResult(lat: Double, lon: Double, address: String?, accuracyM: Float) {
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
                    val accLine = if (accuracyM >= 0) "\n定位精度约 ±${accuracyM.toInt()} 米" else ""
                    val suffix = if (address.isNullOrBlank()) "\n（网络逆编码暂不可用，已精确到省级）" else ""
                    tvLocationDisplay.text = "现在获取到的位置：\n$name$suffix$accLine"
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
}
