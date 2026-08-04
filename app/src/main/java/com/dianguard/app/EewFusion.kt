package com.dianguard.app

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 多源数据融合决策引擎（v1.3.0 稳定版）。
 *
 * 关键设计原则：
 * - 震级用加权中位数融合（抗异常值）；
 * - 震中坐标选优（Centroid Selection）：取权重最高源的原始坐标，严禁加权平均；
 * - 首报标记 isPreliminary，禁止触发红色警报；
 * - 采用动态趋势积分替代静态权重惩罚；
 * - 版本锁机制：修正幅度 > ±0.4 级时强制推送更新。
 */

/** 单次融合结果 */
data class FusionResult(
    /** 加权融合后的震级 */
    val magnitude: Double,
    /** 置信度 0.0-1.0 */
    val confidence: Double,
    /** 参与融合的源数量 */
    val sourceCount: Int,
    /** 各源震级的标准差 */
    val stdDev: Double,
    /** 最优震中纬度（取自权重最高的源，非平均） */
    val fusedLat: Double,
    /** 最优震中经度（取自权重最高的源，非平均） */
    val fusedLon: Double,
    /** 是否为初报（仅1源、窗口未满即强制输出） */
    val isPreliminary: Boolean,
    /** 融合版本号（单调递增，用于检测修正幅度） */
    val version: Int
)

/** 源的可靠性记录 */
data class SourceReliability(
    val id: String,
    val name: String,
    val weight: Double,
    val totalFusions: Long,
    /** 动态趋势计数器：正数=连续高于共识，负数=连续低于共识 */
    val trendScore: Int
)

object EewFusion {

    private const val TAG = "EewFusion"

    /** 融合窗口：在此时间内收集多个源的同类报文 */
    private const val FUSION_WINDOW_MS = 1500L

    /** 动态趋势阈值：连续2次高于中位数 → 震级爬坡期，提升权重 */
    private const val TREND_BONUS_THRESHOLD = 2
    /** 趋势加成系数 */
    private const val TREND_BONUS_FACTOR = 0.10

    /** 源默认初始权重 */
    private const val DEFAULT_WEIGHT = 0.8
    private const val MIN_WEIGHT = 0.3
    /** 趋势封顶：单次地震事件中因趋势积分获得的额外权重总和上限 */
    private const val MAX_TREND_BONUS = 0.20
    /** 趋势归零条件：连续3次不变即停止强化 */
    private const val TREND_RESET_THRESHOLD = 3
    /** 坐标死区（km）：新坐标偏离上次输出 < 此值则沿用旧坐标，防止高频震荡 */
    private const val COORD_DEAD_ZONE_KM = 15.0

    // ===== 可靠性追踪 + 事件状态 =====
    private val reliability = ConcurrentHashMap<String, SourceReliability>()
    /** 每事件的版本计数器（Key=quakeKey），不同地震独立递增 */
    private val eventVersion = ConcurrentHashMap<String, Int>()
    /** 每事件上次输出的坐标（用于死区判断） */
    private data class LastOutput(val lat: Double, val lon: Double)
    private val lastOutput = ConcurrentHashMap<String, LastOutput>()
    /** 每事件的趋势累计加成（封顶用，Key=quakeKey+sourceId） */
    private val eventTrendBonus = ConcurrentHashMap<String, Double>()
    /** 每事件的连续不变计数器（Key=quakeKey+sourceId） */
    private val eventTrendStreak = ConcurrentHashMap<String, Int>()

    fun initSources() {
        for (src in EEW_SOURCES) {
            if (src.wsUrl.isBlank()) continue
            reliability.putIfAbsent(src.id, SourceReliability(src.id, src.name, DEFAULT_WEIGHT, 0L, 0))
        }
    }

    /** 临时缓存：key=地震物理去重键 → 该事件的多源报告列表 */
    internal data class BufferedReport(
        val eew: Eew,
        val sourceId: String,
        val receivedMs: Long,
        val reportNum: Int
    )

    // key=地震物理去重键, value=该事件的多源报告列表
    private val pending = ConcurrentHashMap<String, MutableList<BufferedReport>>()

    /**
     * 输入一条 EEW 报文，若融合窗口内收集到足够多源数据则输出融合结果。
     *
     * @param eew      解析后的 EEW
     * @param sourceId 数据源标识
     * @param quakeKey 地震物理去重键（makeQuakeKey 生成）
     * @return FusionResult? 若返回 null 表示仍在等待更多源；非 null 表示融合完成可决策
     */
    @Synchronized
    fun ingest(eew: Eew, sourceId: String, quakeKey: String): FusionResult? {
        val now = System.currentTimeMillis()
        val reports = pending.getOrPut(quakeKey) { mutableListOf() }

        // 同源同报数去重（同一源可能因重连等原因重复推送）
        if (reports.any { it.sourceId == sourceId && it.reportNum == eew.reportNum }) {
            Log.d(TAG, "[$quakeKey] 跳过同源重复报文: $sourceId#${eew.reportNum}")
            return null
        }

        reports.add(BufferedReport(eew, sourceId, now, eew.reportNum))

        // 检查是否已有足够时间/足够多源
        val firstMs = reports.minOf { it.receivedMs }
        val elapsed = now - firstMs

        if (elapsed < FUSION_WINDOW_MS && reports.size < 2) {
            // 还在窗口内但源数不够，继续等待（首个报文不放行，等窗口到期）
            return null
        }

        // 融合窗口到期或已收集 ≥2 源 → 执行融合
        val result = fuse(reports, quakeKey)
        pending.remove(quakeKey)
        return result
    }

    /**
     * 融合窗口到期时强制输出（EewAlertManager 定时器调用，防止网络抖动导致事件永久挂起）。
     */
    @Synchronized
    fun forceFlush(quakeKey: String): FusionResult? {
        val reports = pending.remove(quakeKey) ?: return null
        return fuse(reports, quakeKey)
    }

    /** 定时清理过期未融合的孤立事件（超过融合窗口 3 倍仍未触发，视为噪声丢弃） */
    fun cleanupStale(now: Long) {
        val cutoff = now - FUSION_WINDOW_MS * 3
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (key, reports) = it.next()
            if (reports.all { it.receivedMs < cutoff }) {
                Log.w(TAG, "融合超时丢弃: $key (${reports.size} 源, 首报距今${now - reports.minOf { it.receivedMs }}ms)")
                it.remove()
            }
        }
    }

    /** 获取各源可靠性数据（供主页/调试展示） */
    fun getReliability(): List<SourceReliability> = reliability.values.toList()

    // ===== 核心融合算法 =====

    private fun fuse(reports: List<BufferedReport>, quakeKey: String): FusionResult {
        val mags = reports.map { it.eew.magnitude }
        val sourceIds = reports.map { it.sourceId }.distinct()

        // 震级：加权中位数（抗异常值）
        val weights = reports.map { reliability[it.sourceId]?.weight ?: DEFAULT_WEIGHT }
        val fusedMag = weightedMedian(mags, weights)

        // 坐标选优（Centroid Selection）+ 死区过滤
        val bestSource = reports.maxByOrNull { reliability[it.sourceId]?.weight ?: DEFAULT_WEIGHT }!!
        val rawLat = bestSource.eew.latitude
        val rawLon = bestSource.eew.longitude
        val prev = lastOutput[quakeKey]
        val (fusedLat, fusedLon) = if (prev != null &&
            haversineKm(rawLat, rawLon, prev.lat, prev.lon) < COORD_DEAD_ZONE_KM) {
            // 坐标死区：新坐标与上次输出差异 < 15km，沿用旧坐标避免高频震荡
            prev.lat to prev.lon
        } else {
            lastOutput[quakeKey] = LastOutput(rawLat, rawLon)
            rawLat to rawLon
        }

        // 加权标准差
        val mean = mags.average()
        val variance = mags.zip(weights).sumOf { (m, w) -> w * (m - mean) * (m - mean) } /
                weights.sum().coerceAtLeast(1.0)
        val stdDev = sqrt(variance)

        // 置信度
        val srcFactor = (sourceIds.size.coerceAtMost(3) / 3.0)
        val consistencyFactor = (1.0 - (stdDev / 1.5).coerceIn(0.0, 1.0))
        val confidence = (srcFactor * 0.6 + consistencyFactor * 0.4).coerceIn(0.0, 1.0)

        // 版本号：按 quakeKey 独立递增（不同地震事件不互串）
        val v = eventVersion.getOrDefault(quakeKey, 0) + 1
        eventVersion[quakeKey] = v
        val isPreliminary = reports.size == 1

        // 动态趋势积分 + 封顶 + 连续不变归零
        for (report in reports) {
            val old = reliability[report.sourceId] ?: continue
            val delta = report.eew.magnitude - fusedMag
            val bonusKey = "$quakeKey|${report.sourceId}"

            val newTrend = when {
                delta > 0.3 -> {
                    eventTrendStreak[bonusKey] = 0  // 变化 → 重置连续不变计数
                    old.trendScore + 1
                }
                delta < -0.3 -> old.trendScore - 1
                else -> {
                    val streak = eventTrendStreak.getOrDefault(bonusKey, 0) + 1
                    eventTrendStreak[bonusKey] = streak
                    if (streak >= TREND_RESET_THRESHOLD) 0 else old.trendScore  // 连续3次不变 → 归零
                }
            }

            val currentBonus = eventTrendBonus.getOrDefault(bonusKey, 0.0)
            val rawNewWeight = when {
                newTrend >= TREND_BONUS_THRESHOLD -> old.weight + TREND_BONUS_FACTOR
                newTrend <= -TREND_BONUS_THRESHOLD -> old.weight - TREND_BONUS_FACTOR
                else -> old.weight
            }
            // 趋势封顶：本事件累积加成 ≤ +20%
            val actualBonus = rawNewWeight - DEFAULT_WEIGHT
            val cappedBonus = actualBonus.coerceIn(-MAX_TREND_BONUS, MAX_TREND_BONUS)
            val newWeight = (DEFAULT_WEIGHT + cappedBonus).coerceIn(MIN_WEIGHT, 1.0)
            eventTrendBonus[bonusKey] = cappedBonus

            reliability[report.sourceId] = old.copy(
                weight = newWeight, totalFusions = old.totalFusions + 1, trendScore = newTrend
            )
        }

        Log.i(TAG, "融合完成 v$v: $quakeKey | ${reports.size}源→M${"%.1f".format(fusedMag)} " +
                "震中(${"%.2f".format(fusedLat)},${"%.2f".format(fusedLon)})来自$bestSource.sourceId " +
                "σ=${"%.2f".format(stdDev)} 首报=$isPreliminary 置信度=${"%.0f".format(confidence * 100)}%")

        return FusionResult(fusedMag, confidence, sourceIds.size, stdDev, fusedLat, fusedLon, isPreliminary, v)
    }

    /** Haversine公式（内部副本，避免跨模块依赖） */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
    private fun sin(x: Double) = kotlin.math.sin(x)
    private fun cos(x: Double) = kotlin.math.cos(x)

    /** 加权中位数：按权重排序后，累加权重的 50% 分位点即为加权中位数 */
    private fun weightedMedian(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        if (values.size == 1) return values[0]

        val sorted = values.zip(weights).sortedBy { it.first }
        val totalWeight = weights.sum()
        var accumulated = 0.0
        for ((v, w) in sorted) {
            accumulated += w
            if (accumulated >= totalWeight / 2.0) return v
        }
        return sorted.last().first
    }

    /** 简单平均值（备用，当前未使用） */
    @Suppress("unused")
    private fun weightedAverage(values: List<Double>, weights: List<Double>): Double {
        val sum = values.zip(weights).sumOf { (v, w) -> v * w }
        val total = weights.sum()
        return if (total > 0) sum / total else 0.0
    }
}
