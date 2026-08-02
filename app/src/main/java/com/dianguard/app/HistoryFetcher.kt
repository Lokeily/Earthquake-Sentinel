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
 * 地震历史记录抓取 — 仅使用中国地震台网官方数据。
 *
 * 数据源（全中文）：
 * 1. CENC 官方速报 (ceic.ac.cn) — 最权威，中文省市区县名
 * 2. Wolfx CENC 聚合目录 — 中国地震台网数据聚合
 * 3. Wolfx CENC EEW 实时速报 — 最新一条兜底
 *
 * 范围：中国境内及周边
 * 时间：48 小时（昨天+今天）
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    private const val CENC_SPEED = "https://www.ceic.ac.cn/ajax/speedsearch"
    private const val CENC_CATALOG = "https://api.wolfx.jp/quake_cenc.json"
    private const val CENC_EEW = "https://api.wolfx.jp/cenc_eew.json"

    private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-HistoryFetch").also { it.isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val millisRegex = Regex("\\.\\d+")

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        executor.execute {
            var added = 0
            try { added += fetchCencSpeedsearch() }
            catch (e: Exception) { Log.w(TAG, "CENC速报: ${e.message}") }
            try { added += fetchCencCatalog() }
            catch (e: Exception) { Log.w(TAG, "CENC聚合: ${e.message}") }
            try { added += fetchCencEew() }
            catch (e: Exception) { Log.w(TAG, "CENC-EEW: ${e.message}") }
            Log.i(TAG, "完成，新增 $added 条")
            mainHandler.post { callback(added) }
        }
    }

    // ===================== CENC 官方速报 =====================

    private fun fetchCencSpeedsearch(): Int {
        val body = httpPost(CENC_SPEED) ?: return 0
        val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return 0 }
        var added = 0; val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("EPI_LAT", Double.NaN)
            val lon = obj.optDouble("EPI_LON", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val oTime = obj.optString("O_TIME", "")
            val otMs = parseOriginTimeMs(oTime)
            if (otMs > 0L && now - otMs > MAX_AGE_MS) continue
            val mag = obj.optDouble("M", 0.0)
            val id = obj.optString("CATA_ID", "")
            val quakeKey = "cenc_sp|$id"
            val intensityStr = "约${"%.0f".format(estimateIntensityFromMagnitude(mag))}"
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            recordIfNew(QuakeRecord(
                key = quakeKey, timeMs = otMs.coerceAtLeast(now),
                originTime = oTime,
                place = obj.optString("LOCATION_C", "未知地区"),
                magnitude = mag, depthKm = obj.optDouble("EPI_DEPTH", 0.0),
                intensity = intensityStr, distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "中国地震台网", reportNum = 1,
                triggered = false, backup = true
            ))
            added++
        }
        return added
    }

    // ===================== Wolfx CENC 聚合 =====================

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

    // ===================== CENC EEW 实时兜底 =====================

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

    private fun httpPost(url: String): String? {
        val body = okhttp3.RequestBody.create("application/x-www-form-urlencoded".toMediaType(), "")
        val req = Request.Builder().url(url)
            .header("Accept", "application/json").header("User-Agent", "Mozilla/5.0")
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
                .replace(millisRegex, "")
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
}
