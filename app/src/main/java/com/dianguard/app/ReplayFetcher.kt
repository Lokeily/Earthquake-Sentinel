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
 * 预警回放数据抓取器（测试版专用）。
 *
 * 与 HistoryFetcher 的区别：
 *   - 抓取窗口 90 天（而非 10 天），覆盖近几个月到近一年的历史预警；
 *   - 数据写入 ReplayHistory（而非 QuakeHistory），独立存储互不干扰；
 *   - 数据源：CENC（最近 50 条）+ USGS（90 天窗口）+ EMSC（90 天窗口），
 *     优先使用 CENC 中文目录，USGS/EMSC 作为兜底补充覆盖更早时段。
 */
object ReplayFetcher {

    private const val TAG = "ReplayFetcher"

    /** 中国地震台网速报目录（JSON，最近 50 条） */
    private const val CENC_EQLIST = "https://api.wolfx.jp/cenc_eqlist.json"

    /** USGS FDSN 事件查询（GeoJSON），90 天窗口 */
    private const val USGS_QUERY = "https://earthquake.usgs.gov/fdsnws/event/1/query"

    /** EMSC 欧洲地震中心 FDSN 事件查询 */
    private const val EMSC_QUERY = "https://www.seismicportal.eu/fdsnws/event/1/query"

    /** 抓取窗口：90 天 */
    private const val WINDOW_DAYS = 90L

    /** 抓取上限 */
    private const val LIMIT = 200

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-ReplayFetch").also { it.isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 复用全局共享客户端（自动参与证书固定降级，降级后无需手动重建）
    private val httpClient get() = HttpClient.instance

    private val zhFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    /**
     * 异步抓取回放数据并落盘。
     * @param onProgress 进度回调，参数为"开始/同步中/完成"等状态文本
     * @param callback   完成回调，参数为新增条数
     */
    fun fetchAndRecord(
        onProgress: ((String) -> Unit)? = null,
        callback: (addedCount: Int) -> Unit
    ) {
        executor.execute {
            mainHandler.post { onProgress?.invoke("正在从数据源同步历史地震目录…") }

            val batch = mutableListOf<QuakeRecord>()

            // 主源（v1.4.3）：CENC 官方目录（https://www.ceic.ac.cn/data/data.json）——
            // 权威官方源，覆盖近 8 个月、免鉴权、HTTPS。先取官方数据与 eventId 去重表，
            // 其后再抓 wolfx 镜像时跳过官方已覆盖的条目（官方优先）。
            var officialIds = setOf<String>()
            try {
                val official = CencOfficial.fetchCatalog(365L * 24 * 60 * 60 * 1000L)
                officialIds = official.ids
                batch += official.records
                Log.i(TAG, "CENC 官方目录返回 ${official.records.size} 条（约${official.ids.size}个事件）")
                mainHandler.post { onProgress?.invoke("已同步 CENC 官方目录 ${official.records.size} 条，继续获取其他目录…") }
            } catch (e: Exception) {
                Log.w(TAG, "CENC 官方目录: ${e.message}")
            }

            // 互补源：wolfx CENC 速报目录（跳过官方已覆盖的 eventId）
            try {
                val cencRecords = fetchCencEqlist(officialIds)
                batch += cencRecords
                if (cencRecords.isNotEmpty()) {
                    Log.i(TAG, "CENC 镜像目录返回 ${cencRecords.size} 条")
                }
            } catch (e: Exception) {
                Log.w(TAG, "CENC 镜像目录: ${e.message}")
            }

            // USGS 兜底：覆盖更早时段
            if (batch.size < 50) {
                try {
                    val usgsRecords = fetchUsgsChina()
                    batch += usgsRecords
                    Log.i(TAG, "USGS 目录返回 ${usgsRecords.size} 条")
                    mainHandler.post { onProgress?.invoke("已同步 USGS 目录 ${usgsRecords.size} 条，继续获取 EMSC 数据…") }
                } catch (e: Exception) {
                    Log.w(TAG, "USGS 目录: ${e.message}")
                }
            }

            // EMSC 第三源兜底
            if (batch.size < 30) {
                try {
                    val emscRecords = fetchEmscChina()
                    batch += emscRecords
                    Log.i(TAG, "EMSC 目录返回 ${emscRecords.size} 条")
                } catch (e: Exception) {
                    Log.w(TAG, "EMSC 目录: ${e.message}")
                }
            }

            // 去重：按 key 去重（保留首条）
            val seen = mutableSetOf<String>()
            val deduped = batch.filter { seen.add(it.key) }

            // 实时监听链路攒下的多报序列一并纳入回放存储（365 天窗口）。
            // 目录数据（CENC/USGS/EMSC）只有最终测定值，没有逐报；
            // 只有 App 实时监听亲历的地震才保留了第1报→第2报→…的完整序列，
            // 若不并入 ReplayHistory，7 天后 QuakeHistory 淘汰即永久丢失。
            val liveMultiReport = QuakeHistory.all().filter { it.reports.size > 1 }
            if (liveMultiReport.isNotEmpty()) {
                Log.i(TAG, "将 ${liveMultiReport.size} 条实时多报记录并入回放存储")
            }

            // 批量落盘到 ReplayHistory
            val added = ReplayHistory.recordBatch(deduped + liveMultiReport)
            Log.i(TAG, "回放数据同步完成，新增 $added 条（去重后共 ${deduped.size} 条）")

            // 全部数据源都不可达时明确告知失败，避免"同步完成，共 0 条"的误导
            val statusText = if (deduped.isEmpty() && ReplayHistory.all().isEmpty()) {
                "同步失败：所有数据源均不可达，请检查网络后点右上角 ↻ 重试"
            } else {
                "同步完成，共 ${ReplayHistory.all().size} 条记录"
            }
            mainHandler.post {
                onProgress?.invoke(statusText)
                callback(added)
            }
        }
    }

    // ===================== CENC 中国地震台网速报目录 =====================

    private fun fetchCencEqlist(skipOfficialIds: Set<String> = emptySet()): List<QuakeRecord> {
        val body = httpGet(CENC_EQLIST) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }

        val out = mutableListOf<QuakeRecord>()
        val keys = root.keys().asSequence().filter { it.startsWith("No") }.toList().sorted()
        for (k in keys) {
            val obj = root.optJSONObject(k) ?: continue
            val eventId = obj.optString("EventID", "")
            if (eventId.isBlank()) continue
            // 官方 CENC 目录已覆盖该事件 → 跳过镜像重复条目（官方优先）
            if (eventId in skipOfficialIds) continue
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
            // 使用回放专用 key 前缀，避免与 QuakeHistory 混淆
            val key = "replay_cenc|$eventId"
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

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
                etaSec = if (distKm > 0) AppConfig.estimateSWaveEtaSeconds(distKm, depthKm) else 0.0,
                sourceName = "中国地震台网速报",
                reportNum = 1,
                triggered = false,
                backup = true,
                epiLat = lat,
                epiLon = lon,
                eventId = eventId,
                sourceId = "cenc_catalog"
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

    // ===================== USGS 中国及周边目录（90 天窗口） =====================

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
            // 倒序取最新：升序会在 90 天窗口里拿到最旧的 200 条
            append("&orderby=time-desc")
        }

        val body = httpGet(url) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        val features = root.optJSONArray("features") ?: return emptyList()

        val out = mutableListOf<QuakeRecord>()
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
            if (otMs <= 0L || id.isBlank()) continue

            val key = "replay_usgs|$id"
            val depthKm = coords.optDouble(2, 0.0)
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            val place = GeoUtils.provinceOf(lat, lon)
                ?: props.optString("place", "未知地区")

            out.add(QuakeRecord(
                key = key,
                timeMs = otMs,
                originTime = zhFmt.format(Date(otMs)),
                place = place,
                magnitude = mag,
                depthKm = depthKm,
                intensity = if (AppConfig.hasLocation)
                    "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                else "约%.1f".format(estimateIntensityFromMagnitude(mag)),
                distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm, depthKm),
                sourceName = "USGS 地震目录",
                reportNum = 1,
                triggered = false,
                backup = true,
                epiLat = lat,
                epiLon = lon,
                eventId = id,
                sourceId = "usgs_catalog"
            ))
        }
        return out
    }

    // ===================== EMSC 欧洲地震中心目录（90 天窗口） =====================

    private fun fetchEmscChina(): List<QuakeRecord> {
        val now = System.currentTimeMillis()
        val start = now - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val url = buildString {
            append(EMSC_QUERY)
            append("?format=json&limit=").append(LIMIT)
            append("&orderby=time-desc")
            append("&minlatitude=18&maxlatitude=54")
            append("&minlongitude=73&maxlongitude=135")
            append("&minmag=2.0")
            append("&starttime=").append(isoUtc(start))
        }

        val body = httpGet(url) ?: return emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        val features = root.optJSONArray("features") ?: return emptyList()

        val out = mutableListOf<QuakeRecord>()
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

            val key = "replay_emsc|$id"
            val depthKm = if (coords.length() >= 3) coords.optDouble(2, 0.0) else 0.0
            val mag = props.optDouble("mag", 0.0)
            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            val place = GeoUtils.provinceOf(lat, lon)
                ?: props.optString("flynn_region", "未知地区")

            out.add(QuakeRecord(
                key = key,
                timeMs = otMs,
                originTime = zhFmt.format(Date(otMs)),
                place = place,
                magnitude = mag,
                depthKm = depthKm,
                intensity = if (AppConfig.hasLocation)
                    "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                else "约%.1f".format(estimateIntensityFromMagnitude(mag)),
                distanceKm = distKm,
                etaSec = AppConfig.estimateSWaveEtaSeconds(distKm, depthKm),
                sourceName = "EMSC 地震目录",
                reportNum = 1,
                triggered = false,
                backup = true,
                epiLat = lat,
                epiLon = lon,
                eventId = id,
                sourceId = "emsc_catalog"
            ))
        }
        return out
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
        } catch (e: Exception) {
            // 接入证书固定降级逃生舱：官方轮换证书时固定校验会永久失败，
            // 必须上报计数，达到阈值后自动降级为系统信任，回放同步才能自愈
            HttpClient.reportFailure(java.net.URL(url).host, e)
            Log.w(TAG, "$url -> ${e.message}"); null
        }
    }

    private fun isoUtc(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }
}
