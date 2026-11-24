package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Wolfx WS→HTTP 自动降级轮询器（v1.4.3）。
 *
 * ## 解决的问题
 * CENC/SC/CQ/CWA 四路 WebSocket 全部经由 wolfx.jp 单一网关。网关偶发 503 或
 * 运营商掐断 WS 长连接时，四路同时显示「源异常」——冗余是假的。
 *
 * 实测确认 wolfx 为每个机构提供同格式 HTTP EEW 端点（仅返回该机构最新一报）：
 *   cenc → api.wolfx.jp/cenc_eew.json
 *   sc   → api.wolfx.jp/sc_eew.json
 *   cq   → api.wolfx.jp/cq_eew.json
 *   cwa  → api.wolfx.jp/cwa_eew.json
 * 报文与 WebSocket 完全同构（parseEew 直接解析，含 Magunitude 拼写容错），
 * 且 HTTP 层在 WS 被掐断时通常仍可用（中间设备只掐 Upgrade 长连接）。
 *
 * ## 工作方式
 *  - WS 连续失败 ≥2 次 → 自动启动该源的 HTTP 轮询（5 秒/次）；
 *  - WS 恢复（onOpen）→ 自动停止轮询，切回实时推送；
 *  - 每次成功轮询都会回报 alive=true，使主页显示「HTTP降级」而非「源异常」，
 *    且头条保持「守护中」，避免误激活备用探测源（备用源是更慢的最后兜底）；
 *  - HTTP 也失败 → alive=false → 头条按全断处理 → BackupSource 兜底。
 *
 * ## 安全过滤（防止旧事件误报）
 *  - 新鲜度：只放行发震 4 分钟内的报文（S 波 4 分钟最远约 840km，更旧无预警价值）。
 *    HTTP 端点会长期保留最后一次事件，安静期轮询会反复拉到数天前的旧震，必须过滤；
 *  - 撤销：CWA 的 isCancel=true 表示官方撤销的误报，绝不播发；
 *  - 去重：同一 eventId+reportNum 只送入告警链路一次（轮询会反复拉到同一份）；
 *  - 非法：震级 ≤ 0 或发震时刻无法解析的报文直接丢弃。
 */
class EewHttpFallbackPoller(
    /** 解析成功的 EEW 送入告警链路（sourceId 与 WS 相同，共用去重/研判/历史） */
    private val onEew: (eew: Eew, sourceId: String) -> Unit,
    /** 每次轮询结果回报：note=状态文案，dataMs=最近成功时间，alive=HTTP 通道是否存活 */
    private val onStatus: (sourceId: String, note: String?, dataMs: Long, alive: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "EewHttpFallback"

        /** 轮询间隔：4 秒（4 源全降级时约 60 req/min，应急态可接受） */
        private const val POLL_INTERVAL_MS = 4_000L

        /** 新鲜度窗口：只放行发震 4 分钟内的报文 */
        private const val FRESH_WINDOW_MS = 240_000L

        /** 「接收中」判定窗口：最近 20 秒内成功过即视为 HTTP 通道存活 */
        private const val ALIVE_WINDOW_MS = 20_000L

        val FALLBACK_URLS = mapOf(
            "cenc" to "https://api.wolfx.jp/cenc_eew.json",
            "sc" to "https://api.wolfx.jp/sc_eew.json",
            "cq" to "https://api.wolfx.jp/cq_eew.json",
            "cwa" to "https://api.wolfx.jp/cwa_eew.json"
        )
    }

    private class FallbackState(val sourceId: String, val url: String) {
        var lastSignature = ""
        var lastSuccessMs = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, FallbackState>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-HttpFallback").also { it.isDaemon = true }
    }

    /** 服务销毁标志：停止一切调度 */
    @Volatile private var stopped = false

    private val httpClient get() = HttpClient.instance

    /** 启动某源的 HTTP 降级轮询（幂等） */
    fun startFallback(sourceId: String) {
        if (stopped) return
        val url = FALLBACK_URLS[sourceId] ?: return
        if (states.containsKey(sourceId)) return
        val state = FallbackState(sourceId, url)
        states[sourceId] = state
        Log.w(TAG, "[$sourceId] WS 不可用，启动 HTTP 降级轮询: $url")
        notifyStatus(state, "HTTP降级·轮询中", 0L, false)
        schedulePoll(state, 0L)
    }

    /** 停止某源降级（WS 恢复时调用） */
    fun stopFallback(sourceId: String) {
        if (states.remove(sourceId) != null) {
            Log.i(TAG, "[$sourceId] WS 已恢复，停止 HTTP 降级轮询")
        }
    }

    fun stopAll() {
        stopped = true
        states.clear()
        handler.removeCallbacksAndMessages(null)
    }

    fun isActive(sourceId: String): Boolean = states.containsKey(sourceId)

    /** 该源 HTTP 通道是否存活（最近 20 秒内成功过一次轮询） */
    fun isReceiving(sourceId: String): Boolean {
        val s = states[sourceId] ?: return false
        return s.lastSuccessMs > 0L && System.currentTimeMillis() - s.lastSuccessMs <= ALIVE_WINDOW_MS
    }

    private fun schedulePoll(state: FallbackState, delayMs: Long) {
        if (stopped) return
        handler.postDelayed({
            if (stopped || states[state.sourceId] !== state) return@postDelayed
            executor.execute {
                pollOnce(state)
                schedulePoll(state, POLL_INTERVAL_MS)
            }
        }, delayMs)
    }

    private fun pollOnce(state: FallbackState) {
        val now = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(state.url)
                .header("Accept", "application/json").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "[${state.sourceId}] HTTP 降级返回 ${resp.code}")
                    notifyStatus(state, "HTTP降级·${resp.code}", 0L, false)
                    return
                }
                val body = resp.body?.string() ?: run {
                    notifyStatus(state, "HTTP降级·空响应", 0L, false)
                    return
                }
                // 到达此处说明 HTTP 通道本身存活（即使内容是安静期的旧事件）
                state.lastSuccessMs = now

                val obj = try { JSONObject(body) } catch (_: Exception) {
                    notifyStatus(state, "HTTP降级·正常", now, true); return
                }

                // 官方撤销的误报绝不播发
                if (obj.optBoolean("isCancel", false)) {
                    notifyStatus(state, "HTTP降级·官方已撤销", now, true)
                    return
                }

                val eew = parseEew(body)
                if (eew == null || eew.magnitude <= 0.0) {
                    notifyStatus(state, "HTTP降级·正常", now, true)
                    return
                }

                // 新鲜度过滤：安静期端点保留的是旧事件，绝不能进入告警链路
                val origin = eew.originMs
                if (origin <= 0L || origin <= now - FRESH_WINDOW_MS) {
                    notifyStatus(state, "HTTP降级·正常", now, true)
                    return
                }

                // 去重：轮询会反复拉到同一份报告，同一 eventId+reportNum 只送一次
                val sig = "${eew.eventId}|${eew.reportNum}"
                synchronized(state) {
                    if (sig == state.lastSignature) {
                        notifyStatus(state, "HTTP降级·接收中", now, true)
                        return
                    }
                    state.lastSignature = sig
                }

                notifyStatus(state, "HTTP降级·接收中", now, true)
                Log.i(TAG, "[${state.sourceId}] HTTP 降级收到: ${eew.hypoCenter} M${eew.magnitude} #${eew.reportNum}")
                onEew(eew, state.sourceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[${state.sourceId}] HTTP 降级轮询失败: ${e.message}")
            notifyStatus(state, "HTTP降级·连接失败", 0L, false)
        }
    }

    private fun notifyStatus(state: FallbackState, note: String, dataMs: Long, alive: Boolean) {
        handler.post {
            if (stopped) return@post
            onStatus(state.sourceId, note, dataMs, alive)
        }
    }
}
