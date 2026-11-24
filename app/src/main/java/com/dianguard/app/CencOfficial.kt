package com.dianguard.app

import android.util.Log
import okhttp3.Request
import org.json.JSONArray

/**
 * 中国地震台网（CENC）官方地震目录抓取器（v1.4.3）。
 *
 * ## 来源
 *   https://www.ceic.ac.cn/data/data.json  —— 中国地震台网官网「地震目录」SPA 的数据接口，
 *   免鉴权、HTTPS 有效证书、中国大陆可达（本机实测 HTTP 200 + TLS 校验通过）。
 *   返回官方地震目录数组（id/time/latitude/longitude/depth/magnitude/location），
 *   实测约 795 条、覆盖近 8 个月、震级 1.7~7.9。
 *
 * ## 定位
 *   官方权威目录（速报，地震发生后约 1~5 分钟内入库），**非秒级 EEW**。
 *   因此用于：① 回放历史（365 天 / 500 条）官方数据；② 预警历史页（7 天）权威确认。
 *   不进入全屏倒计时告警链路（S 波早已通过，与备用探测源同策略）。
 *
 * ## 与 wolfx 镜像的关系
 *   同一场地震的 eventId 在 wolfx 镜像与官方源中一致（如 CC.20260828004208.3）。
 *   本类作为**首要官方源**；调用方用返回的 ids 去重后，跳过 wolfx cenc_eqlist 中的
 *   重复条目，从而用官方数据替代镜像数据，权威性更高。
 */
object CencOfficial {

    private const val TAG = "CencOfficial"
    private const val URL = "https://www.ceic.ac.cn/data/data.json"

    /** 单次抓取的完整结果：官方记录（已按 keepMs 裁剪）+ 全部官方 eventId（用于 wolfx 去重） */
    data class OfficialCatalog(
        val records: List<QuakeRecord>,
        val ids: Set<String>
    )

    private val httpClient get() = HttpClient.instance

    /**
     * 单次抓取官方地震目录（一次 190KB HTTP 请求同时产出记录与 ID 去重表）。
     * @param keepMs 保留窗口（毫秒）：QuakeHistory 7 天、ReplayHistory 365 天
     */
    fun fetchCatalog(keepMs: Long): OfficialCatalog {
        val body = httpGet() ?: return OfficialCatalog(emptyList(), emptySet())
        val arr = try { JSONArray(body) } catch (_: Exception) {
            Log.w(TAG, "CENC 官方目录 JSON 解析失败")
            return OfficialCatalog(emptyList(), emptySet())
        }

        val cutoff = System.currentTimeMillis() - keepMs
        val out = mutableListOf<QuakeRecord>()
        val ids = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            ids.add(id)

            val timeStr = o.optString("time", "")
            val otMs = parseOriginTimeMs(timeStr)
            if (otMs <= 0L || otMs < cutoff) continue

            val lat = o.optDouble("latitude", Double.NaN)
            val lon = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val mag = o.optDouble("magnitude", 0.0)
            if (mag <= 0.0) continue
            val depthKm = o.optDouble("depth", 0.0)
            val place = ZhConvert.toSimplified(o.optString("location", "未知地区"))

            val distKm = if (AppConfig.hasLocation)
                haversineKm(AppConfig.homeLat, AppConfig.homeLon, lat, lon) else 0.0

            out.add(QuakeRecord(
                key = "cenc_official|$id",
                timeMs = otMs,
                originTime = timeStr,
                place = place,
                magnitude = mag,
                depthKm = depthKm,
                intensity = if (AppConfig.hasLocation)
                    "约%.1f".format(estimateSiteIntensity(mag, distKm, depthKm))
                else "约%.1f".format(estimateIntensityFromMagnitude(mag)),
                distanceKm = distKm,
                etaSec = if (distKm > 0) AppConfig.estimateSWaveEtaSeconds(distKm, depthKm) else 0.0,
                sourceName = "中国地震台网(官方)",
                reportNum = 1,
                triggered = false,
                backup = true,
                epiLat = lat,
                epiLon = lon,
                eventId = id,
                sourceId = "cenc_official"
            ))
        }
        return OfficialCatalog(out.sortedByDescending { it.timeMs }, ids)
    }

    private fun httpGet(): String? {
        return try {
            val req = Request.Builder().url(URL)
                .header("Accept", "application/json")
                .header("Referer", "https://www.ceic.ac.cn/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CENC 官方目录返回 ${resp.code}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CENC 官方目录抓取失败: ${e.message}")
            null
        }
    }
}