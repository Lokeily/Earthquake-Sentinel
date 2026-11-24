package com.dianguard.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 预警回放历史记录持久化（测试版专用，v1.4.0）。
 *
 * 与 QuakeHistory 的区别：
 *   - 保留 365 天 / 500 条（而非 7 天 / 100 条），覆盖近一年内的历史预警；
 *   - 数据源为 ReplayFetcher 从 CENC / USGS / EMSC 同步的历史目录；
 *   - 存储于独立 SharedPreferences key（replay_history_v1），互不干扰；
 *   - 记录格式复用 QuakeRecord，但 key 前缀为 "replay_" 避免与实时预警混淆。
 *
 * 写入语义：按 key upsert——同一地震的多次抓取只更新字段，不重复追加。
 */
object ReplayHistory {

    private const val TAG = "ReplayHistory"

    /** 最多保留的条数 */
    const val MAX_RECORDS = 500

    /** 最长保留时长：365 天 */
    const val KEEP_MS = 365L * 24 * 60 * 60 * 1000L

    private val lock = Any()

    /**
     * 批量记录（用于历史抓取一次导入多条）。
     * 仅做一次全量加载 + 一次排序裁剪 + 一次落盘。
     * @return 实际新增条数（去重后）
     */
    fun recordBatch(records: List<QuakeRecord>): Int {
        if (records.isEmpty()) return 0
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            var added = 0
            var changed = false
            for (record in records) {
                val idx = list.indexOfFirst { it.key == record.key }
                if (idx >= 0) {
                    val old = list[idx]
                    // 同 key 保留首次收到时间，其余字段用最新覆盖；多报序列必须合并，不能丢弃
                    list[idx] = record.copy(
                        timeMs = old.timeMs,
                        triggered = old.triggered || record.triggered,
                        backup = old.backup && record.backup,
                        sourceName = if (old.backup && !record.backup) record.sourceName else old.sourceName,
                        eventId = record.eventId.ifBlank { old.eventId },
                        sourceId = record.sourceId.ifBlank { old.sourceId },
                        reports = mergeReports(old.reports, record.reports)
                    )
                    changed = true
                } else {
                    list.add(record)
                    added++
                    changed = true
                }
            }
            if (!changed) return 0
            saveLocked(list)
            return added
        }
    }

    /** 读取全部历史（按时间倒序，最新在前） */
    fun all(): List<QuakeRecord> = synchronized(lock) { loadLocked() }

    /** 清空回放历史 */
    fun clear() {
        synchronized(lock) { AppConfig.replayHistoryJson = "[]" }
    }

    // ===================== 内部实现 =====================

    /** 合并多报序列（与 QuakeHistory 语义一致）：同一 sourceId+eventId+reportNum 保留最新快照 */
    private fun mergeReports(
        old: List<QuakeReportSnapshot>,
        incoming: List<QuakeReportSnapshot>
    ): List<QuakeReportSnapshot> {
        if (incoming.isEmpty()) return old
        val merged = LinkedHashMap<String, QuakeReportSnapshot>()
        for (report in old + incoming) {
            val id = "${report.sourceId}|${report.eventId}|${report.reportNum}"
            val previous = merged[id]
            if (previous == null || report.receivedMs >= previous.receivedMs) merged[id] = report
        }
        return merged.values
            .sortedWith(compareBy<QuakeReportSnapshot> { it.receivedMs }.thenBy { it.reportNum })
            .takeLast(100)
    }

    private fun parseReports(arr: JSONArray?): List<QuakeReportSnapshot> {
        if (arr == null) return emptyList()
        val out = ArrayList<QuakeReportSnapshot>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(QuakeReportSnapshot(
                sourceId = o.optString("sid", ""),
                eventId = o.optString("eid", ""),
                reportNum = o.optInt("rn", 1),
                receivedMs = o.optLong("recv", 0L),
                originTime = o.optString("ot", ""),
                place = o.optString("p", "未知地区"),
                magnitude = o.optDouble("m", 0.0),
                depthKm = o.optDouble("d", 0.0),
                epiLat = o.optDouble("lat", 0.0),
                epiLon = o.optDouble("lon", 0.0),
                sourceName = o.optString("s", "")
            ))
        }
        return out
    }

    private fun reportsToJson(reports: List<QuakeReportSnapshot>): JSONArray {
        val arr = JSONArray()
        for (r in reports) {
            arr.put(JSONObject().apply {
                put("sid", r.sourceId)
                put("eid", r.eventId)
                put("rn", r.reportNum)
                put("recv", r.receivedMs)
                put("ot", r.originTime)
                put("p", r.place)
                put("m", r.magnitude)
                put("d", r.depthKm)
                put("lat", r.epiLat)
                put("lon", r.epiLon)
                put("s", r.sourceName)
            })
        }
        return arr
    }

    /** 解析发震时间字符串为毫秒时间戳（用于排序），解析失败返回 0 */
    private fun parseOtMs(ot: String): Long {
        if (ot.isBlank()) return 0L
        return try {
            val cleaned = ot.trim().replace("T", " ").replace("Z", "")
                .replace(Regex("\\.\\d+"), "")
                .let { s ->
                    val tzIdx = s.indexOfFirst { it == '+' || it == '-' }
                    if (tzIdx > 10) s.substring(0, tzIdx).trim() else s
                }.take(19)
            if (cleaned.length < 16) return 0L
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun loadLocked(): List<QuakeRecord> {
        val raw = AppConfig.replayHistoryJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val cutoff = System.currentTimeMillis() - KEEP_MS
            val out = ArrayList<QuakeRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optLong("t", 0L)
                if (t < cutoff) continue
                out.add(
                    QuakeRecord(
                        key = o.optString("k", ""),
                        timeMs = t,
                        originTime = o.optString("ot", ""),
                        place = o.optString("p", "未知地区"),
                        magnitude = o.optDouble("m", 0.0),
                        depthKm = o.optDouble("d", 0.0),
                        intensity = o.optString("i", ""),
                        distanceKm = o.optDouble("dist", 0.0),
                        etaSec = o.optDouble("eta", 0.0),
                        sourceName = o.optString("s", ""),
                        reportNum = o.optInt("rn", 1),
                        triggered = o.optBoolean("tr", false),
                        backup = o.optBoolean("bk", false),
                        epiLat = o.optDouble("ela", 0.0),
                        epiLon = o.optDouble("elo", 0.0),
                        eventId = o.optString("eid", ""),
                        sourceId = o.optString("sid", ""),
                        reports = parseReports(o.optJSONArray("rp"))
                    )
                )
            }
            // 按发震时间倒序（最新在上）
            out.sortByDescending { r -> parseOtMs(r.originTime).let { if (it > 0L) it else r.timeMs } }
            out
        } catch (e: Exception) {
            Log.w(TAG, "回放历史记录解析失败: ${e.message}")
            emptyList()
        }
    }

    private fun saveLocked(list: List<QuakeRecord>) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val pruned = list
            .filter { it.timeMs >= cutoff }
            .sortedByDescending { r -> parseOtMs(r.originTime).let { if (it > 0L) it else r.timeMs } }
            .take(MAX_RECORDS)
        val arr = JSONArray()
        for (r in pruned) {
            arr.put(
                JSONObject().apply {
                    put("k", r.key)
                    put("t", r.timeMs)
                    put("ot", r.originTime)
                    put("p", r.place)
                    put("m", r.magnitude)
                    put("d", r.depthKm)
                    put("i", r.intensity)
                    put("dist", r.distanceKm)
                    put("eta", r.etaSec)
                    put("s", r.sourceName)
                    put("rn", r.reportNum)
                    put("tr", r.triggered)
                    put("bk", r.backup)
                    put("ela", r.epiLat)
                    put("elo", r.epiLon)
                    put("eid", r.eventId)
                    put("sid", r.sourceId)
                    put("rp", reportsToJson(r.reports))
                }
            )
        }
        try {
            AppConfig.replayHistoryJson = arr.toString()
        } catch (e: Exception) {
            Log.w(TAG, "回放历史记录写入失败: ${e.message}")
        }
    }
}
