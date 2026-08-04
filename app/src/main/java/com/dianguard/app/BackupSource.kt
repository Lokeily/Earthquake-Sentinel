package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

/**
 * 备用探测源（修复 P0 #2：数据源单点依赖）。
 *
 * ## 要解决的问题
 * v1.0.16 的 3 路预警源（中国地震台网 / 四川局 / 重庆局）虽然是三个不同机构发布的，
 * 但**全部经由 wolfx.jp 这一个第三方站点聚合转发**。也就是说 Wolfx 一旦宕机、被墙、
 * 或域名解析失败，3 路 WebSocket 会同时断开——冗余是假的，实际仍是单点。
 *
 * ## 本类的定位
 * 一条**独立于 Wolfx 的兜底探测链路**，只在主链路完全失效时启用：
 *
 *  1. **USGS FDSN**（美国地质调查局，真正的非 Wolfx 独立源）
 *     全球权威地震目录，对中国境内 M4+ 地震有稳定覆盖，CDN 分发、无需 Key。
 *  2. **Wolfx HTTP JSON**（同源不同协议）
 *     很多故障只发生在 WebSocket 长连接层（中间设备掐长连接、代理不支持 Upgrade），
 *     此时普通 HTTPS 请求往往仍然可用，作为第二道兜底成本极低。
 *
 * ## 关键设计取舍
 * - **只在全部 WebSocket 断连并超过宽限期后激活**，主链路一恢复立刻停掉，
 *   平时零流量、零耗电——这对插电常驻的老手机很重要。
 * - **30 秒轮询**，而不是更激进的频率：备用源本就是降级方案，够用即可。
 * - **命中后不触发全屏倒计时告警**。USGS 属于「震后速报」，从地震发生到进入目录
 *   通常已过数分钟，S 波早已通过，此时再弹一个倒计时只会制造恐慌且毫无价值。
 *   因此备用源命中只做两件事：写入历史记录 + 发一条通知栏提示，
 *   并在文案上明确标注「备用源速报」，不与真实预警混淆。
 * - **自带长周期去重表**（2 小时）。USGS 的同一条地震会在接下来的一个多小时里
 *   反复出现在 feed 中，若只依赖服务层 10 分钟的去重窗，会被重复提示多次。
 */
class BackupSource(
    /** 命中新地震时回调（Eew 数据, 源名称）——运行在工作线程 */
    private val onEvent: (Eew, String) -> Unit,
    /** 每轮巡检后回调一句人类可读的状态，用于主页“备用探测源”那一行 */
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "BackupSource"

        /** 轮询间隔：30 秒 */
        const val POLL_INTERVAL_MS = 30_000L

        /**
         * USGS 近一小时 M2.5+ 全球地震摘要。
         * 选这个 feed 而不是带查询参数的 FDSN query 接口，是因为它由 CDN 静态分发、
         * 每分钟更新、体积只有几 KB，30 秒轮询对流量和电量几乎无感。
         */
        private const val USGS_FEED =
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_hour.geojson"

        /** Wolfx 的 HTTP 版 CENC 预警接口（与 WebSocket 同源，仅用于绕开长连接层故障） */
        private const val WOLFX_HTTP = "https://api.wolfx.jp/cenc_eew.json"

        /** EMSC 欧洲地震中心 FDSN 事件查询（独立于 wolfx/USGS 的第三源） */
        private const val EMSC_QUERY = "https://www.seismicportal.eu/fdsnws/event/1/query"

        /** 单次拉取上限 */
        private const val LIMIT = 80

        /** 只关心距参考位置这个范围内的地震，避免全球小震刷屏 */
        private const val MAX_DISTANCE_KM = 2000.0

        /** 只关心发震时间在此范围内的地震（更早的属于历史，不再提示） */
        private const val MAX_AGE_MS = 60 * 60 * 1000L

        /** 备用源自身的去重窗口：2 小时，覆盖 USGS feed 中同一事件的重复出现 */
        private const val SEEN_TTL_MS = 2 * 60 * 60 * 1000L
    }

    @Volatile
    var active: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    /** 共享线程池，替代每次 new Thread（30s轮询，避免线程堆积） */
    private val pollExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-BackupPoll").also { it.isDaemon = true }
    }

    /** 已上报过的事件（key -> 首次上报时间），防止同一地震被反复提示 */
    private val seen = LinkedHashMap<String, Long>()
    private val seenLock = Any()

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    /** 启动轮询（幂等）。立即执行一次，之后每 30 秒一轮。 */
    fun start() {
        if (active) return
        active = true
        Log.w(TAG, "主链路全部断开，启动备用探测源（USGS + Wolfx HTTP + EMSC 兜底，${POLL_INTERVAL_MS / 1000}s 轮询）")
        onStatus("巡检中")
        pollRunnable = object : Runnable {
            override fun run() {
                pollAsync()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.post(pollRunnable!!)
    }

    /** 停止轮询（幂等）。主链路恢复后调用。 */
    fun stop() {
        if (!active) return
        active = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        Log.i(TAG, "主链路已恢复，备用探测源转入待命")
        onStatus("待命中")
    }

    // ===================== 轮询实现 =====================

    private fun pollAsync() {
        pollExecutor.execute {
            var hit = 0
            var okCount = 0
            // 先查 Wolfx HTTP（若只是 WS 层故障，这里能拿到真正的实时预警）
            try {
                if (pollWolfxHttp()) hit++
                okCount++
            } catch (e: Exception) {
                Log.w(TAG, "Wolfx HTTP 兜底失败: ${e.message}")
            }
            // 再查 USGS（真正独立于 Wolfx 的链路）
            try {
                hit += pollUsgs()
                okCount++
            } catch (e: Exception) {
                Log.w(TAG, "USGS 兜底失败: ${e.message}")
            }
            // 最后查 EMSC（欧洲地震中心，与 wolfx/USGS 互不包含的第三独立源）
            try {
                hit += pollEmsc()
                okCount++
            } catch (e: Exception) {
                Log.w(TAG, "EMSC 兜底失败: ${e.message}")
            }
            val status = when {
                okCount == 0 -> "备用源也不可达"
                hit > 0 -> "巡检中 · 刚发现 $hit 条"
                else -> "巡检中 · 暂无异常"
            }
            onStatus(status)
        }
    }

    /** 拉取 Wolfx HTTP 版 CENC 预警；返回是否命中新事件 */
    private fun pollWolfxHttp(): Boolean {
        val body = httpGet(WOLFX_HTTP) ?: return false
        val obj = JSONObject(body)
        if (!obj.has("Latitude") || !obj.has("Longitude")) return false
        val lat = obj.optDouble("Latitude", Double.NaN)
        val lon = obj.optDouble("Longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return false

        // 时间过滤：拒绝超过 MAX_AGE_MS 的旧数据（与 USGS 分支保持一致）
        val originTime = obj.optString("OriginTime", "")
        if (originTime.isNotBlank()) {
            val otMs = parseOriginTimeMs(originTime)
            if (otMs > 0L && System.currentTimeMillis() - otMs > MAX_AGE_MS) {
                Log.i(TAG, "Wolfx HTTP 返回的事件已超过 ${MAX_AGE_MS / 60_000} 分钟，忽略: $originTime")
                return false
            }
        }

        val eew = Eew(
            id = obj.optString("ID", ""),
            eventId = obj.optString("EventID", obj.optString("ID", "")),
            reportNum = obj.optInt("ReportNum", 1),
            originTime = originTime,
            hypoCenter = obj.optString("HypoCenter", "未知地区"),
            latitude = lat,
            longitude = lon,
            magnitude = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0)),
            depthKm = obj.optDouble("Depth", 0.0),
            maxIntensity = obj.optString("MaxIntensity", "")
        )
        val key = "wolfx|${eew.eventId}|${eew.reportNum}"
        if (!markSeen(key)) return false
        if (!withinRange(eew)) return false
        onEvent(eew, "中国地震台网 (HTTP 兜底)")
        return true
    }

    /**
     * 解析发震时间字符串为毫秒时间戳。
     * 支持格式：ISO 8601（"2024-01-15T08:30:00+08:00"/"2024-01-15 08:30:00"/带毫秒等）。
     * v1.1.1 修复：substringBefore("-") 会错误截断负时区偏移如 -05:00。
     * 改为 regex 去毫秒 + 仅在 tzIdx>10（日期部分之后）时才截断时区后缀。
     */
    private fun parseOriginTimeMs(timeStr: String): Long {
        return try {
            val cleaned = timeStr.trim()
                .replace("T", " ")
                .replace("Z", "")
                // 去除毫秒部分 ".123"
                .replace(Regex("\\.\\d+"), "")
                // 去除时区后缀 "+08:00" / "-05:00"，仅在日期部分之后截断
                .let { s ->
                    val tzIdx = s.indexOfFirst { it == '+' || it == '-' }
                    if (tzIdx > 10) s.substring(0, tzIdx).trim() else s
                }
                .take(19) // "yyyy-MM-dd HH:mm:ss"
            if (cleaned.length < 16) return 0L
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) {
            Log.w(TAG, "无法解析发震时间: $timeStr")
            0L
        }
    }

    /** 拉取 EMSC 欧洲地震中心目录（独立于 wolfx/USGS 的第三源）；返回命中的新事件数 */
    private fun pollEmsc(): Int {
        val start = System.currentTimeMillis() - MAX_AGE_MS
        val url = StringBuilder(EMSC_QUERY).apply {
            append("?format=json&limit=").append(LIMIT)
            append("&orderby=time")
            append("&minlatitude=18&maxlatitude=54")
            append("&minlongitude=73&maxlongitude=135")
            append("&minmag=2.5")
            append("&starttime=").append(isoUtc(start))
        }.toString()
        val body = httpGet(url) ?: return 0
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return 0
        var hit = 0
        val now = System.currentTimeMillis()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            // EMSC 的 time 可能为 Unix 秒（数字）或 ISO 字符串，两种都兼容解析
            val timeSec = props.optLong("time", 0L)
            val otMs = if (timeSec > 0L) {
                timeSec * 1000L
            } else {
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(props.optString("time", ""))?.time ?: 0L
                } catch (_: Exception) { 0L }
            }
            if (otMs <= 0L) continue
            if (now - otMs > MAX_AGE_MS) continue
            val id = props.optString("unid", "")
            if (id.isBlank()) continue
            val depth = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0
            val eew = Eew(
                id = id,
                eventId = id,
                reportNum = 1,
                originTime = isoFormat.format(Date(otMs)),
                hypoCenter = props.optString("flynn_region", "未知地区"),
                latitude = lat,
                longitude = lon,
                magnitude = props.optDouble("mag", 0.0),
                depthKm = if (depth > 0) depth else 0.0,
                maxIntensity = "" // EMSC 不给烈度，由震级兜底估算
            )
            if (!withinRange(eew)) continue
            if (!markSeen("emsc|$id")) continue
            onEvent(eew, "EMSC 欧洲地震中心目录")
            hit++
        }
        return hit
    }

    /** 拉取 USGS 近一小时地震摘要；返回命中的新事件数 */
    private fun pollUsgs(): Int {
        val body = httpGet(USGS_FEED) ?: return 0
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return 0
        var hit = 0
        val now = System.currentTimeMillis()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            val depth = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0
            if (lat.isNaN() || lon.isNaN()) continue

            val timeMs = props.optLong("time", 0L)
            if (timeMs <= 0L || now - timeMs > MAX_AGE_MS) continue

            val id = f.optString("id", "")
            if (id.isBlank()) continue

            val eew = Eew(
                id = id,
                eventId = id,
                reportNum = 1,
                originTime = isoFormat.format(Date(timeMs)),
                hypoCenter = props.optString("place", "未知地区"),
                latitude = lat,
                longitude = lon,
                magnitude = props.optDouble("mag", 0.0),
                depthKm = depth,
                maxIntensity = "" // USGS 不给烈度，由震级兜底估算
            )
            if (!withinRange(eew)) continue
            if (!markSeen("usgs|$id")) continue
            onEvent(eew, "USGS 全球地震目录")
            hit++
        }
        return hit
    }

    // ===================== 工具 =====================

    private fun isoUtc(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .build()
        // 复用全局共享 OkHttpClient（连接池 / DNS 缓存与主链路共用）
        HttpClient.instance.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "$url 返回 ${resp.code}")
                return null
            }
            val text = resp.body?.string()
            return if (text.isNullOrBlank()) null else text
        }
    }

    /** 距参考位置过远的地震直接忽略，避免全球小震刷屏 */
    private fun withinRange(eew: Eew): Boolean {
        val d = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        return d <= MAX_DISTANCE_KM
    }

    /**
     * 标记事件已上报；返回 true 表示这是新事件（此前未上报过）。
     * 顺便清理超过 TTL 的旧条目，避免长期运行时表无限增长。
     */
    private fun markSeen(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(seenLock) {
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > SEEN_TTL_MS) it.remove()
            }
            if (seen.containsKey(key)) return false
            seen[key] = now
            return true
        }
    }
}
