package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * ICL（成都高新减灾研究所）官方 EEW HTTP 轮询源（v1.3.0，v1.4.3 增量提速）。
 *
 * ICL 是中国大陆唯一的官方地震预警公众服务系统，覆盖 31 省市区、90% 地震区人口。
 * 其 HTTP 接口 `mobile-new.chinaeew.cn/v1/earlywarnings` 免鉴权、返回实时 EEW 记录，
 * 含 eventId/updates/经纬度/深度/震中地名/震级/震中烈度。
 *
 * 本 Poller 采用增量模式：以 start_at = 已见最大 updateAt 拉取，只返回新增/更新事件
 * （新地震 + 同一事件的 updates 修正报），静止期响应仅 ~33B。轮询间隔 1 秒，平均接收
 * 延迟约 0.5 秒，接近推送级；新事件送入 EewAlertManager.handleRaw 立即研判，不做多源
 * 融合等待——谁先到、烈度达标谁就触发，最快送达用户。
 *
 * 设计要点：
 * - 轮询间隔 1s（增量模式带宽可忽略）：
 * - 服务端 start_at 增量过滤 + 内存去重表（2h TTL）：避免重复处理
 * - 独立 daemon 线程：不阻塞 WebSocket 主链路
 * - 冷静期：服务启动后前 15s 不送入（避免历史数据误触）
 */
class IclPoller(
    private val onEew: (eew: Eew, sourceId: String) -> Unit,
    private val onStatus: (connected: Boolean, lastDataMs: Long, failCount: Long) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "IclPoller"
        /** ICL 实时预警端点；start_at 支持服务端增量过滤（updateAt > start_at），静止期近乎零流量 */
        private const val ICL_ENDPOINT = "https://mobile-new.chinaeew.cn/v1/earlywarnings"
        /** 轮询间隔：1 秒（v1.4.3 增量模式：start_at 只取新事件，静止期响应仅 ~33B，1s 轮询带宽可忽略） */
        const val NORMAL_INTERVAL_MS = 1000L
        const val FAST_INTERVAL_MS = 1000L
        private const val SEEN_TTL_MS = 2 * 60 * 60 * 1000L  // 2h
    }

    @Volatile var active = false
        private set

    /** 当前轮询间隔（增量模式 1s，可动态调整） */
    @Volatile private var pollIntervalMs = NORMAL_INTERVAL_MS

    /** 服务端增量游标：已见过的最大 updateAt（毫秒）。start_at 传此值 → 只取更新过的/新增事件 */
    @Volatile private var lastServerMs = 0L

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

    /**
     * 动态调整轮询间隔（下一轮生效，无需重启）。
     * 增量模式 1s 已近推送延迟；此方法保留供未来权衡带宽时调大。
     */
    fun setPollInterval(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(500L, 10_000L)
        if (clamped == pollIntervalMs) return
        pollIntervalMs = clamped
        Log.i(TAG, "轮询间隔调整: ${clamped}ms")
    }

    fun start() {
        if (active) return
        active = true
        Log.i(TAG, "ICL 轮询启动，${pollIntervalMs}ms/次（增量模式）")
        pollRunnable = object : Runnable {
            override fun run() {
                pollAsync()
                // 每轮读取当前间隔：setPollInterval 的调整下一轮自然生效
                handler.postDelayed(this, pollIntervalMs)
            }
        }
        handler.post(pollRunnable!!)
    }

    fun stop() {
        if (!active) return
        active = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        // 注意：不 shutdown executor——它是构造时的单例字段，服务 stop→start 会复用，
        // shutdown 后再 execute 会抛 RejectedExecutionException 使轮询崩溃。daemon 单线程池
        // 空闲仅占 1 个线程，无累积问题；active 守卫已足以阻止残留任务上报。
        Log.i(TAG, "ICL 轮询停止")
    }

    private fun pollAsync() {
        executor.execute {
            if (!active) return@execute // 关停后残留任务立即退出，不再上报失败
            val now = System.currentTimeMillis()
            try {
                // 增量拉取：start_at=上次已见最大 updateAt，只返回更新过/新增的事件。
                // 静止期响应仅 ~33B，1s 轮询近乎零流量；活跃期拿到的是实时修正报。
                val url = "$ICL_ENDPOINT?start_at=$lastServerMs&updates="
                val req = Request.Builder().url(url)
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
                    var maxUpdateAt = lastServerMs
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i) ?: continue
                        // 推进服务端游标：取所有返回事件的 updateAt 最大值（含重复/已见，防漏）
                        val updateAt = obj.optLong("updateAt", 0L)
                        if (updateAt > maxUpdateAt) maxUpdateAt = updateAt
                        val eventId = obj.optString("eventId", "")
                        if (eventId.isBlank()) continue
                        // 同一 eventId 会有 updates=1/2/3... 官方修正报；按 eventId+报数去重
                        val reportNum = obj.optInt("updates", 1)
                        if (!markSeen("$eventId|$reportNum")) continue
                        val eew = parseIclEew(obj) ?: continue
                        Log.i(TAG, "ICL 实时: ${eew.hypoCenter} M${eew.magnitude} #${eew.reportNum}")
                        // v1.4.4 稳定增强：研判直接在工作线程执行（与 WebSocket 源一致），
                        // 不再 post 到主线程。研判链路内部线程安全（去重锁/历史锁/AppEvents 均加锁，
                        // startActivity 已由 triggerAlert 内部 post 回主线程），且地震活跃期 ICL
                        // 每秒多条修正报，每条含 SharedPreferences 全量读写，主线程执行会卡顿/ANR。
                        onEew(eew, "icl")
                    }
                    lastServerMs = maxUpdateAt
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
