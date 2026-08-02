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

    /** 读取全部历史（按时间倒序，最新在前） */
    fun all(): List<QuakeRecord> = synchronized(lock) { loadLocked() }

    /** 清空历史 */
    fun clear() {
        synchronized(lock) { AppConfig.historyJson = "[]" }
    }

    // ===================== 内部实现（均需持有 lock） =====================

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
                if (t < cutoff) continue // 过期条目直接丢弃
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
            out.sortByDescending { it.timeMs }
            out
        } catch (e: Exception) {
            // 数据损坏（极端情况下的写入中断）：丢弃重来，绝不因历史记录影响预警主链路
            Log.w(TAG, "历史记录解析失败，已重置: ${e.message}")
            AppConfig.historyJson = "[]"
            emptyList()
        }
    }

    private fun saveLocked(list: List<QuakeRecord>) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val pruned = list
            .filter { it.timeMs >= cutoff }
            .sortedByDescending { it.timeMs }
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
        AppConfig.historyJson = arr.toString()
    }
}
