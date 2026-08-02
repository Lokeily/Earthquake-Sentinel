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
 * 地震历史记录自动抓取（v1.1.2 重写：昨天+今天中国大陆地震）。
 *
 * 数据源：
 * 1. USGS FDSN GeoJSON — 全球地震目录，M4.5+ 覆盖中国大陆，延迟 5-15 分钟
 * 2. Wolfx CENC HTTP — 中国地震台网最新一条速报（兜底）
 *
 * 范围过滤：中国境内及周边（纬度 15-55N，经度 70-140E）
 * 时间范围：过去 48 小时（昨天+今天）
 * 去重：通过 QuakeRecord.key 与本地记录匹配
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    /** USGS FDSN 查询：过去 2 天 M4.5+，中国及周边 */
    private val USGS_QUERY: String
        get() {
            val now = java.time.Instant.now().toString() // 2024-01-15T08:30:00Z
            val twoDaysAgo = java.time.Instant.now().minusSeconds(48 * 3600).toString()
            return "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson" +
                "&starttime=$twoDaysAgo&endtime=$now" +
                "&minlatitude=15&maxlatitude=55&minlongitude=70&maxlongitude=140" +
                "&minmagnitude=3.0&orderby=time"
        }

    /** Wolfx CENC 最新速报（兜底） */
    private const val CENC_URL = "https://api.wolfx.jp/cenc_eew.json"

    /** 时间过滤：48 小时 */
    private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        Thread {
            var added = 0
            try {
                added += fetchUsgsFdsn()
            } catch (e: Exception) {
                Log.w(TAG, "USGS 查询失败: ${e.message}")
            }
            try {
                added += fetchCenc()
            } catch (e: Exception) {
                Log.w(TAG, "CENC 抓取失败: ${e.message}")
            }
            Log.i(TAG, "历史抓取完成，新增 $added 条记录")
            Handler(Looper.getMainLooper()).post { callback(added) }
        }.start()
    }

    /** USGS FDSN 查询：中国及周边 48h M3.0+ */
    private fun fetchUsgsFdsn(): Int {
        val url = USGS_QUERY
        Log.i(TAG, "USGS query: $url")
        val body = httpGet(url) ?: return 0
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

            val depth = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0
            val timeMs = props.optLong("time", 0L)
            if (timeMs <= 0L || now - timeMs > MAX_AGE_MS) continue

            val id = f.optString("id", "")
            if (id.isBlank()) continue

            val mag = props.optDouble("mag", 0.0)
            val quakeKey = "usgs|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            val distKm = if (AppConfig.hasLocation) {
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon)
            } else 0.0

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = System.currentTimeMillis(),
                originTime = isoFormat.format(Date(timeMs)),
                place = props.optString("place", formatPlace(lat, lon)), magnitude = mag,
                depthKm = depth, intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "USGS (震后速报)", reportNum = 1,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    /** Wolfx CENC 最新速报（兜底：可能被 USGS 已覆盖） */
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
            latitude = lat, longitude = lon,
            magnitude = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0)),
            depthKm = obj.optDouble("Depth", 0.0),
            maxIntensity = obj.optString("MaxIntensity", "")
        )

        val distKm = if (AppConfig.hasLocation) {
            haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        } else 0.0

        val quakeKey = "cenc|${eew.eventId}|${eew.reportNum}"
        val intensityStr = if (eew.maxIntensity.isNotBlank()) eew.maxIntensity
        else "约${"%.0f".format(estimateIntensityFromMagnitude(eew.magnitude))}"

        recordIfNew(QuakeRecord(
            key = quakeKey, timeMs = System.currentTimeMillis(), originTime = eew.originTime,
            place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
            intensity = intensityStr, distanceKm = distKm,
            etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
            sourceName = "中国地震台网 (速报)", reportNum = eew.reportNum,
            triggered = false, backup = true
        ))
        return 1
    }

    // ===================== 工具 =====================

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json").build()
        HttpClient.instance.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "$url -> ${resp.code}"); return null }
            return resp.body?.string()
        }
    }

    private fun recordIfNew(record: QuakeRecord) {
        val existing = QuakeHistory.all()
        if (existing.any { it.key == record.key }) return
        QuakeHistory.record(record)
    }

    private fun parseOriginTimeMs(timeStr: String): Long {
        return try {
            val cleaned = timeStr.trim().replace("T", " ").replace("Z", "")
                .replace(Regex("\\.\\d+"), "")
                .let { s ->
                    val tzIdx = s.indexOfFirst { it == '+' || it == '-' }
                    if (tzIdx > 10) s.substring(0, tzIdx).trim() else s
                }.take(19)
            if (cleaned.length < 16) return 0L
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** 根据经纬度生成简单中文地名（USGS 不提供中文地名时的兜底） */
    private fun formatPlace(lat: Double, lon: Double): String {
        // USGS 通常给英文地名，这里仅作兜底，正常情况 props.optString("place") 已有值
        return "%.2fN %.2fE".format(lat, lon)
    }
}
