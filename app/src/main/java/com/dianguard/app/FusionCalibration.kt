package com.dianguard.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 多源融合自学习校准模块（v1.4.0，数据驱动优化）。
 *
 * 核心思想：把每次融合决策（各源震级快照）保存为样本，事后用 CENC 官方速报目录
 * （最终震级）作为 ground truth 回填，统计每个数据源相对真值的系统性偏差，
 * 再在下次融合时对源震级做在线修正，让引擎"越用越准"。
 *
 * 闭环流程：
 *   ① collectSample()：融合发生时记录每源 (sourceId, magnitude, timeMs, quakeKey)；
 *   ② fillGroundTruth()：HistoryFetcher 拉到 CENC 目录后，按 时间窗 + 距离 匹配回填真值；
 *   ③ rebuildProfiles()：对已回填样本统计每源 Δ = 源震级 − 真值 的 μ / σ / n；
 *   ④ correctMagnitude()：融合前查询源画像，n ≥ MIN_SAMPLES 时输出 M' = M − μ。
 *
 * 防过拟合设计：
 *  - MIN_SAMPLES = 10：样本不足的源不做修正（回退默认）；
 *  - 修正幅度钳制 [−MAX_CORRECTION, +MAX_CORRECTION]（±0.5 级），防异常偏差污染；
 *  - 样本库 200 条上限 + 30 天裁剪，只保留近期分布（源行为可能随版本变化）。
 *
 * 持久化：SharedPreferences 两个 key（样本库 + 源画像），与 QuakeHistory 同模式，
 * 老手机低开销；不阻塞任何告警链路（全部 try/catch + synchronized 串行化）。
 */
object FusionCalibration {

    private const val TAG = "FusionCalibration"

    /** 启用修正的最小样本数：< 此值视为"尚未学够"，不做修正 */
    private const val MIN_SAMPLES = 10

    /** 单次修正幅度钳制（级）：防异常偏差 / 恶意数据污染 */
    private const val MAX_CORRECTION = 0.5

    /** 样本库容量上限 */
    private const val MAX_SAMPLES = 200

    /** 样本保留时长：30 天 */
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000L

    /** 真值匹配参数（与离线分析脚本一致） */
    private const val MATCH_TIME_WINDOW_MS = 60_000L   // 时间窗 ±60s
    private const val MATCH_DIST_KM = 200.0            // 距离 ≤ 200km

    /** 一条融合样本：一次地震事件中某个源的一报 */
    data class CalibSample(
        val quakeKey: String,       // 跨源去重键
        val sourceId: String,       // 数据源标识（cenc/icl/usgs/emsc...）
        val mag: Double,            // 该源报告的震级
        val timeMs: Long,           // 收到报文时刻
        val lat: Double, val lon: Double, // 该源给的震中坐标（用于真值匹配）
        var truthMag: Double? = null // 回填的 CENC 真值（null=尚未回填）
    )

    /** 源画像：学到的系统性偏差统计 */
    data class SourceProfile(
        val sourceId: String,
        val meanBias: Double,   // μ = Σ(源−真值)/n，正值=源偏高
        val stdDev: Double,     // σ
        val sampleCount: Int    // n
    )

    private val lock = Any()

    /** 内存态样本（源画像由样本实时重算，不单独持久化，避免双写不一致） */
    private val samples = ConcurrentHashMap<String, CalibSample>()

    /** 内存态源画像缓存（rebuildProfiles 后更新） */
    private val profiles = ConcurrentHashMap<String, SourceProfile>()

    // ===================== 样本采集 =====================

    /**
     * 融合发生时调用：记录某个源的一报震级快照。
     * 同一 quakeKey+sourceId 只保留最新一报（后续报覆盖首报），避免样本膨胀。
     */
    fun collectSample(quakeKey: String, sourceId: String, mag: Double, timeMs: Long, lat: Double, lon: Double) {
        if (mag <= 0) return
        synchronized(lock) {
            val key = "$quakeKey|$sourceId"
            samples[key] = CalibSample(quakeKey, sourceId, mag, timeMs, lat, lon)
            trimLocked()
            persistSamplesLocked()
        }
    }

    // ===================== 真值回填 =====================

    /**
     * HistoryFetcher 拉到 CENC 目录后调用：对每个目录条目，在样本库中按
     * 时间窗 ±60s + 距离 ≤200km 匹配同震样本并回填真值。
     * 匹配成功后该样本计入偏差统计。
     */
    fun fillGroundTruth(cencTimeMs: Long, cencMag: Double, cencLat: Double, cencLon: Double) {
        if (cencTimeMs <= 0 || cencMag <= 0) return
        synchronized(lock) {
            var changed = false
            val now = System.currentTimeMillis()
            for (s in samples.values) {
                if (s.truthMag != null) continue
                if (now - s.timeMs > KEEP_MS) continue
                if (kotlin.math.abs(s.timeMs - cencTimeMs) > MATCH_TIME_WINDOW_MS) continue
                val d = haversineKm(s.lat, s.lon, cencLat, cencLon)
                if (d > MATCH_DIST_KM) continue
                // 同震匹配：回填真值
                samples[s.quakeKey + "|" + s.sourceId] = s.copy(truthMag = cencMag)
                changed = true
            }
            if (changed) {
                rebuildProfilesLocked()
                persistSamplesLocked()
            }
        }
    }

    // ===================== 修正查询（融合入口） =====================

    /**
     * 融合前调用：返回该源修正后的震级。
     *  - 源画像样本数 ≥ MIN_SAMPLES：M' = M − μ，并钳制在 ±MAX_CORRECTION；
     *  - 样本不足：原值返回（尚未学够，不冒险）。
     */
    fun correctMagnitude(sourceId: String, rawMag: Double): Double {
        if (rawMag <= 0) return rawMag
        val p = profiles[sourceId] ?: return rawMag
        if (p.sampleCount < MIN_SAMPLES) return rawMag
        val correction = p.meanBias.coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
        return (rawMag - correction).coerceAtLeast(0.1)
    }

    /** 源画像查询（调试/设置页展示用） */
    fun profileOf(sourceId: String): SourceProfile? = profiles[sourceId]

    /** 全部源画像（设置页"引擎学习进度"展示用） */
    fun allProfiles(): List<SourceProfile> = profiles.values.toList()

    /** 当前有效样本总数 */
    fun sampleCount(): Int = synchronized(lock) { samples.values.count { it.truthMag != null } }

    // ===================== 内部：偏差统计 =====================

    private fun rebuildProfilesLocked() {
        val bySource = HashMap<String, MutableList<Double>>()
        for (s in samples.values) {
            val t = s.truthMag ?: continue
            bySource.getOrPut(s.sourceId) { mutableListOf() }.add(s.mag - t)
        }
        profiles.clear()
        for ((id, diffs) in bySource) {
            val n = diffs.size
            if (n < MIN_SAMPLES) continue
            val mu = diffs.sum() / n
            val varSum = diffs.sumOf { (it - mu) * (it - mu) }
            val sigma = kotlin.math.sqrt(varSum / n)
            profiles[id] = SourceProfile(id, mu, sigma, n)
        }
    }

    private fun trimLocked() {
        val now = System.currentTimeMillis()
        // 1) 剔除超期样本
        val it = samples.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.timeMs > KEEP_MS) it.remove()
        }
        // 2) 容量裁剪：优先保留已回填真值的样本（它们是有效学习数据），
        //    再按收到时间保留最新
        if (samples.size > MAX_SAMPLES) {
            val sorted = samples.values.sortedWith(
                compareByDescending<CalibSample> { it.truthMag != null }
                    .thenByDescending { it.timeMs }
            )
            val keep = sorted.take(MAX_SAMPLES).map { it.quakeKey + "|" + it.sourceId }.toSet()
            samples.keys.retainAll(keep)
        }
    }

    // ===================== 持久化 =====================

    private fun persistSamplesLocked() {
        try {
            val arr = JSONArray()
            for (s in samples.values) {
                arr.put(
                    JSONObject().apply {
                        put("q", s.quakeKey); put("src", s.sourceId)
                        put("m", s.mag); put("t", s.timeMs)
                        put("la", s.lat); put("lo", s.lon)
                        s.truthMag?.let { put("tr", it) }
                    }
                )
            }
            AppConfig.fusionCalibJson = arr.toString()
        } catch (e: Exception) {
            // 单测环境未初始化 AppConfig（无 Context）或存储故障：纯内存模式继续运行
            Log.w(TAG, "样本持久化失败（内存模式继续）: ${e.message}")
        }
    }

    /** App 启动/服务启动时调用：从 SP 恢复样本并重建画像 */
    fun load() {
        synchronized(lock) {
            samples.clear()
            try {
                val raw = AppConfig.fusionCalibJson
                if (raw.isBlank() || raw == "[]") return
                val arr = JSONArray(raw)
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optLong("t", 0L)
                    if (t <= 0 || now - t > KEEP_MS) continue
                    samples["${o.optString("q")}|${o.optString("src")}"] = CalibSample(
                        quakeKey = o.optString("q"),
                        sourceId = o.optString("src"),
                        mag = o.optDouble("m", 0.0),
                        timeMs = t,
                        lat = o.optDouble("la", 0.0),
                        lon = o.optDouble("lo", 0.0),
                        truthMag = if (o.has("tr")) o.optDouble("tr", 0.0) else null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "样本加载失败（保留原始数据）: ${e.message}")
            }
            rebuildProfilesLocked()
            Log.i(TAG, "校准模块加载: 样本=${samples.size} 画像=${profiles.size}")
        }
    }

    /** 清空学习数据（设置页"重置引擎学习"用） */
    fun reset() {
        synchronized(lock) {
            samples.clear(); profiles.clear()
            try {
                AppConfig.fusionCalibJson = "[]"
            } catch (_: Exception) {
                // 单测环境未初始化 AppConfig：纯内存模式
            }
        }
    }
}
