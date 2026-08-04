package com.dianguard.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 预警历史记录页（v1.2.0+）：底部导航"预警历史"内容区。
 * 顶部展示最新/重点地震特色卡片，下方列表展示其余记录，对齐用户截图样式。
 */
class HistoryFragment : Fragment() {

    private lateinit var historyList: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var featureCard: View
    private lateinit var featureAccent: View
    private lateinit var featureReltime: TextView
    private lateinit var featurePlace: TextView
    private lateinit var featureTime: TextView
    private lateinit var featureMagBadge: TextView
    private lateinit var featureDepth: TextView
    private lateinit var featureSummary: TextView

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private var lastFetchMs = 0L
    private val FETCH_COOLDOWN_MS = 2 * 60 * 1000L
    private var initialFetchDone = false

    private val historyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (!isAdded) return
            rebuild()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_history, container, false)
        historyList = root.findViewById(R.id.history_list)
        tvEmpty = root.findViewById(R.id.tv_history_empty)
        tvCount = root.findViewById(R.id.tv_history_count)
        featureCard = root.findViewById(R.id.feature_card)
        featureAccent = root.findViewById(R.id.feature_accent)
        featureReltime = root.findViewById(R.id.feature_reltime)
        featurePlace = root.findViewById(R.id.feature_place)
        featureTime = root.findViewById(R.id.feature_time)
        featureMagBadge = root.findViewById(R.id.feature_mag_badge)
        featureDepth = root.findViewById(R.id.feature_depth)
        featureSummary = root.findViewById(R.id.feature_summary)
        return root
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        autoFetchHistory()
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(historyReceiver, android.content.IntentFilter(EewService.ACTION_HISTORY_CHANGED))
        } catch (_: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(historyReceiver)
        } catch (_: Exception) { }
    }

    private fun autoFetchHistory() {
        val now = System.currentTimeMillis()
        val isInitial = !initialFetchDone
        if (!isInitial && now - lastFetchMs < FETCH_COOLDOWN_MS) return
        lastFetchMs = now
        initialFetchDone = true

        if (!isAdded) return

        HistoryFetcher.fetchAndRecord { _ ->
            if (!isAdded) return@fetchAndRecord
            rebuild()
        }
    }

    /** 重新读取并渲染历史记录 */
    private fun rebuild() {
        val records = QuakeHistory.all()
        historyList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        tvCount.text = getString(R.string.history_count, records.size)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            featureCard.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        featureCard.visibility = View.VISIBLE

        // 顶部特色卡片：取最新一条
        bindFeatureCard(records[0])

        // 其余记录用列表项展示
        for (i in 1 until records.size) {
            bindListItem(inflater, records[i])
        }
    }

    private fun bindFeatureCard(r: QuakeRecord) {
        val level = warningLevel(r.magnitude)
        val levelColor = ContextCompat.getColor(requireContext(), level.colorRes())

        featureAccent.setBackgroundColor(levelColor)
        featureReltime.text = relativeTime(r.timeMs)
        featurePlace.text = if (r.place.isBlank()) "未知地区" else r.place
        featureTime.text = formatOriginTime(r.originTime, r.timeMs)
        featureMagBadge.text = "%.1f级".format(r.magnitude)
        featureMagBadge.setTextColor(android.graphics.Color.WHITE)
        featureMagBadge.background = makeRoundRect(levelColor)
        featureDepth.text = "震源深度${r.depthKm.toInt()}公里"
        featureSummary.text = buildSummary(r)
    }

    private fun bindListItem(inflater: LayoutInflater, r: QuakeRecord) {
        val row = inflater.inflate(R.layout.history_item, historyList, false)
        val place = row.findViewById<TextView>(R.id.item_place)
        val summary = row.findViewById<TextView>(R.id.item_summary)
        val meta = row.findViewById<TextView>(R.id.item_meta)
        val magnitude = row.findViewById<TextView>(R.id.item_magnitude)

        place.text = if (r.place.isBlank()) "未知地区" else r.place
        summary.text = buildSummary(r)
        meta.text = "发震时刻：${formatOriginTime(r.originTime, r.timeMs)}"

        val mag = r.magnitude
        val level = warningLevel(mag)
        val levelColor = ContextCompat.getColor(requireContext(), level.colorRes())

        magnitude.text = if (level == WarningLevel.BLUE && mag >= 0.1 && mag < 3.0) {
            "轻微\n地震"
        } else if (mag > 0) {
            "%.1f".format(mag)
        } else {
            "?"
        }
        magnitude.setTextColor(levelColor)
        magnitude.textSize = if (magnitude.text.contains("\n")) 10f else 15f

        val bg = magnitude.background.mutate() as android.graphics.drawable.GradientDrawable
        bg.setStroke(dp2px(2.5f).toInt(), levelColor)
        magnitude.background = bg

        historyList.addView(row)
    }

    /** 预警时间/预估烈度摘要：历史目录通常没有用户本地值，用「-」占位，对齐截图 */
    private fun buildSummary(r: QuakeRecord): String {
        val eta = if (r.etaSec > 0) "${r.etaSec.toInt()}" else "-"
        val intensity = when {
            r.intensity.isBlank() -> "-"
            r.intensity.startsWith("约") -> r.intensity.substring(1)
            else -> r.intensity
        }
        return "预警时间${eta}秒，预估烈度${intensity}度"
    }

    private fun formatOriginTime(originTime: String, fallbackMs: Long): String {
        if (originTime.isBlank()) return dateFmt.format(Date(fallbackMs))
        val d = parseOriginDate(originTime)
        return if (d != null) dateFmt.format(d) else dateFmt.format(Date(fallbackMs))
    }

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

    private fun relativeTime(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000}小时前"
            diff < 30L * 86_400_000 -> "${diff / 86_400_000}天前"
            else -> "很久前"
        }
    }

    private fun makeRoundRect(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp2px(16f)
            setColor(color)
        }
    }

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    companion object {
        fun clearAll() { QuakeHistory.clear() }
    }
}
