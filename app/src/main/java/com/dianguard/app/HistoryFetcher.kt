package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 地震历史记录自动抓取（v1.1.x 新增，修复问题4）。
 *
 * 在用户打开预警历史记录页时，从中国地震台网（CENC，经 Wolfx HTTP 聚合）
 * 和美国地质调查局（USGS）抓取近期已发生的地震，写入本地历史记录。
 *
 * 设计取舍：
 * - **只抓取已发生的地震（震后速报）**，不是实时预警。USGS feed 延迟通常
 *   5-15 分钟，CENC 速报延迟 3-10 分钟。抓取历史不会触发全屏告警。
 * - **去重**：通过 QuakeRecord.key 与本地已有记录匹配，不会重复写入。
 * - **范围过滤**：仅记录距参考位置 2000 km 内、12 小时内的地震。
 * - **异步执行**：所有网络 I/O 在后台线程完成，不阻塞 UI。
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    /** Wolfx CENC HTTP 端点（返回最新一条 CENC 地震速报） */
    private const val CENC_URL = "https://api.wolfx.jp/cenc_eew.json"

    /** USGS 近一小时 M2.5+ 摘要（CDN 静态分发，体积小） */
    private const val USGS_URL =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_hour.geojson"

    /** 最大距离：2000 km */
    private const val MAX_DISTANCE_KM = 2000.0

    /** 只抓取最近 12 小时内的事件 */
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /** ISO 时间解析器（Asia/Shanghai 时区） */
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    /**
     * 从 CENC 和 USGS 抓取近期地震记录并写入本地历史。
     *
     * @param callback 完成后的回调（运行在主线程），参数为新增记录条数
     */
    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        Thread {
            var added = 0
            try {
                added += fetchCenc()
            } catch (e: Exception) {
                Log.w(TAG, "CENC 历史抓取失败: ${e.message}")
            }
            try {
                added += fetchUsgs()
            } catch (e: Exception) {
                Log.w(TAG, "USGS 历史抓取失败: ${e.message}")
            }
            Log.i(TAG, "历史抓取完成，新增 $added 条记录")
            // 回调必须在主线程执行（调用方会操作 UI）
            Handler(Looper.getMainLooper()).post { callback(added) }
        }.start()
    }

    /** 抓取 CENC（Wolfx HTTP）最新一条速报 */
    private fun fetchCenc(): Int {
        val body = httpGet(CENC_URL) ?: return 0
        val obj = JSONObject(body)
        if (!obj.has("Latitude") || !obj.has("Longitude")) return 0

        val lat = obj.optDouble("Latitude", Double.NaN)
        val lon = obj.optDouble("Longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return 0

        val originTime = obj.optString("OriginTime", "")
        val otMs = parseOriginTimeMs(originTime)
        if (otMs > 0L && System.currentTimeMillis() - otMs > MAX_AGE_MS) return 0

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

        val distKm = if (AppConfig.hasLocation) {
            haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        } else 0.0
        if (AppConfig.hasLocation && distKm > MAX_DISTANCE_KM) return 0

        val quakeKey = "cenc|${eew.eventId}|${eew.reportNum}"
        val intensityStr = if (eew.maxIntensity.isNotBlank()) eew.maxIntensity
        else "约${"%.0f".format(estimateIntensityFromMagnitude(eew.magnitude))}"

        recordIfNew(
            QuakeRecord(
                key = quakeKey, timeMs = System.currentTimeMillis(), originTime = eew.originTime,
                place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "中国地震台网 (历史速报)", reportNum = eew.reportNum,
                triggered = false, backup = true
            )
        )
        return 1
    }

    /** 抓取 USGS 近一小时地震摘要 */
    private fun fetchUsgs(): Int {
        val body = httpGet(USGS_URL) ?: return 0
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return 0
        var added = 0
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

            val timeMs = props.optLong("time", 0L)
            if (timeMs <= 0L || now - timeMs > MAX_AGE_MS) continue

            val id = f.optString("id", "")
            if (id.isBlank()) continue

            val distKm = if (AppConfig.hasLocation) {
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon)
            } else 0.0
            if (AppConfig.hasLocation && distKm > MAX_DISTANCE_KM) continue

            val mag = props.optDouble("mag", 0.0)
            val quakeKey = "usgs|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            recordIfNew(
                QuakeRecord(
                    key = quakeKey, timeMs = System.currentTimeMillis(),
                    originTime = isoFormat.format(Date(timeMs)),
                    place = props.optString("place", "未知地区"), magnitude = mag,
                    depthKm = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0,
                    intensity = intensityStr, distanceKm = distKm,
                    etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                    sourceName = "USGS 全球地震目录 (速报)", reportNum = 1,
                    triggered = false, backup = true
                )
            )
            added++
        }
        return added
    }

    // ===================== 工具方法 =====================

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .build()
        HttpClient.instance.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "$url 返回 ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    /**
     * 仅在记录不存在时写入，避免重复。
     * 检查逻辑：先读取现有记录，若 key 已存在则跳过。
     */
    private fun recordIfNew(record: QuakeRecord) {
        val existing = QuakeHistory.all()
        if (existing.any { it.key == record.key }) {
            Log.d(TAG, "跳过重复记录: ${record.key}")
            return
        }
        QuakeHistory.record(record)
    }

    /** 解析发震时间字符串为毫秒时间戳（修复负时区偏移截断问题） */
    private fun parseOriginTimeMs(timeStr: String): Long {
        return try {
            val cleaned = timeStr.trim()
                .replace("T", " ")
                .replace("Z", "")
                .replace(Regex("\\.\\d+"), "")
                .let { s ->
                    val tzIdx = s.indexOfFirst { it == '+' || it == '-' }
                    if (tzIdx > 10) s.substring(0, tzIdx).trim() else s
                }
                .take(19)
            if (cleaned.length < 16) return 0L
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
