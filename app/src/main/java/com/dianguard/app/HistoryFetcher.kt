package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 地震历史记录多源自动抓取（v1.1.2 整合版）。
 *
 * 数据源（按优先级）：
 * 1. 中国地震台网-CENC 官方速报 (ceic.ac.cn) — 最权威中文数据，M3.0+
 * 2. Wolfx CENC 聚合目录 — 中国地震台网数据聚合，中文地名
 * 3. 美国地质调查局-USGS FDSN — 全球权威目录，英文→坐标转省份
 * 4. 德国地学中心-GFZ FDSN — 欧洲权威目录，交叉验证
 * 5. Wolfx CENC EEW 单条速报 — 兜底
 *
 * 范围：中国及周边（15-55N, 70-140E）
 * 时间：48 小时（昨天+今天）
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    // === 数据源端点 ===
    private const val CENC_SPEED = "http://www.ceic.ac.cn/ajax/speedsearch"
    private const val CENC_CATALOG = "https://api.wolfx.jp/quake_cenc.json"
    private const val CENC_EEW = "https://api.wolfx.jp/cenc_eew.json"

    /** USGS FDSN */
    private val USGS_URL: String
        get() = fdsnUrl("https://earthquake.usgs.gov/fdsnws/event/1/query")

    /** GFZ 德国地学中心（权威交叉验证） */
    private val GFZ_URL: String
        get() = fdsnUrl("https://geofon.gfz-potsdam.de/fdsnws/event/1/query")

    private fun fdsnUrl(base: String): String {
        val now = java.time.Instant.now().toString()
        val ago = java.time.Instant.now().minusSeconds(48 * 3600).toString()
        return "$base?format=geojson&starttime=$ago&endtime=$now" +
            "&minlatitude=15&maxlatitude=55&minlongitude=70&maxlongitude=140" +
            "&minmagnitude=3.0&orderby=time"
    }

    private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    // ===================== 公开入口 =====================

    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        Thread {
            var added = 0
            // 源1: CENC 官方速报（最权威）
            try { added += fetchCencSpeedsearch() }
            catch (e: Exception) { Log.w(TAG, "CENC速报: ${e.message}") }
            // 源2: Wolfx CENC 聚合
            try { added += fetchCencCatalog() }
            catch (e: Exception) { Log.w(TAG, "CENC聚合: ${e.message}") }
            // 源3: USGS FDSN
            try { added += fetchFdsn(USGS_URL, "USGS") }
            catch (e: Exception) { Log.w(TAG, "USGS: ${e.message}") }
            // 源4: GFZ FDSN（交叉验证）
            try { added += fetchFdsn(GFZ_URL, "GFZ") }
            catch (e: Exception) { Log.w(TAG, "GFZ: ${e.message}") }
            // 源5: CENC EEW 单条兜底
            try { added += fetchCencEew() }
            catch (e: Exception) { Log.w(TAG, "CENC-EEW: ${e.message}") }

            Log.i(TAG, "多源抓取完成：新增 $added 条记录")
            Handler(Looper.getMainLooper()).post { callback(added) }
        }.start()
    }

    // ===================== 源1: CENC 官方速报 =====================

    private fun fetchCencSpeedsearch(): Int {
        // CENC 速报 API：POST 带时间参数，返回 JSON 数组
        val body = httpPost(CENC_SPEED) ?: return 0
        val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return 0 }
        var added = 0
        val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("EPI_LAT", Double.NaN)
            val lon = obj.optDouble("EPI_LON", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val oTime = obj.optString("O_TIME", "")
            val otMs = parseOriginTimeMs(oTime)
            if (otMs > 0L && now - otMs > MAX_AGE_MS) continue

            val mag = obj.optDouble("M", 0.0)
            val id = obj.optString("CATA_ID", obj.optString("EVENT_ID", ""))
            val quakeKey = "cenc_sp|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = otMs.coerceAtLeast(now),
                originTime = oTime,
                place = obj.optString("LOCATION_C", "未知地区"),
                magnitude = mag,
                depthKm = obj.optDouble("EPI_DEPTH", 0.0),
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "中国地震台网", reportNum = 1,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    // ===================== 源2: Wolfx CENC 聚合 =====================

    private fun fetchCencCatalog(): Int {
        val body = httpGet(CENC_CATALOG) ?: return 0
        val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return 0 }
        var added = 0; val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("Latitude", Double.NaN)
            val lon = obj.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val oTime = obj.optString("OriginTime", obj.optString("O_TIME", ""))
            val otMs = parseOriginTimeMs(oTime)
            if (otMs > 0L && now - otMs > MAX_AGE_MS) continue

            val mag = obj.optDouble("Magnitude", obj.optDouble("M", 0.0))
            val id = obj.optString("ID", obj.optString("EventID", ""))
            val quakeKey = "cenc_cat|$id"
            val intensityStr = if (obj.optString("MaxIntensity", "").isNotBlank())
                obj.optString("MaxIntensity")
            else "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"

            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = otMs.coerceAtLeast(now),
                originTime = oTime,
                place = obj.optString("HypoCenter", obj.optString("LOCATION_C", "未知地区")),
                magnitude = mag,
                depthKm = obj.optDouble("Depth", obj.optDouble("EPI_DEPTH", 0.0)),
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "中国地震台网", reportNum = obj.optInt("ReportNum", 1),
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    // ===================== 源3+4: USGS / GFZ FDSN =====================

    private fun fetchFdsn(url: String, source: String): Int {
        val body = httpGet(url) ?: return 0
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return 0
        var added = 0; val now = System.currentTimeMillis()

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

            val quakeKey = "${source.lowercase()}|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            // 英文地名→坐标转中文省份
            val rawPlace = props.optString("place", "")
            val chinesePlace = if (rawPlace.isNotBlank() && !rawPlace.contains(Regex("[\\u4e00-\\u9fff]")))
                coordToChineseProvince(lat, lon)
            else rawPlace

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = timeMs,
                originTime = isoFmt.format(Date(timeMs)),
                place = chinesePlace, magnitude = mag, depthKm = depth,
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "$source (速报)", reportNum = 1,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    // ===================== 源5: CENC EEW 单条速报兜底 =====================

    private fun fetchCencEew(): Int {
        val body = httpGet(CENC_EEW) ?: return 0
        val obj = JSONObject(body)
        if (!obj.has("Latitude") || !obj.has("Longitude")) return 0
        val lat = obj.optDouble("Latitude", Double.NaN)
        val lon = obj.optDouble("Longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return 0

        val oTime = obj.optString("OriginTime", "")
        val otMs = parseOriginTimeMs(oTime)
        if (otMs > 0L && System.currentTimeMillis() - otMs > MAX_AGE_MS) return 0

        val mag = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0))
        val eId = obj.optString("EventID", obj.optString("ID", ""))
        val rpt = obj.optInt("ReportNum", 1)
        val quakeKey = "cenc_eew|$eId|$rpt"
        val intensityStr = if (obj.optString("MaxIntensity", "").isNotBlank())
            obj.optString("MaxIntensity")
        else "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"
        val distKm = if (AppConfig.hasLocation)
            haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

        recordIfNew(QuakeRecord(
            key = quakeKey, timeMs = otMs.coerceAtLeast(System.currentTimeMillis()),
            originTime = oTime,
            place = obj.optString("HypoCenter", "未知地区"),
            magnitude = mag, depthKm = obj.optDouble("Depth", 0.0),
            intensity = intensityStr, distanceKm = distKm,
            etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
            sourceName = "中国地震台网 (实时)", reportNum = rpt,
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

    /** CENC 速报 API 使用 POST */
    private fun httpPost(url: String): String? {
        val body = okhttp3.RequestBody.create("application/x-www-form-urlencoded".toMediaType(), "")
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")
            .post(body).build()
        HttpClient.instance.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "$url POST -> ${resp.code}"); return null }
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

    /** 经纬度 → 中文省份 */
    private fun coordToChineseProvince(lat: Double, lon: Double): String {
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
