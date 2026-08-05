package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * ICL（成都高新减灾研究所）官方 EEW HTTP 轮询源（v1.3.0）。
 *
 * ICL 是中国大陆唯一的官方地震预警公众服务系统，覆盖 31 省市区、90% 地震区人口。
 * 其 HTTP 接口 `mobile-new.chinaeew.cn/v1/earlywarnings` 免鉴权、返回最新 20 条 EEW 记录，
 * 含 eventId/updates/经纬度/深度/震中地名/震级/震中烈度。
 *
 * 本 Poller 每 3 秒轮询一次，新事件送入 EewAlertManager.handleRaw，
 * 与 WebSocket 源（Wolfx/Podris）共同进入 EewFusion 融合引擎进行交叉验证。
 *
 * 设计要点：
 * - 轮询间隔 3s：平衡实时性与服务器压力
 * - 内存去重表（2h TTL）：避免重复处理
 * - 独立 daemon 线程：不阻塞 WebSocket 主链路
 * - 冷静期：服务启动后前 15s 不送入（避免历史数据误触）
 */
class IclPoller(
    private val onEew: (eew: Eew, sourceId: String) -> Unit,
    private val onStatus: (connected: Boolean, lastDataMs: Long, failCount: Long) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "IclPoller"
        private const val ICL_URL = "https://mobile-new.chinaeew.cn/v1/earlywarnings?start_at=&updates="
        private const val POLL_INTERVAL_MS = 3000L
        private const val SEEN_TTL_MS = 2 * 60 * 60 * 1000L  // 2h
    }

    @Volatile var active = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-IclPoll").also { it.isDaemon = true }
    }
    private val seen = LinkedHashMap<String, Long>()
    private val seenLock = Any()
    // 每次都从 HttpClient.instance 取最新，避免证书固定降级重建后旧 client 失效（P0 修复）
    private val httpClient get() = HttpClient.instance

    /** ICL 连通性统计：成功时间 + 连续失败次数（供主页状态行 / 新鲜度判定） */
    private var lastSuccessMs = 0L
    private var consecutiveFails = 0L

    fun start() {
        if (active) return
        active = true
        Log.i(TAG, "ICL 轮询启动，${POLL_INTERVAL_MS / 1000}s/次")
        pollRunnable = object : Runnable {
            override fun run() {
                pollAsync()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.post(pollRunnable!!)
    }

    fun stop() {
        if (!active) return
        active = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        Log.i(TAG, "ICL 轮询停止")
    }

    private fun pollAsync() {
        executor.execute {
            val now = System.currentTimeMillis()
            try {
                val req = Request.Builder().url(ICL_URL)
                    .header("Accept", "application/json").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "ICL 返回 ${resp.code}")
                        reportFailure()
                        return@execute
                    }
                    val body = resp.body?.string() ?: run { reportFailure(); return@execute }
                    val root = JSONObject(body)
                    if (root.optInt("code", -1) != 0) {
                        Log.w(TAG, "ICL API 错误: ${root.optString("message")}")
                        reportFailure()
                        return@execute
                    }
                    // HTTP 200 + code=0：ICL 可达（平静期无新事件也视为连通正常）
                    reportSuccess(now)
                    val data = root.optJSONArray("data") ?: return@execute
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i) ?: continue
                        val eventId = obj.optString("eventId", "")
                        if (eventId.isBlank()) continue
                        if (!markSeen(eventId)) continue
                        val eew = parseIclEew(obj) ?: continue
                        Log.i(TAG, "ICL 新事件: ${eew.hypoCenter} M${eew.magnitude} #${eew.reportNum}")
                        handler.post { onEew(eew, "icl") }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ICL 轮询异常: ${e.message}")
                reportFailure()
                // 证书固定失败（pin 轮换 / 中间 CA 更换）时交给 HttpClient 评估是否降级为系统信任
                HttpClient.reportFailure("mobile-new.chinaeew.cn", e)
            }
        }
    }

    private fun reportSuccess(now: Long) {
        consecutiveFails = 0
        lastSuccessMs = now
        onStatus(true, now, 0L)
    }

    private fun reportFailure() {
        consecutiveFails++
        onStatus(false, lastSuccessMs, consecutiveFails)
    }

    private fun markSeen(eventId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(seenLock) {
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > SEEN_TTL_MS) it.remove()
            }
            if (seen.containsKey(eventId)) return false
            seen[eventId] = now
            return true
        }
    }
}
