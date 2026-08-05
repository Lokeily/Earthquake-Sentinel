package com.dianguard.app

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 多源数据融合决策引擎（开源精简版）。
 *
 * ⚠️ 闭源说明：本项目原自研「EewFusion 多源融合决策引擎」及其自学习校准模块
 * （FusionCalibration）已转为**闭源专有**（见 README 许可声明）。本文件为开源仓库
 * 中的**兼容精简实现**：保留对外接口签名（ingest / forceFlush / FusionResult /
 * BufferedReport / initSources / getReliability），使 EewAlertManager 等调用方
 * 无需改动即可编译运行；内部融合算法简化为多源加权平均 + 坐标选优 + 去重，
 * 不含动态趋势积分、可靠性自学习等闭源智能。
 *
 * 数据流：多源报文 → ingest() 在 1.5s 窗口内收集 → 加权平均震级 → 坐标选优 → 输出。
 */

/** 单次融合结果（接口与闭源版一致，供调用方决策） */
data class FusionResult(
    /** 融合后的震级 */
    val magnitude: Double,
    /** 置信度 0.0-1.0 */
    val confidence: Double,
    /** 参与融合的源数量 */
    val sourceCount: Int,
    /** 各源震级的标准差 */
    val stdDev: Double,
    /** 最优震中纬度（取权重最高源，非平均） */
    val fusedLat: Double,
    /** 最优震中经度（取权重最高源，非平均） */
    val fusedLon: Double,
    /** 是否为初报（仅1源、窗口未满即强制输出） */
    val isPreliminary: Boolean,
    /** 融合版本号（单调递增，用于检测修正幅度） */
    val version: Int
)

/** 源可靠性记录（开源版为静态展示用，权重固定为默认值） */
data class SourceReliability(
    val id: String,
    val name: String,
    val weight: Double,
    val totalFusions: Long,
    /** 趋势计数器（开源版恒为 0，保留字段兼容） */
    val trendScore: Int
)

object EewFusion {

    private const val TAG = "EewFusion"

    /** 融合窗口：在此时间内收集多个源的同类报文 */
    private const val FUSION_WINDOW_MS = 1500L

    /** 源默认初始权重（开源版固定权重，不参与学习） */
    private const val DEFAULT_WEIGHT = 0.8
    private const val MIN_WEIGHT = 0.3
    private const val MAX_WEIGHT = 1.0

    /** 坐标死区（km）：新坐标偏离上次输出 < 此值则沿用旧坐标，防止高频震荡 */
    private const val COORD_DEAD_ZONE_KM = 15.0

    /**
     * 事件级状态表的「最后活动」保留时长。
     * lastOutput / eventVersion 两张表按 quakeKey 累积，此前只增不减——作为常驻前台服务，
     * 其占用随累计处理的地震次数单调增长。现记录每 quakeKey 的最后处理时间，超过此 TTL
     * 即随 cleanupStale 一同淘汰，使内存占用在长周期运行下有界。
     */
    private const val STALE_TOUCH_TTL_MS = 30 * 60_000L // 30 分钟

    // ===== 可靠性追踪 + 事件状态 =====
    private val reliability = ConcurrentHashMap<String, SourceReliability>()
    /** 每事件的版本计数器（Key=quakeKey），不同地震独立递增 */
    private val eventVersion = ConcurrentHashMap<String, Int>()
    /** 每事件上次输出的坐标（用于死区判断） */
    private data class LastOutput(val lat: Double, val lon: Double)
    private val lastOutput = ConcurrentHashMap<String, LastOutput>()
    /** 每 quakeKey 的最后处理时间（驱动上述两张事件表的 TTL 淘汰） */
    private val lastTouch = ConcurrentHashMap<String, Long>()

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
        lastTouch[quakeKey] = now   // 标记事件活动时间，供 cleanupStale TTL 淘汰

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
                Log.w(TAG, "融合超时丢弃: $key (${reports.size} 源)")
                it.remove()
            }
        }

        // 淘汰长期无活动事件的状态，防止 lastOutput / eventVersion 随运行时间无限增长。
        val touchCutoff = now - STALE_TOUCH_TTL_MS
        val tit = lastTouch.entries.iterator()
        while (tit.hasNext()) {
            val entry = tit.next()
            if (entry.value < touchCutoff) {
                val k = entry.key
                tit.remove()
                lastOutput.remove(k)
                eventVersion.remove(k)
            }
        }
    }

    /** 获取各源可靠性数据（供主页/调试展示） */
    fun getReliability(): List<SourceReliability> = reliability.values.toList()

    // ===== 核心融合算法（开源精简版：加权平均 + 坐标选优） =====

    private fun fuse(reports: List<BufferedReport>, quakeKey: String): FusionResult {
        val mags = reports.map { it.eew.magnitude }
        val sourceIds = reports.map { it.sourceId }.distinct()

        // 震级：按源权重加权平均（开源版权重固定，不做动态学习）
        val weights = reports.map { reliability[it.sourceId]?.weight ?: DEFAULT_WEIGHT }
        val totalW = weights.sum().coerceAtLeast(1.0)
        val fusedMag = mags.zip(weights).sumOf { (m, w) -> m * w } / totalW

        // 坐标选优（Centroid Selection）：取权重最高源的原始坐标，严禁加权平均
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
        val variance = mags.zip(weights).sumOf { (m, w) -> w * (m - mean) * (m - mean) } / totalW
        val stdDev = sqrt(variance)

        // 置信度：多源一致 → 高置信；单源初报 → 低置信 + isPreliminary 标记
        val srcFactor = (sourceIds.size.coerceAtMost(3) / 3.0)
        val consistencyFactor = (1.0 - (stdDev / 1.5).coerceIn(0.0, 1.0))
        val confidence = (srcFactor * 0.6 + consistencyFactor * 0.4).coerceIn(0.0, 1.0)

        // 版本号：按 quakeKey 独立递增（不同地震事件不互串）
        val v = eventVersion.getOrDefault(quakeKey, 0) + 1
        eventVersion[quakeKey] = v
        val isPreliminary = reports.size == 1

        Log.i(TAG, "融合完成(开源精简版) v$v: $quakeKey | ${reports.size}源→M${"%.1f".format(fusedMag)} " +
                "震中(${"%.2f".format(fusedLat)},${"%.2f".format(fusedLon)}) σ=${"%.2f".format(stdDev)} " +
                "置信度=${"%.0f".format(confidence * 100)}%")

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
}
