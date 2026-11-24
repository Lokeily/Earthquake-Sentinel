package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 预警回放页（测试版专用，v1.4.0）。
 *
 * 功能：浏览近一年内的历史预警记录，点击任一记录即可进入回放参数设置页，
 * 选择模拟观测位置与倒计时模式后，拉起真实全屏预警弹窗（AlertActivity）
 * 与真实预警提示音（EewVoice），交互与真实预警完全一致。
 *
 * 数据来源：
 *   - 首次进入自动从 CENC / USGS / EMSC 同步近 90 天的历史地震目录；
 *   - 已同步数据持久化于 ReplayHistory（365 天 / 500 条）；
 *   - 右上角按钮可手动重新同步。
 */
class ReplayActivity : AppCompatActivity() {

    companion object {
        /** 用于传递 QuakeRecord 到 ReplaySetupActivity 的 Intent extra key */
        const val EXTRA_RECORD = "replay_record"
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRefresh: TextView
    private lateinit var btnBack: ImageView

    private var lastFetchMs = 0L
    private val FETCH_COOLDOWN_MS = 30 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay)

        btnBack = findViewById(R.id.replay_btn_back)
        listContainer = findViewById(R.id.replay_list)
        tvEmpty = findViewById(R.id.replay_empty)
        tvStatus = findViewById(R.id.replay_status)
        progressBar = findViewById(R.id.replay_progress)
        btnRefresh = findViewById(R.id.replay_btn_refresh)

        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { fetchReplayData(force = true) }

        // 首次进入自动同步
        rebuild()
        autoFetch()
    }

    private fun autoFetch() {
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < FETCH_COOLDOWN_MS) return
        lastFetchMs = now
        fetchReplayData(force = false)
    }

    private fun fetchReplayData(force: Boolean) {
        if (force) lastFetchMs = System.currentTimeMillis()
        setSyncing(true)
        ReplayFetcher.fetchAndRecord(
            onProgress = { msg ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tvStatus.text = msg
                }
            },
            callback = { added ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setSyncing(false)
                    if (added > 0) {
                        Toast.makeText(this, "已同步 $added 条新的历史预警", Toast.LENGTH_SHORT).show()
                    }
                    rebuild()
                }
            }
        )
    }

    private fun setSyncing(syncing: Boolean) {
        progressBar.visibility = if (syncing) View.VISIBLE else View.GONE
        btnRefresh.isEnabled = !syncing
    }

    private fun rebuild() {
        // 目录同步数据通常只有最终审核结果；真实监听历史可能保存了官方逐报快照。
        // 两者统一进入回放列表，但逐报序列只由 ReplaySetupActivity 按同源 eventId 识别。
        val records = (QuakeHistory.all() + ReplayHistory.all())
            .distinctBy { record ->
                if (record.eventId.isNotBlank() && record.sourceId.isNotBlank()) {
                    "${record.sourceId}|${record.eventId}"
                } else record.key
            }
            .sortedByDescending { it.timeMs }
        listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE

        for (r in records) {
            val row = inflater.inflate(R.layout.replay_item, listContainer, false)
            val tvPlace = row.findViewById<TextView>(R.id.replay_item_place)
            val tvMeta = row.findViewById<TextView>(R.id.replay_item_meta)
            val tvMag = row.findViewById<TextView>(R.id.replay_item_mag)
            val tvBadge = row.findViewById<TextView>(R.id.replay_item_badge)

            val place = if (r.place.isBlank()) "未知地区" else r.place
            tvPlace.text = place

            val time = formatOriginTime(r.originTime, r.timeMs)
            val depth = "震源深度${r.depthKm.toInt()}公里"
            val sequence = officialReportSequence(r)
            val reportLabel = if (sequence.size > 1) {
                "官方${sequence.size}报"
            } else {
                "单报"
            }
            tvMeta.text = "$time ｜ $depth"
            tvBadge.text = reportLabel

            val level = warningLevel(r.magnitude)
            val levelColor = android.graphics.Color.parseColor(levelColorHex(level))
            tvMag.text = if (r.magnitude > 0) "%.1f级".format(r.magnitude) else "?"
            tvMag.setTextColor(levelColor)

            // 点击 → 打开回放参数设置页（选择位置/倒计时后再启动真实预警）
            row.setOnClickListener {
                val intent = Intent(this, ReplaySetupActivity::class.java)
                    .putExtra(EXTRA_RECORD, r)
                startActivity(intent)
            }
            listContainer.addView(row)
        }
    }

    /** 显示发震时刻（originTime），而非记录/同步时间（timeMs）；解析失败回退 timeMs */
    private fun formatOriginTime(originTime: String, fallbackMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val parsedMs = parseOriginTimeMs(originTime)
        if (parsedMs > 0) return sdf.format(Date(parsedMs))
        if (fallbackMs > 0) return sdf.format(Date(fallbackMs))
        return "时间未知"
    }

    private fun levelColorHex(level: WarningLevel): String = when (level) {
        WarningLevel.RED -> "#FF3B30"
        WarningLevel.ORANGE -> "#FF9500"
        WarningLevel.YELLOW -> "#FFCC00"
        WarningLevel.BLUE -> "#0A84FF"
        WarningLevel.NONE -> "#8E8E93"
    }
}
