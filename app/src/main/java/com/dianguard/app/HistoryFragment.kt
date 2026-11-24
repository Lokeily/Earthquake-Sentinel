package com.dianguard.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    /** 重建防抖标志：250ms 窗口内的多次历史变更合并为一次重渲染 */
    private var rebuildPending = false

    /** 上次渲染的数据签名（条数 + 最新一条 key），用于避免无变化时重复播放入场动画 */
    private var lastRenderSignature: String? = null

    // P1-2 迁移：事件流订阅 Job
    private var historyJob: kotlinx.coroutines.Job? = null

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
        // P1-2 迁移：lifecycleScope 订阅历史变更事件（替代 LocalBroadcastManager）
        // 重建防抖：地震报文突发时（一次地震可有多源多报），250ms 内的多次变更
        // 合并为一次全量重渲染，避免旧手机连续全量 inflate 列表造成卡顿。
        historyJob = lifecycleScope.launch {
            AppEvents.historyChanged.collect {
                if (!isAdded || rebuildPending) return@collect
                rebuildPending = true
                view?.postDelayed({
                    rebuildPending = false
                    if (isAdded) rebuild()
                }, 250L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        historyJob?.cancel()
        historyJob = null
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
        val signature = "${records.size}|${records.firstOrNull()?.key ?: ""}"
        val dataChanged = signature != lastRenderSignature
        lastRenderSignature = signature

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
        // 仅在数据真正变化时播放入场动画，避免切 Tab / 新小震落库时反复闪烁
        if (dataChanged) {
            featureCard.alpha = 0f
            featureCard.animate().alpha(1f).setDuration(280).start()
        } else {
            featureCard.alpha = 1f
        }

        // 顶部特色卡片：取最新一条
        bindFeatureCard(records[0])

        // 其余记录用列表项展示
        for (i in 1 until records.size) {
            bindListItem(inflater, records[i])
        }
    }

    private fun bindFeatureCard(r: QuakeRecord) {
        val level = recordLevel(r)
        val levelColor = ContextCompat.getColor(requireContext(), level.colorRes())

        featureAccent.setBackgroundColor(levelColor)
        featureReltime.text = relativeTime(r.timeMs)
        featurePlace.text = if (r.place.isBlank()) "未知地区" else r.place
        featureTime.text = formatOriginTime(r.originTime, r.timeMs)
        featureMagBadge.text = "%.1f级".format(r.magnitude)
        // 徽标文字颜色按底色亮度自适应：黄色/橙色底用深色字，红/蓝底用白字，
        // 修复「白字配 #FFCC00 黄底」几乎不可读的问题（M3-4.9 是最高发的区间）
        val lum = 0.299 * android.graphics.Color.red(levelColor) +
                0.587 * android.graphics.Color.green(levelColor) +
                0.114 * android.graphics.Color.blue(levelColor)
        featureMagBadge.setTextColor(
            if (lum > 160) android.graphics.Color.parseColor("#1C1C1E")
            else android.graphics.Color.WHITE
        )
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
        val badge = row.findViewById<TextView>(R.id.item_badge)

        place.text = if (r.place.isBlank()) "未知地区" else r.place
        summary.text = buildSummary(r)
        meta.text = "发震时刻：${formatOriginTime(r.originTime, r.timeMs)}"

        // 状态徽标（数据链路 v1.2.0 已有 triggered/backup 字段，此前 UI 从未渲染）：
        //   triggered=true            → 红「已告警」——该地震曾达到阈值触发全屏告警；
        //   backup=true 且未告警      → 橙「备用源速报」——主链路中断期间由兜底源补录。
        // 两者皆否 → 普通记录，不显示徽标。
        when {
            r.triggered -> {
                badge.setText(R.string.history_badge_alerted)
                badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.alert_red))
                badge.visibility = View.VISIBLE
            }
            r.backup -> {
                badge.setText(R.string.history_badge_backup)
                badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.source_orange))
                badge.visibility = View.VISIBLE
            }
            else -> badge.visibility = View.GONE
        }

        val mag = r.magnitude
        val level = recordLevel(r)
        val levelColor = ContextCompat.getColor(requireContext(), level.colorRes())

        // 等级按震级（王暾团队标准）：BLUE（<3.0 弱震）显示「轻微地震」小标签
        magnitude.text = if (level == WarningLevel.BLUE) {
            "轻微\n地震"
        } else if (mag > 0) {
            "%.1f".format(mag)
        } else {
            "?"
        }
        // iOS 风格 tinted 圆标：等级色描边 + 10% 等级色填充 + 主文字色。
        // 不再用「彩色文字 + 白底」——深色模式下白底刺眼，黄色系文字在浅底上对比度不足。
        magnitude.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_label))
        magnitude.textSize = if (magnitude.text.contains("\n")) 10f else 15f

        val bg = magnitude.background.mutate() as android.graphics.drawable.GradientDrawable
        bg.setStroke(dp2px(2.5f).toInt(), levelColor)
        bg.setColor(android.graphics.Color.argb(
            26,
            android.graphics.Color.red(levelColor),
            android.graphics.Color.green(levelColor),
            android.graphics.Color.blue(levelColor)
        ))
        magnitude.background = bg

        // 点击查看该条记录的完整参数
        row.setOnClickListener { showRecordDetail(r) }
        historyList.addView(row)
    }

    /** 单条历史记录详情：展示数据源、报数、震中距、S 波 ETA、发震时刻与触发状态 */
    private fun showRecordDetail(r: QuakeRecord) {
        val level = recordLevel(r)
        val sb = StringBuilder()
        sb.append("震中地点：").append(if (r.place.isBlank()) "未知地区" else r.place).append('\n')
        sb.append("震级：").append("%.1f级".format(r.magnitude)).append('\n')
        sb.append("震源深度：").append(r.depthKm.toInt()).append(" 公里\n")
        sb.append("发震时刻：").append(formatOriginTime(r.originTime, r.timeMs)).append('\n')
        if (r.distanceKm > 0) {
            sb.append("距参考位置：约 ").append(r.distanceKm.toInt()).append(" 公里\n")
            sb.append("S 波预计到达：").append(r.etaSec.toInt()).append(" 秒\n")
        }
        val intensity = r.intensity.removePrefix("约")
        if (intensity.isNotBlank() && intensity != "-") {
            sb.append("本地预估烈度：").append(intensity).append(" 度\n")
        }
        sb.append("数据来源：").append(r.sourceName.ifBlank { "—" }).append('\n')
        sb.append("报数：第 ").append(r.reportNum).append(" 报\n")
        val sequence = officialReportSequence(r)
        sb.append("回放数据：").append(
            if (sequence.size > 1) "已保存同一官方来源的 ${sequence.size} 份逐报快照"
            else "仅有单一报告/目录审核结果"
        ).append('\n')
        sb.append("触发状态：").append(
            when {
                r.triggered -> "已触发全屏告警"
                r.backup -> "备用源速报（主链路中断期间补录）"
                else -> "未达阈值，仅记录"
            }
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("${level.label()} · 预警详情")
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 预警时间/预估烈度摘要：烈度数据缺失（"-"）时只展示预警时间，避免出现「预估烈度-度」 */
    private fun buildSummary(r: QuakeRecord): String {
        val eta = if (r.etaSec > 0) "${r.etaSec.toInt()}" else "-"
        val intensity = when {
            r.intensity.isBlank() -> ""
            r.intensity == "-" -> ""
            r.intensity.startsWith("约") -> r.intensity.substring(1)
            else -> r.intensity
        }
        return if (intensity.isNotBlank()) {
            "预警时间${eta}秒，预估烈度${intensity}度"
        } else {
            "预警时间${eta}秒"
        }
    }

    /**
     * 历史记录等级：按【震级】分级（王暾团队"地震预警"App 标准）。
     * 历史列表展示全国各地地震，颜色代表"这场地震本身有多强"（一次地震只有一个震级）：
     *   红 ≥6.0 / 橙 5.0-5.9 / 黄 3.0-4.9 / 蓝 <3.0（弱震）。
     * 注意：这与倒计时告警弹窗按【用户所在地预估烈度】分级（warningLevelByIntensity）
     * 是两套独立标准——列表看震级，弹窗看烈度。
     */
    private fun recordLevel(r: QuakeRecord): WarningLevel = warningLevel(r.magnitude)

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
