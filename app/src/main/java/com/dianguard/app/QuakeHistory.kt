package com.dianguard.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条地震历史记录。
 *
 * @param key         跨源去重键（EewService.makeQuakeKey 生成），同一物理地震唯一
 * @param timeMs      本机首次收到该地震的时间戳
 * @param originTime  数据源给出的发震时刻（字符串，各源格式略有差异）
 * @param place       震中地名
 * @param magnitude   震级
 * @param depthKm     震源深度（km）
 * @param intensity   预估烈度展示串（源报优先，缺失时为「约X」）
 * @param distanceKm  震中距参考位置的球面距离
 * @param etaSec      S 波到达参考位置的估算秒数（收到报文那一刻起算）
 * @param sourceName  首次报出该地震的数据源名
 * @param reportNum   最后一次收到的报数（第几报）
 * @param triggered   是否触发过全屏告警（达到烈度阈值）
 * @param backup      是否来自备用探测源（震后速报，非实时预警）
 */
data class QuakeRecord(
    val key: String,
    val timeMs: Long,
    val originTime: String,
    val place: String,
    val magnitude: Double,
    val depthKm: Double,
    val intensity: String,
    val distanceKm: Double,
    val etaSec: Double,
    val sourceName: String,
    val reportNum: Int,
    val triggered: Boolean,
    val backup: Boolean
)

/**
 * 地震历史记录持久化（修复 P0 #1）。
 *
 * 背景：此前 EewService.recentQuakes 只是内存里的 LinkedHashMap，仅用于 10 分钟去重，
 * 服务一重启就全部丢失。对「插电常驻老手机、用户几天不开 App」的核心场景来说，
 * 期间收到的远震通知只是临时通知栏消息，划掉即消失，用户完全无法回溯
 * “这几天到底检测到了哪些地震”，也就无从判断软件是否真的在工作。
 *
 * 本对象把**每一条**收到的 EEW（含未达阈值、含备用源速报）落盘：
 *  - 存储介质：SharedPreferences 里的一个 JSON 数组字符串（无需引入数据库依赖）；
 *  - 容量上限：最近 7 天、最多 100 条，双重裁剪，避免老手机上无限膨胀；
 *  - 写入语义：按 key **upsert**——同一地震的第 2/3/4 报只更新参数（震级、烈度会随报数修正），
 *    不会在列表里堆出一串重复条目；首次收到时间与「已触发全屏告警」标记始终保留；
 *  - 线程安全：EewService 的多个 OkHttp 工作线程会并发写入，全部读写在同一把锁内串行化。
 */
object QuakeHistory {

    private const val TAG = "QuakeHistory"

    /** 最多保留的条数（超出后丢弃最旧的） */
    const val MAX_RECORDS = 100

    /** 最长保留时长：7 天 */
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000L

    private val lock = Any()

    /**
     * 记录一条地震（存在则更新）。
     * 由 EewService 在解析出每条有效报文后调用，运行在 OkHttp 工作线程。
     */
    fun record(record: QuakeRecord) {
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            val idx = list.indexOfFirst { it.key == record.key }
            if (idx >= 0) {
                val old = list[idx]
                // 后续报：保留首次收到时间与已告警标记，其余字段用最新报覆盖
                list[idx] = record.copy(
                    timeMs = old.timeMs,
                    triggered = old.triggered || record.triggered,
                    // 只要曾由主链路（WebSocket）报出过，就不再标记为备用源速报
                    backup = old.backup && record.backup,
                    sourceName = if (old.backup && !record.backup) record.sourceName else old.sourceName
                )
            } else {
                list.add(record)
            }
            saveLocked(list)
        }
    }

    /**
     * 批量记录（用于历史抓取一次导入多条）。
     * 仅做一次全量加载 + 一次排序裁剪 + 一次落盘，避免逐条 [record] 带来的 N 次 SharedPreferences 全量 I/O。
     * 批内重复 key 与已有历史重复 key 均按 upsert 去重。
     * @return 实际新增条数（去重后）
     */
    fun recordBatch(records: List<QuakeRecord>): Int {
        if (records.isEmpty()) return 0
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            var added = 0
            for (record in records) {
                val idx = list.indexOfFirst { it.key == record.key }
                if (idx >= 0) {
                    val old = list[idx]
                    list[idx] = record.copy(
                        timeMs = old.timeMs,
                        triggered = old.triggered || record.triggered,
                        // 只要曾由主链路（WebSocket）报出过，就不再标记为备用源速报
                        backup = old.backup && record.backup,
                        sourceName = if (old.backup && !record.backup) record.sourceName else old.sourceName
                    )
                } else {
                    list.add(record)
                    added++
                }
            }
            if (added == 0) return 0
            saveLocked(list)
            return added
        }
    }

    /** 读取全部历史（按时间倒序，最新在前） */
    fun all(): List<QuakeRecord> = synchronized(lock) { loadLocked() }

    /** 清空历史 */
    fun clear() {
        synchronized(lock) { AppConfig.historyJson = "[]" }
    }

    // ===================== 内部实现（均需持有 lock） =====================

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
        val raw = AppConfig.historyJson
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
                        backup = o.optBoolean("bk", false)
                    )
                )
            }
            // 按发震时间倒序（最新在上）；解析失败回退 timeMs
            out.sortByDescending { r -> parseOtMs(r.originTime).let { if (it > 0L) it else r.timeMs } }
            out
        } catch (e: Exception) {
            Log.w(TAG, "历史记录解析失败，保留原始数据: ${e.message}")
            // 不重置——保留旧 JSON，下次成功写入会自然覆盖损坏数据
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
                }
            )
        }
        try {
            AppConfig.historyJson = arr.toString()
        } catch (e: Exception) {
            // 极端低存储 / 文件系统故障时 SP 写入可能失败；此时静默丢弃本次写入，
            // 保留已有历史数据不丢，由下次成功写入自然覆盖。
            Log.w(TAG, "历史记录写入失败（可能存储空间不足），保留已有数据: ${e.message}")
        }
    }
}
