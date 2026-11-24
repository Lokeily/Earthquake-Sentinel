package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 回放参数设置页。官方有逐报快照则逐报重现；没有则只播放现有单报，绝不伪造修正报。 */
class ReplaySetupActivity : AppCompatActivity() {
    private lateinit var record: QuakeRecord
    private lateinit var sequence: List<QuakeReportSnapshot>

    private lateinit var tvMag: TextView
    private lateinit var tvLevelBadge: TextView
    private lateinit var tvPlace: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvReportStatus: TextView
    private lateinit var rgLocation: RadioGroup
    private lateinit var rbCurrent: RadioButton
    private lateinit var rbSimulate: RadioButton
    private lateinit var tvCurrentSub: TextView
    private lateinit var etDistance: EditText
    private lateinit var rgCountdown: RadioGroup
    private lateinit var rbDemo30: RadioButton
    private lateinit var tvPreviewDist: TextView
    private lateinit var tvPreviewEta: TextView
    private lateinit var tvPreviewIntensity: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay_setup)
        record = intent.getSerializableExtra(ReplayActivity.EXTRA_RECORD) as? QuakeRecord
            ?: run { finish(); return }
        sequence = officialReportSequence(record)

        findViewById<ImageView>(R.id.rs_back).setOnClickListener { finish() }
        tvMag = findViewById(R.id.rs_mag)
        tvLevelBadge = findViewById(R.id.rs_level_badge)
        tvPlace = findViewById(R.id.rs_place)
        tvMeta = findViewById(R.id.rs_meta)
        tvReportStatus = findViewById(R.id.rs_report_status)
        rgLocation = findViewById(R.id.rg_location)
        rbCurrent = findViewById(R.id.rb_current)
        rbSimulate = findViewById(R.id.rb_simulate)
        tvCurrentSub = findViewById(R.id.tv_current_sub)
        etDistance = findViewById(R.id.et_distance)
        rgCountdown = findViewById(R.id.rg_countdown)
        rbDemo30 = findViewById(R.id.rb_demo_30)
        tvPreviewDist = findViewById(R.id.tv_preview_dist)
        tvPreviewEta = findViewById(R.id.tv_preview_eta)
        tvPreviewIntensity = findViewById(R.id.tv_preview_intensity)

        bindEarthquakeInfo()
        setupLocationDefaults()
        setupListeners()
        recomputePreview()
        findViewById<Button>(R.id.btn_start_replay).setOnClickListener { confirmReplay() }
    }

    private fun playbackReports(): List<QuakeReportSnapshot> {
        if (sequence.isNotEmpty()) return sequence
        return listOf(QuakeReportSnapshot(
            sourceId = record.sourceId,
            eventId = record.eventId.ifBlank { record.key },
            reportNum = record.reportNum.coerceAtLeast(1),
            receivedMs = record.timeMs,
            originTime = record.originTime,
            place = record.place,
            magnitude = record.magnitude,
            depthKm = record.depthKm,
            epiLat = record.epiLat,
            epiLon = record.epiLon,
            sourceName = record.sourceName
        ))
    }

    private fun bindEarthquakeInfo() {
        val display = sequence.lastOrNull() ?: playbackReports().last()
        val level = warningLevel(display.magnitude)
        val color = ContextCompat.getColor(this, level.colorRes())
        tvMag.text = "%.1f级".format(display.magnitude)
        tvMag.setTextColor(color)
        tvLevelBadge.text = level.label()
        tvLevelBadge.setTextColor(color)
        tvPlace.text = display.place.ifBlank { "未知地区" }
        tvMeta.text = "${formatOrigin(record.originTime, record.timeMs)} · 震源深度${display.depthKm.toInt()}公里"
        tvReportStatus.text = if (sequence.size > 1) {
            getString(R.string.replay_reports_official, sequence.size)
        } else {
            getString(R.string.replay_reports_catalog)
        }
    }

    private fun setupLocationDefaults() {
        val report = playbackReports().first()
        val hasEpicenter = hasCoordinates(report)
        if (AppConfig.hasLocation && hasEpicenter) {
            rbCurrent.isChecked = true
            tvCurrentSub.text = AppConfig.locationName.ifBlank { "当前参考位置已设置" }
        } else {
            rbSimulate.isChecked = true
            tvCurrentSub.text = if (!AppConfig.hasLocation) {
                getString(R.string.replay_loc_current_none)
            } else getString(R.string.replay_no_epicenter)
        }
    }

    private fun setupListeners() {
        rgLocation.setOnCheckedChangeListener { _, _ -> recomputePreview() }
        rgCountdown.setOnCheckedChangeListener { _, _ -> recomputePreview() }
        etDistance.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recomputePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun recomputePreview() {
        val reports = playbackReports()
        val steps = reports.map { buildStep(it, reportOffset(reports.first(), it)) }
        val first = steps.first()
        val last = steps.last()
        tvPreviewDist.text = "首报距观测位置：${first.distanceKm.toInt()} 公里"
        tvPreviewEta.text = if (first.etaSec > 0.5) {
            "首报 S 波剩余：${first.etaSec.toInt()} 秒"
        } else "首报时 S 波即将或已抵达"
        tvPreviewIntensity.text = if (reports.size > 1) {
            "本地预估烈度：${"%.1f".format(first.intensity)} → ${"%.1f".format(last.intensity)} 度\n" +
                "将完整重现第${reports.first().reportNum}报 → 第${reports.last().reportNum}报"
        } else {
            "本地预估烈度：${"%.1f".format(first.intensity)} 度 · ${first.level.label()}"
        }
        tvPreviewIntensity.setTextColor(ContextCompat.getColor(this, last.level.colorRes()))
    }

    private data class ReplayStep(
        val report: QuakeReportSnapshot,
        val distanceKm: Double,
        val etaSec: Double,
        val intensity: Double,
        val level: WarningLevel
    )

    private fun buildStep(report: QuakeReportSnapshot, offsetMs: Long): ReplayStep {
        val distance = distanceFor(report)
        val rawIntensity = if (hasCoordinates(report)) {
            estimateSiteIntensity(report.magnitude, distance, report.depthKm, report.epiLat, report.epiLon)
        } else {
            estimateSiteIntensity(report.magnitude, distance, report.depthKm)
        }
        // 关键修复：等级必须按「四舍五入到 0.1 的展示值」判定，而不是原始浮点值。
        // 否则 6.95 会被判成橙色、界面却显示"7.0 度"——展示烈度与预警等级直接矛盾
        //（ICL / 国家标准：红 ≥7°）。展示值与等级必须同源同值。
        val intensity = Math.round(rawIntensity.coerceAtLeast(0.0) * 10.0) / 10.0
        // 回放是模拟场景：S 波剩余时间 = 完整传播时间 - 已播放的报间隔，
        // 从「收到首报」那一刻重新计时，绝不沿用历史 receivedMs-originMs 的时间差。
        // 否则 ICL 轮询/备用源晚到的历史记录（发震后 30-60 秒才被 App 收到）会被
        // 误算成「S 波早已到达」，回放首报显示 0 秒倒计时，失去预警意义。
        val eta = if (rbDemo30.isChecked) {
            (30.0 - offsetMs / 1000.0).coerceAtLeast(0.0)
        } else {
            (AppConfig.estimateSWaveEtaSeconds(distance, report.depthKm) - offsetMs / 1000.0)
                .coerceAtLeast(0.0)
        }
        return ReplayStep(report, distance, eta, intensity, warningLevelByIntensity(intensity))
    }

    private fun distanceFor(report: QuakeReportSnapshot): Double {
        if (rbCurrent.isChecked && AppConfig.hasLocation && hasCoordinates(report)) {
            return haversineKm(AppConfig.homeLat, AppConfig.homeLon, report.epiLat, report.epiLon)
        }
        return etDistance.text.toString().toDoubleOrNull()?.coerceIn(1.0, 3000.0) ?: 60.0
    }

    private fun hasCoordinates(report: QuakeReportSnapshot): Boolean =
        report.epiLat != 0.0 || report.epiLon != 0.0

    private fun reportOffset(first: QuakeReportSnapshot, report: QuakeReportSnapshot): Long {
        if (first.receivedMs <= 0L || report.receivedMs <= 0L) return 0L
        return (report.receivedMs - first.receivedMs).coerceAtLeast(0L)
    }

    private fun confirmReplay() {
        val reports = playbackReports()
        if (rbCurrent.isChecked && (!AppConfig.hasLocation || !hasCoordinates(reports.first()))) {
            Toast.makeText(this, R.string.replay_need_location, Toast.LENGTH_LONG).show()
            return
        }
        if (rbSimulate.isChecked) {
            val distance = etDistance.text.toString().toDoubleOrNull()
            if (distance == null || distance !in 1.0..3000.0) {
                Toast.makeText(this, R.string.replay_distance_invalid, Toast.LENGTH_LONG).show()
                return
            }
        }
        // 回放始终从第一报开始，完整重现第一报→第二报→第三报的修正过程
        val first = reports.first()
        val last = reports.last()
        val firstStep = buildStep(first, 0L)
        val lastStep = buildStep(last, reportOffset(first, last))
        AlertDialog.Builder(this)
            .setTitle("开始回放预警")
            .setMessage(buildString {
                if (sequence.size > 1) {
                    append("将按官方实际 ${sequence.size} 报依次重现，从第${first.reportNum}报开始：\n\n")
                } else {
                    append("数据源没有逐报序列，本次只回放现有一报。\n\n")
                }
                append("震中：${first.place}\n")
                append("首报震级：${"%.1f级".format(first.magnitude)}\n")
                if (reports.size > 1) append("末报震级：${"%.1f级".format(last.magnitude)}\n")
                append("首报倒计时：${firstStep.etaSec.toInt()} 秒\n")
                append("首报预估烈度：${"%.1f".format(firstStep.intensity)} 度")
                if (reports.size > 1) {
                    append(" → 末报 ${"%.1f".format(lastStep.intensity)} 度")
                }
                append("\n\n⚠ 回放会播放较大声音与震动，请在合适环境下进行。")
            })
            .setPositiveButton("开始回放") { _, _ -> startPlayback(reports) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPlayback(reports: List<QuakeReportSnapshot>) {
        EewVoice.stopAll()
        // 始终从第一报开始，完整重现第一报→第二报→第三报的修正过程
        val firstReport = reports.first()
        val first = buildStep(firstReport, 0L)
        val replayEventId = "REPLAY-${record.key}-${System.currentTimeMillis()}"
        val firstIntent = alertIntent(replayEventId, first).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val updates = reports.drop(1).map { report ->
            val delay = reportOffset(firstReport, report)
            ReplayPlayback.Update(delay, refreshEvent(replayEventId, buildStep(report, delay)))
        }
        ReplayPlayback.start(this, firstIntent, updates)
    }

    private fun alertIntent(eventId: String, step: ReplayStep): Intent =
        Intent(this, AlertActivity::class.java).apply {
            putExtra(EewService.EXTRA_EVENT_ID, eventId)
            putReportExtras(step)
            putExtra(EewService.EXTRA_SOURCE, "历史回放 · ${step.report.sourceName.ifBlank { record.sourceName }}")
        putExtra(EewService.EXTRA_TIME, parseOriginTimeMs(step.report.originTime).takeIf { it > 0 } ?: record.timeMs)
        putExtra(EewService.EXTRA_EPI_LAT, step.report.epiLat)
        putExtra(EewService.EXTRA_EPI_LON, step.report.epiLon)
        putExtra(EewService.EXTRA_REPLAY, true)
        }

    private fun refreshEvent(eventId: String, step: ReplayStep): AppEvents.RefreshEvent =
        AppEvents.RefreshEvent(
            eventId = eventId,
            mag = step.report.magnitude,
            place = "${step.report.place.ifBlank { record.place }}（回放）",
            distance = step.distanceKm,
            eta = step.etaSec,
            intensity = "%.1f".format(step.intensity),
            depth = step.report.depthKm,
            reportNum = step.report.reportNum
        )

    private fun Intent.putReportExtras(step: ReplayStep) {
        putExtra(EewService.EXTRA_MAG, step.report.magnitude)
        putExtra(EewService.EXTRA_PLACE, "${step.report.place.ifBlank { record.place }}（回放）")
        putExtra(EewService.EXTRA_DISTANCE, step.distanceKm)
        putExtra(EewService.EXTRA_ETA, step.etaSec)
        putExtra(EewService.EXTRA_INTENSITY, "%.1f".format(step.intensity))
        putExtra(EewService.EXTRA_DEPTH, step.report.depthKm)
        putExtra(EewService.EXTRA_REPORT_NUM, step.report.reportNum)
    }

    private fun formatOrigin(originTime: String, fallbackMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }
        val parsedMs = parseOriginTimeMs(originTime)
        if (parsedMs > 0) return sdf.format(java.util.Date(parsedMs))
        if (fallbackMs > 0) return sdf.format(java.util.Date(fallbackMs))
        return "时间未知"
    }
}
