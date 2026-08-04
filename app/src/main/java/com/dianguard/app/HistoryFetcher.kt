package com.dianguard.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 地震历史记录抓取。
 *
 * 数据源说明（v1.2.0+）：
 * 主源 `api.wolfx.jp/cenc_eqlist.json` 是中国地震台网速报目录，返回最近 50 条地震，
 * 包含中文地名、震级、深度、发震时刻，在中国网络环境下可达性最好。
 * 备用源 `earthquake.usgs.gov` 在部分网络下可能受限，仅作为主源失败/无数据时兜底。
 */
object HistoryFetcher {

    private const val TAG = "HistoryFetcher"

    /** 中国地震台网速报目录（JSON，最近 50 条） */
    private const val CENC_EQLIST = "https://api.wolfx.jp/cenc_eqlist.json"

    /** USGS FDSN event 查询（GeoJSON），按时间倒序返回中国及周边近期地震 */
    private const val USGS_QUERY = "https://earthquake.usgs.gov/fdsnws/event/1/query"

    /** BigDataCloud 反向地理编码（免费、无需密钥、中文 localityLanguage=zh） */
    private const val GEO_API = "https://api.bigdatacloud.net/data/reverse-geocode-client"

    /** EMSC 欧洲地震中心 FDSN 事件查询（独立于 wolfx/USGS 的第三源，覆盖中国及周边） */
    private const val EMSC_QUERY = "https://www.seismicportal.eu/fdsnws/event/1/query"

    /** USGS 抓取窗口：最近 10 天（落盘后由 QuakeHistory 按 7 天保留） */
    private const val WINDOW_DAYS = 10L

    /** USGS 抓取上限 */
    private const val LIMIT = 80

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-HistoryFetch").also { it.isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 专用短超时客户端（与全局客户端共用同一套证书固定规则，防中间人注入虚假历史目录） */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .certificatePinner(HttpClient.buildCertificatePinner())
        .build()

    private val zhFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    /** 会话内坐标→中文地名缓存 */
    private val geoCache = LinkedHashMap<String, String>(64)

    fun fetchAndRecord(callback: (addedCount: Int) -> Unit) {
        executor.execute {
            val batch = mutableListOf<QuakeRecord>()
            try { batch += fetchCencEqlist() }
            catch (e: Exception) { Log.w(TAG, "CENC目录: ${e.message}") }
            if (batch.isEmpty()) {
                // 主源无数据时尝试 USGS 兜底
                try { batch += fetchUsgsChina() }
                catch (e: Exception) { Log.w(TAG, "USGS目录: ${e.message}") }
            }
            if (batch.isEmpty()) {
                // 主源与 USGS 均无数据时，再尝试 EMSC（独立于 wolfx/USGS 的第三源）
                try { batch += fetchEmscChina() }
                catch (e: Exception) { Log.w(TAG, "EMSC目录: ${e.message}") }
            }
            // 批量落盘：仅一次全量加载 + 一次排序裁剪 + 一次写入，避免逐条 record 的 N 次 SP 全量 I/O
            val added = QuakeHistory.recordBatch(batch)
            Log.i(TAG, "完成，新增 $added 条")
            mainHandler.post { callback(added) }
        }
    }

    // ===================== 中国地震台网速报目录 =====================

    private fun fetchCencEqlist(): List<QuakeRecord> {
        val body = httpGet(CENC_EQLIST) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }

        val out = mutableListOf<QuakeRecord>()
        val keys = root.keys().asSequence().filter { it.startsWith("No") }.toList().sorted()
        for (k in keys) {
            val obj = root.optJSONObject(k) ?: continue
            val eventId = obj.optString("EventID", "")
            if (eventId.isBlank()) continue
            val magStr = obj.optString("magnitude", "")
            val mag = magStr.toDoubleOrNull() ?: continue
            if (mag <= 0) continue
            val depthStr = obj.optString("depth", "0")
            val depthKm = depthStr.toDoubleOrNull() ?: 0.0
            val lat = obj.optString("latitude", "").toDoubleOrNull() ?: continue
            val lon = obj.optString("longitude", "").toDoubleOrNull() ?: continue
            val timeStr = obj.optString("time", "")
            if (timeStr.isBlank()) continue
            val place = obj.optString("location", obj.optString("placeName", "未知地区"))
            val maxIntensity = obj.optString("intensity", "")

            val otMs = parseChinaTime(timeStr)
            val key = "cenc_eq|$eventId"
            // 自学习校准（v1.4.0）：CENC 官方目录即"最终震级"真值，
            // 回填给融合校准样本库，驱动 EewFusion 源偏差学习。
            FusionCalibration.fillGroundTruth(otMs, mag, lat, lon)
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            // 历史目录无用户所在地烈度，优先用源报 maxIntensity，有位置时估算本地烈度
            val intensity = when {
                AppConfig.hasLocation -> "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                maxIntensity.isNotBlank() -> "约$maxIntensity"
                else -> "-"
            }

            out.add(QuakeRecord(
                key = key,
                timeMs = otMs,
                originTime = timeStr,
                place = place,
                magnitude = mag,
                depthKm = depthKm,
                intensity = intensity,
                distanceKm = distKm,
                etaSec = if (distKm > 0) AppConfig.estimateSWaveEtaSeconds(distKm) else 0.0,
                sourceName = "中国地震台网速报",
                reportNum = 1,
                triggered = false,
                backup = true
            ))
        }
        return out
    }

    private fun parseChinaTime(timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            sdf.parse(timeStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    // ===================== USGS 中国及周边目录（兜底） =====================

    private fun fetchUsgsChina(): List<QuakeRecord> {
        val now = System.currentTimeMillis()
        val start = now - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val url = buildString {
            append(USGS_QUERY)
            append("?format=geojson")
            append("&starttime=").append(isoUtc(start))
            append("&endtime=").append(isoUtc(now))
            append("&minlatitude=18&maxlatitude=54")
            append("&minlongitude=73&maxlongitude=135")
            append("&minmagnitude=2.0")
            append("&limit=").append(LIMIT)
            append("&orderby=time")
        }

        val body = httpGet(url) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        val features = root.optJSONArray("features") ?: return emptyList()

        val out = mutableListOf<QuakeRecord>()
        var geoBudget = 20
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (GeoUtils.provinceOf(lat, lon) == null) continue

            val mag = props.optDouble("mag", 0.0)
            val id = props.optString("id", "")
            val otMs = props.optLong("time", 0L)
            if (otMs <= 0L) continue
            if (id.isBlank()) continue

            val key = "usgs|$id"
            val depthKm = coords.optDouble(2, 0.0)
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            val place = if (geoBudget > 0) {
                geoBudget--
                reverseGeocodeZh(lat, lon) ?: GeoUtils.provinceOf(lat, lon)
                    ?: props.optString("place", "未知地区")
            } else {
                GeoUtils.provinceOf(lat, lon) ?: props.optString("place", "未知地区")
            }

            out.add(QuakeRecord(
                key = key,
                timeMs = otMs,
                originTime = zhFmt.format(Date(otMs)),
                place = place,
                magnitude = mag,
                depthKm = if (depthKm > 0) depthKm else 0.0,
                intensity = if (AppConfig.hasLocation)
                    "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                else "约%.1f".format(estimateIntensityFromMagnitude(mag)),
                distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "USGS 地震目录",
                reportNum = 1,
                triggered = false,
                backup = true
            ))
        }
        return out
    }

    // ===================== EMSC 欧洲地震中心目录（独立第三源） =====================
    // EMSC(seismicportal.eu) 与 wolfx/USGS 互不包含，作为历史抓取的最后兜底，
    // 进一步降低对单一第三方站点的依赖。time 为 Unix 秒；coordinates=[lon,lat,depth_km]。

    private fun fetchEmscChina(): List<QuakeRecord> {
        val now = System.currentTimeMillis()
        val start = now - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val url = buildString {
            append(EMSC_QUERY)
            append("?format=json&limit=").append(LIMIT)
            append("&orderby=time")
            append("&minlatitude=18&maxlatitude=54")
            append("&minlongitude=73&maxlongitude=135")
            append("&minmag=2.0")
            append("&starttime=").append(isoUtc(start))
        }

        val body = httpGet(url) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        val features = root.optJSONArray("features") ?: return emptyList()

        val out = mutableListOf<QuakeRecord>()
        var geoBudget = 20
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (GeoUtils.provinceOf(lat, lon) == null) continue

            val timeSec = props.optLong("time", 0L)
            if (timeSec <= 0L) continue
            val otMs = timeSec * 1000L
            val id = props.optString("unid", "")
            if (id.isBlank()) continue

            val key = "emsc|$id"
            val depthKm = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0
            val mag = props.optDouble("mag", 0.0)
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            val place = if (geoBudget > 0) {
                geoBudget--
                reverseGeocodeZh(lat, lon) ?: GeoUtils.provinceOf(lat, lon)
                    ?: props.optString("flynn_region", "未知地区")
            } else {
                GeoUtils.provinceOf(lat, lon) ?: props.optString("flynn_region", "未知地区")
            }

            out.add(QuakeRecord(
                key = key,
                timeMs = otMs,
                originTime = zhFmt.format(Date(otMs)),
                place = place,
                magnitude = mag,
                depthKm = if (depthKm > 0) depthKm else 0.0,
                intensity = if (AppConfig.hasLocation)
                    "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                else "约%.1f".format(estimateIntensityFromMagnitude(mag)),
                distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm),
                sourceName = "EMSC 地震目录",
                reportNum = 1,
                triggered = false,
                backup = true
            ))
        }
        return out
    }

    // ===================== 反向地理编码（中文地名） =====================

    private fun reverseGeocodeZh(lat: Double, lon: Double): String? {
        val cacheKey = "%.2f,%.2f".format(lat, lon)
        synchronized(geoCache) {
            geoCache[cacheKey]?.let { return it }
        }

        var result: String? = null
        try {
            val url = "$GEO_API?latitude=$lat&longitude=$lon&localityLanguage=zh"
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string() ?: "")
                    val prov = obj.optString("principalSubdivision", "")
                    val city = obj.optString("city", "")
                    val locality = obj.optString("locality", "")
                    val sb = StringBuilder()
                    if (prov.isNotBlank()) sb.append(prov)
                    if (city.isNotBlank() && city != prov) sb.append(city)
                    if (locality.isNotBlank() && locality != city) sb.append(locality)
                    if (sb.isNotEmpty()) result = sb.toString()
                }
            }
        } catch (_: Exception) { }

        if (result == null) result = GeoUtils.provinceOf(lat, lon)

        synchronized(geoCache) {
            geoCache[cacheKey] = result ?: "未知地区"
            if (geoCache.size > 200) geoCache.clear()
        }
        // 繁体 → 简体（BigDataCloud 对港澳台地名返回繁体，需统一为简体中文）
        return result?.let { ZhConvert.toSimplified(it) }
    }

    // ===================== 工具 =====================

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json").build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "$url -> ${resp.code}"); return null }
                resp.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "$url -> ${e.message}"); null }
    }

    private fun isoUtc(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }
}
