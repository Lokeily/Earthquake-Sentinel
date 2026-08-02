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
 * 地震历史记录自动抓取（v1.1.2 重写：中文地名 + 昨天今天）。
 *
 * 数据源优先级：
 * 1. Wolfx CENC 历史目录 — 中国地震台网官方目录，中文地名，M3.0+（首选）
 * 2. USGS FDSN — 全球目录，英文地名→坐标转省份中文兜底
 *
 * 范围：中国境内及周边（15-55N, 70-140E）
 * 时间：48 小时
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    /** Wolfx CENC 历史地震目录（中文地名） */
    private const val CENC_CATALOG = "https://api.wolfx.jp/quake_cenc.json"

    /** Wolfx CENC 最新速报（兜底） */
    private const val CENC_URL = "https://api.wolfx.jp/cenc_eew.json"

    /** 时间过滤：48 小时 */
    private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

    /** USGS FDSN 查询 */
    private val USGS_QUERY: String
        get() {
            val now = java.time.Instant.now().toString()
            val twoDaysAgo = java.time.Instant.now().minusSeconds(48 * 3600).toString()
            return "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson" +
                "&starttime=$twoDaysAgo&endtime=$now" +
                "&minlatitude=15&maxlatitude=55&minlongitude=70&maxlongitude=140" +
                "&minmagnitude=3.0&orderby=time"
        }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        Thread {
            var added = 0
            // 1. CENC 中文目录（首选）
            try { added += fetchCencCatalog() }
            catch (e: Exception) { Log.w(TAG, "CENC 目录失败: ${e.message}") }
            // 2. USGS（补漏，坐标转中文省份）
            try { added += fetchUsgsFdsn() }
            catch (e: Exception) { Log.w(TAG, "USGS 失败: ${e.message}") }
            // 3. CENC 速报兜底
            try { added += fetchCenc() }
            catch (e: Exception) { Log.w(TAG, "CENC 速报失败: ${e.message}") }
            Log.i(TAG, "完成，新增 $added 条")
            Handler(Looper.getMainLooper()).post { callback(added) }
        }.start()
    }

    /** Wolfx CENC 历史目录 — 返回中文地名数组 */
    private fun fetchCencCatalog(): Int {
        val body = httpGet(CENC_CATALOG) ?: return 0
        val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return 0 }
        var added = 0
        val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("Latitude", Double.NaN)
            val lon = obj.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val originTime = obj.optString("OriginTime", obj.optString("O_TIME", ""))
            val otMs = parseOriginTimeMs(originTime)
            if (otMs > 0L && now - otMs > MAX_AGE_MS) continue

            val mag = obj.optDouble("Magnitude", obj.optDouble("M", 0.0))
            val id = obj.optString("ID", obj.optString("EventID", ""))
            val reportNum = obj.optInt("ReportNum", 1)

            val quakeKey = "cenc_cat|${id}|${reportNum}"
            val intensityStr = if (obj.has("MaxIntensity") && obj.optString("MaxIntensity").isNotBlank())
                obj.optString("MaxIntensity")
            else "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = otMs.coerceAtLeast(now),
                originTime = originTime,
                place = obj.optString("HypoCenter", obj.optString("LOCATION_C", "未知地区")),
                magnitude = mag,
                depthKm = obj.optDouble("Depth", obj.optDouble("EPI_DEPTH", 0.0)),
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "中国地震台网", reportNum = reportNum,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    /** USGS FDSN — 坐标转中文省份 */
    private fun fetchUsgsFdsn(): Int {
        val body = httpGet(USGS_QUERY) ?: return 0
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

            val id = f.optString("id", ""); if (id.isBlank()) continue
            val mag = props.optDouble("mag", 0.0)
            val quakeKey = "usgs|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            // 英文地名→中文省份+方位
            val usgsPlace = props.optString("place", "")
            val chinesePlace = if (usgsPlace.isNotBlank() && !usgsPlace.contains(Regex("[\\u4e00-\\u9fff]")))
                coordToChineseProvince(lat, lon) else usgsPlace

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = timeMs,
                originTime = isoFormat.format(Date(timeMs)),
                place = chinesePlace, magnitude = mag, depthKm = depth,
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "USGS (速报)", reportNum = 1,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    /** Wolfx CENC 最新速报（兜底） */
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
            reportNum = obj.optInt("ReportNum", 1), originTime = originTime,
            hypoCenter = obj.optString("HypoCenter", "未知地区"),
            latitude = lat, longitude = lon,
            magnitude = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0)),
            depthKm = obj.optDouble("Depth", 0.0),
            maxIntensity = obj.optString("MaxIntensity", "")
        )
        val distKm = if (AppConfig.hasLocation)
            haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude) else 0.0
        val quakeKey = "cenc|${eew.eventId}|${eew.reportNum}"
        val intensityStr = if (eew.maxIntensity.isNotBlank()) eew.maxIntensity
        else "约${"%.0f".format(estimateIntensityFromMagnitude(eew.magnitude))}"

        recordIfNew(QuakeRecord(
            key = quakeKey, timeMs = otMs.coerceAtLeast(System.currentTimeMillis()),
            originTime = eew.originTime, place = eew.hypoCenter,
            magnitude = eew.magnitude, depthKm = eew.depthKm,
            intensity = intensityStr, distanceKm = distKm,
            etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
            sourceName = "中国地震台网 (速报)", reportNum = eew.reportNum,
            triggered = false, backup = true
        ))
        return 1
    }

    // ===================== 工具 =====================

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
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

    /**
     * 经纬度 → 中国省份/地区中文名。
     * 基于省份近似边界框，精度足够区分到省级。
     * 不覆盖中国全境时返回经纬度格式。
     */
    private fun coordToChineseProvince(lat: Double, lon: Double): String {
        // 省份边界框 (latMin, latMax, lonMin, lonMax)
        data class Rect(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double)
        val provinces = listOf(
            "黑龙江" to Rect(43.0, 54.0, 121.0, 135.0),
            "内蒙古" to Rect(37.0, 53.0, 97.0, 126.0),
            "新疆"   to Rect(34.0, 49.0, 73.0, 96.0),
            "吉林"   to Rect(41.0, 46.0, 122.0, 131.0),
            "辽宁"   to Rect(38.0, 43.0, 119.0, 126.0),
            "北京"   to Rect(39.0, 41.0, 115.0, 118.0),
            "天津"   to Rect(38.5, 40.0, 116.5, 118.0),
            "河北"   to Rect(36.0, 42.0, 113.0, 120.0),
            "山西"   to Rect(34.5, 41.0, 110.0, 115.0),
            "山东"   to Rect(34.0, 38.5, 115.0, 123.0),
            "河南"   to Rect(31.0, 36.5, 110.0, 117.0),
            "陕西"   to Rect(31.5, 39.5, 105.5, 111.5),
            "宁夏"   to Rect(35.0, 39.5, 104.0, 107.5),
            "甘肃"   to Rect(32.5, 43.0, 92.0, 109.0),
            "青海"   to Rect(31.5, 39.0, 89.0, 103.0),
            "西藏"   to Rect(26.5, 36.5, 78.0, 99.0),
            "四川"   to Rect(26.0, 34.5, 97.0, 108.5),
            "重庆"   to Rect(28.0, 32.5, 105.0, 110.5),
            "湖北"   to Rect(29.0, 33.5, 108.0, 116.5),
            "安徽"   to Rect(29.0, 35.0, 114.5, 120.0),
            "江苏"   to Rect(30.5, 35.5, 116.0, 122.0),
            "上海"   to Rect(30.5, 31.5, 120.5, 122.0),
            "浙江"   to Rect(27.0, 31.5, 118.0, 123.0),
            "湖南"   to Rect(24.5, 30.5, 108.5, 114.5),
            "江西"   to Rect(24.0, 30.5, 113.5, 118.5),
            "贵州"   to Rect(24.5, 29.5, 103.5, 110.0),
            "福建"   to Rect(23.5, 28.5, 115.5, 121.0),
            "云南"   to Rect(21.0, 29.5, 97.5, 106.5),
            "广西"   to Rect(20.5, 26.5, 104.0, 112.5),
            "广东"   to Rect(20.0, 25.5, 109.5, 117.5),
            "海南"   to Rect(18.0, 20.5, 108.5, 111.5),
            "台湾"   to Rect(21.5, 25.5, 120.0, 122.5),
            "香港"   to Rect(22.0, 23.0, 113.5, 114.5),
            "澳门"   to Rect(22.0, 22.5, 113.5, 114.0),
            "南海"   to Rect(15.0, 21.0, 108.0, 120.0),
        )
        for ((name, r) in provinces) {
            if (lat in r.latMin..r.latMax && lon in r.lonMin..r.lonMax) return name
        }
        return "%.1f°N %.1f°E".format(lat, lon)
    }
}
