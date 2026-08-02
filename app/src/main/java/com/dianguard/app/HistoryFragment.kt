package com.dianguard.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 预警历史记录页（v1.0.17）：底部导航"预警历史"内容区。
 * 展示 QuakeHistory 持久化的全部记录（最近 7 天 / 最多 100 条），
 * 按时间倒序，标注「已告警 / 备用源速报 / 已记录」，并提供清空入口。
 *
 * v1.1.x：每次打开页面自动从 CENC + USGS 抓取近期已发生地震并写入历史。
 */
class HistoryFragment : Fragment() {

    private lateinit var historyList: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvFetchHint: TextView

    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    /** 用于解析各数据源发震时间字符串为 Date 对象 */
    private fun parseOriginDate(originTime: String): Date? {
        if (originTime.isBlank()) return null
        return try {
            val cleaned = originTime.trim()
                .replace("T", " ")
                .replace("Z", "")
                .replace(Regex("\\.\\d+"), "")
                .let { s ->
                    val tzIdx = s.indexOfFirst { it == '+' || it == '-' }
                    if (tzIdx > 10) s.substring(0, tzIdx).trim() else s
                }
                .take(19)
            if (cleaned.length < 16) return null
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(cleaned)
        } catch (_: Exception) { null }
    }

    /** 防抖冷却，首次打开必定刷新一次（v1.1.1 取消位置依赖） */
    private var lastFetchMs = 0L
    private val FETCH_COOLDOWN_MS = 2 * 60 * 1000L // 2 分钟冷却

    /** 标记是否已完成首次抓取 */
    private var initialFetchDone = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_history, container, false)
        historyList = root.findViewById(R.id.history_list)
        tvEmpty = root.findViewById(R.id.tv_history_empty)
        tvCount = root.findViewById(R.id.tv_history_count)
        tvFetchHint = root.findViewById(R.id.tv_fetch_hint)
        return root
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        // 自动从 CENC + USGS 抓取近期已发生地震写入历史（带冷却防抖）
        // 注意：show/hide 模式下所有 Fragment 的 onResume 都会触发，
        // 但抓取只在用户切到本 Tab 时执行一次（冷却期内跳过）
        autoFetchHistory()
    }

    /** 自动从中国地震台网和 USGS 抓取近期地震记录 */
    private fun autoFetchHistory() {
        val now = System.currentTimeMillis()
        // 首次打开必定抓取一次（不管冷却），之后按 2 分钟冷却
        val isInitial = !initialFetchDone
        if (!isInitial && now - lastFetchMs < FETCH_COOLDOWN_MS) return
        lastFetchMs = now
        initialFetchDone = true

        // 守卫：Fragment 必须已附加且有有效视图
        if (!isAdded || !::tvFetchHint.isInitialized) return

        tvFetchHint.visibility = View.VISIBLE
        tvFetchHint.text = getString(R.string.history_fetching)

        // 传递 Context 给 HistoryFetcher（用于 Geocoder 逆地理编码 USGS 记录）
        HistoryFetcher.appContext = requireContext().applicationContext
        HistoryFetcher.fetchAndRecord { added ->
            // 再次守卫：回调时 Fragment 可能已 detach
            if (!isAdded || !::tvFetchHint.isInitialized) return@fetchAndRecord
            tvFetchHint.text = if (added > 0) {
                getString(R.string.history_fetch_done, added)
            } else {
                getString(R.string.history_fetch_none)
            }
            rebuild()
        }
    }

    /** 重新读取并渲染历史记录 */
    private fun rebuild() {
        val records = QuakeHistory.all()
        historyList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        tvCount.text = getString(R.string.history_count, records.size)
        tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        for (r in records) {
            val row = inflater.inflate(R.layout.history_item, historyList, false)
            val place = row.findViewById<TextView>(R.id.item_place)
            val meta = row.findViewById<TextView>(R.id.item_meta)
            val magnitude = row.findViewById<TextView>(R.id.item_magnitude)
            val badge = row.findViewById<TextView>(R.id.item_badge)

            // 震中地名
            place.text = if (r.place.isBlank()) "未知地区" else r.place

            // 左侧 meta 行：发震时间 · 烈度 · 距离 · 数据源
            val originDate = parseOriginDate(r.originTime)
            val timeStr = if (originDate != null) {
                dateFmt.format(originDate)
            } else {
                dateFmt.format(Date(r.timeMs))
            }
            val intensity = if (r.intensity.isBlank()) "" else " · 烈度${r.intensity}"
            val dist = if (r.distanceKm > 0) " · 震中约%.0fkm".format(r.distanceKm) else ""
            val src = if (r.sourceName.isBlank()) "" else " · ${r.sourceName}"
            meta.text = "$timeStr$intensity$dist$src"

            // 右侧：震级圆圈（背景色按级别：蓝/黄/橙/红）
            val mag = r.magnitude
            magnitude.text = if (mag > 0) "M%.1f".format(mag) else "?"
            val magColor = when {
                mag >= 7.0 -> R.color.level_red
                mag >= 6.0 -> R.color.level_orange
                mag >= 5.0 -> R.color.level_yellow
                mag >= 4.0 -> R.color.level_blue
                else -> R.color.ios_label_secondary
            }
            magnitude.backgroundTintList = ContextCompat.getColorStateList(requireContext(), magColor)
            magnitude.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

            // 右侧下方：徽标
            when {
                r.triggered -> {
                    badge.text = getString(R.string.history_badge_alerted)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.alert_red)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                r.backup -> {
                    badge.text = getString(R.string.history_badge_backup)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.source_orange)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                else -> {
                    badge.text = getString(R.string.history_badge_logged)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.ios_label_secondary)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
            }
            historyList.addView(row)
        }
    }

    companion object {
        /** 清空历史记录（供设置页调用） */
        fun clearAll() { QuakeHistory.clear() }
    }
}
