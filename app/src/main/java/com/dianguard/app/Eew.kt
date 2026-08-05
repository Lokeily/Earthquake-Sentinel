package com.dianguard.app

import kotlin.math.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 解析后的震前预警（EEW）信息。
 * 字段对齐 Wolfx CENC EEW 镜像返回结构，并对字符串/数字做了容错。
 */
data class Eew(
    val id: String,
    val eventId: String,
    val reportNum: Int,
    val originTime: String,
    val hypoCenter: String,   // 震中地名
    val latitude: Double,
    val longitude: Double,
    val magnitude: Double,
    val depthKm: Double,
    val maxIntensity: String, // 预估最大烈度（如 "5" / "5-")
    val originMs: Long = 0L   // 发震时刻 epoch 毫秒；0 表示未知（解析失败）。用于“仅推送开启监听后发生的地震”过滤。
)

/**
 * 两点间球面距离（Haversine，单位 km）。
 */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * 把烈度字符串解析为可比较的数值。
 *
 * 支持的形态（CENC / JMA 烈度标注常见写法）：
 *   "3" -> 3.0, "5" -> 5.0, "7" -> 7.0
 *   "5-" -> 4.7（略低于 5）, "5+" -> 5.3（略高于 5）
 *   "6-" / "6+" 等同理；罗马数字 "Ⅲ"/"Ⅴ"/"Ⅷ" 亦支持；
 *   首字符为负号（如 "-5"）视为非法 → 0.0（不触发阈值）；无法解析时返回 0.0。
 */
/** 罗马数字烈度（JMA / 部分标注使用 Ⅰ-Ⅷ）到数值的映射 */
private val ROMAN_VALUES = mapOf(
    'Ⅰ' to 1, 'Ⅱ' to 2, 'Ⅲ' to 3, 'Ⅳ' to 4,
    'Ⅴ' to 5, 'Ⅵ' to 6, 'Ⅶ' to 7, 'Ⅷ' to 8
)

fun parseIntensity(s: String): Double {
    val t = s.trim()
    if (t.isEmpty() || t == "-") return 0.0
    // 首字符为负号 → 负数烈度非法，视为无（不触发阈值）
    val first = t.first()
    if (first == '-' || first == '−' || first == '－') return 0.0
    // 罗马数字形态（如 Ⅲ / Ⅴ / Ⅷ）
    if (t.any { it in ROMAN_VALUES }) {
        val v = t.sumOf { ROMAN_VALUES[it] ?: 0 }
        if (v > 0) return v.toDouble().coerceIn(0.0, 12.0)
    }
    val digits = StringBuilder()
    var delta = 0.0
    var seenDigit = false
    for (ch in t) {
        when {
            ch.isDigit() || ch == '.' -> { digits.append(ch); seenDigit = true }
            // 仅当已出现数字时，后缀 -/＋ 表示“略低于/略高于”（如 5- → 4.7）
            (ch == '-' || ch == '−' || ch == '－') && seenDigit -> delta = -0.3
            (ch == '+' || ch == '＋') && seenDigit -> delta = 0.3
        }
    }
    val base = digits.toString().toDoubleOrNull() ?: return 0.0
    return (base + delta).coerceIn(0.0, 12.0)
}

/**
 * 解析原始 EEW JSON 报文为 [Eew]。
 * 容错：缺经纬度 / 经纬度越界 / Magunitude 拼写兼容 / 缺字段 / 非法 JSON 均返回 null。
 * 抽为顶层函数，便于单元测试直接调用（原实现为 EewAlertManager 成员方法）。
 */
fun parseEew(raw: String): Eew? {
    return try {
        val obj = JSONObject(raw)
        if (!obj.has("Latitude") || !obj.has("Longitude")) return null
        val lat = obj.optDouble("Latitude", Double.NaN)
        val lon = obj.optDouble("Longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null

        val mag = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0))
        val depth = obj.optDouble("Depth", 0.0)
        val maxInt = obj.optString("MaxIntensity", "")
        val hypo = obj.optString("HypoCenter", "未知地区")
        val id = obj.optString("ID", "")
        val eventId = obj.optString("EventID", id)
        val reportNum = obj.optInt("ReportNum", 1)
        val originTime = obj.optString("OriginTime", "")
        val originMs = parseOriginTimeMs(originTime)
        Eew(id, eventId, reportNum, originTime, hypo, lat, lon, mag, depth, maxInt, originMs)
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析第三方聚合源（FAN Studio / BeeCLD·2v8 等）的【秒级预警】报文为 [Eew]。
 *
 * 这些源与 Wolfx 字段命名不同（多为驼峰），且用信封包裹：
 *   FAN Studio: {"type":"cenc_eew","data":{ latitude, longitude, magnitude, depth,
 *                epicenter, startAt(毫秒), updates(第N报), eventId, isFinal }}
 *   BeeCLD/2v8 : {"Data":{ magnitude, latitude, longitude, depth, placeName,
 *                shockTime, ... },"md5":...,"source":...}
 *
 * 策略：先定位真正的数据对象（data / Data / 顶层），再用多别名回退抽取字段；
 * 仅当具备合法经纬度 + 震级>0 才视为有效预警帧——心跳/速报帧因缺字段被自然过滤。
 * 发震时间优先按数值（毫秒；个别源用秒）转 UTC+8 字符串，否则原样保留。
 * 容错：任何异常返回 null，由 handleRaw 回退或忽略，绝不抛错中断链路。
 */
fun parseExternalEew(raw: String): Eew? {
    return try {
        val root = JSONObject(raw)
        val data: JSONObject = when {
            root.has("data") && root.get("data") is JSONObject -> root.getJSONObject("data")
            root.has("Data") && root.get("Data") is JSONObject -> root.getJSONObject("Data")
            else -> root
        }
        val lat = data.optDouble("latitude", data.optDouble("Latitude", Double.NaN))
        val lon = data.optDouble("longitude", data.optDouble("Longitude", Double.NaN))
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        val mag = data.optDouble(
            "magnitude",
            data.optDouble("Magnitude", data.optDouble("Magunitude", 0.0))
        )
        if (mag <= 0) return null // 心跳 / 非预警帧

        val depth = data.optDouble("depth", data.optDouble("Depth", 0.0))
        val hypo = data.optString(
            "epicenter",
            data.optString(
                "placeName",
                data.optString(
                    "Place",
                    data.optString("hypoCenter", data.optString("location", "未知地区"))
                )
            )
        )
        val eventId = data.optString(
            "eventId",
            data.optString("EventID", data.optString("id", ""))
        )
        val reportNum = data.optInt(
            "updates",
            data.optInt("reportNum", data.optInt("ReportNum", 1))
        )
        val maxInt = data.optString("maxIntensity", data.optString("MaxIntensity", ""))

        val timeVal = data.opt("startAt")
            ?: data.opt("shockTime")
            ?: data.opt("OriginTime")
            ?: data.opt("updateTime")
            ?: data.opt("time")
        val originTime = when (timeVal) {
            is Number -> formatUtc8(timeVal.toLong())
            is String -> timeVal.toString().ifBlank { eventId }
            else -> eventId
        }
        val originMs = when (timeVal) {
            is Number -> if (timeVal.toLong() > 1_000_000_000_000L) timeVal.toLong() else timeVal.toLong() * 1000L
            is String -> parseOriginTimeMs(timeVal.toString())
            else -> 0L
        }
        val id = data.optString("id", eventId)
        Eew(id, eventId, reportNum, originTime, hypo, lat, lon, mag, depth, maxInt, originMs)
    } catch (_: Exception) {
        null
    }
}

/** epoch 毫秒/秒 → "yyyy-MM-dd HH:mm:ss"（UTC+8，中国发震时间展示约定） */
private fun formatUtc8(value: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("GMT+8")
    val millis = if (value > 1_000_000_000_000L) value else value * 1000L
    return fmt.format(millis)
}

/**
 * 把发震时刻文本解析为 epoch 毫秒（统一收口，供各解析器填充 Eew.originMs）。
 * 支持：
 *  - 纯数字 → 视为 epoch（>1e12 毫秒，否则秒）；
 *  - "yyyy-MM-dd HH:mm:ss"（北京时，Wolfx CENC 主流格式）；
 *  - "yyyy-MM-dd'T'HH:mm:ss"（含尾部 'Z' 时按北京时近似，海外源极少用）。
 * 解析失败返回 0（未知），调用方据此走“不依赖发震时刻”的保守路径。
 */
fun parseOriginTimeMs(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0L
    if (t.all { it.isDigit() }) {
        val v = t.toLongOrNull() ?: return 0L
        return if (v > 1_000_000_000_000L) v else v * 1000L
    }
    // "yyyy-MM-dd HH:mm:ss"（Asia/Shanghai）
    try { ORIGIN_FMT_SH.parse(t)?.time?.let { return it } } catch (_: Exception) { }
    // "yyyy-MM-dd'T'HH:mm:ss" / 尾部 'Z'
    val iso = if (t.endsWith("Z", ignoreCase = true)) t.removeSuffix("Z") else t
    try { ORIGIN_FMT_ISO.parse(iso)?.time?.let { return it } } catch (_: Exception) { }
    return 0L
}

private val ORIGIN_FMT_SH = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}
private val ORIGIN_FMT_ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}

/**
 * 生成跨源去重用的事件 Key：经纬度/震级量化到较低精度 + 发震时刻，
 * 使同源不同报文的微小浮点差异被归并为同一事件，避免重复告警。
 */
fun makeQuakeKey(e: Eew): String {
    val lat = (e.latitude * 1000).toLong()
    val lon = (e.longitude * 1000).toLong()
    val mag = (e.magnitude * 10).toLong()
    val ot = e.originTime.ifBlank { e.eventId }
    return "$ot|$lat|$lon|$mag"
}

/**
 * 预警等级（按【用户所在地预估烈度】划分，中国地震局官方标准）：
 *   Ⅰ级 红 ≥ 7°（灾害性预警）
 *   Ⅱ级 橙 5–6°（灾害性预警）
 *   Ⅲ级 黄 3–4°（告知性预警）
 *   Ⅳ级 蓝 < 3°（告知性预警）
 * NONE 仅用于“无有效烈度数据”的占位（如历史记录缺失烈度时），不参与正常分级。
 */
enum class WarningLevel { RED, ORANGE, YELLOW, BLUE, NONE }

/**
 * 按【震级】划分预警等级，用于**历史记录列表**展示地震本身的能量规模
 * （对齐王暾团队"地震预警"App 与官方震级分类标准）：
 *   红 ≥ 6.0 级（强震，破坏性强）
 *   橙 5.0 – 5.9 级（中强震偏强，可造成破坏）
 *   黄 3.0 – 4.9 级（有感地震）
 *   蓝 < 3.0 级（弱震，一般不易察觉）
 * 一次地震只有一个震级，因此列表颜色代表"这场地震本身有多强"；
 * 与 warningLevelByIntensity（按用户所在地预估烈度，用于倒计时告警弹窗）是两套独立标准。
 */
fun warningLevel(magnitude: Double): WarningLevel = when {
    magnitude >= 6.0 -> WarningLevel.RED
    magnitude >= 5.0 -> WarningLevel.ORANGE
    magnitude >= 3.0 -> WarningLevel.YELLOW
    magnitude > 0.0 -> WarningLevel.BLUE
    else -> WarningLevel.NONE
}

/**
 * 按【用户所在地预估烈度】划分预警等级，用于**倒计时告警弹窗**，对齐中国地震局及
 * 省级地震局官方标准（烈度 = 地震波传到用户所在位置的影响程度）：
 *   Ⅰ级 红 ≥ 7°（灾害性预警）
 *   Ⅱ级 橙 5–6°（灾害性预警）
 *   Ⅲ级 黄 3–4°（告知性预警）
 *   Ⅳ级 蓝 < 3°（告知性预警）
 * 注：官方同时规定，通常仅当预估震级 > 4 级或预估震中烈度 > 5 度时才对外发布预警；
 * 本函数只负责把已计算出的本地预估烈度映射为四级，触发门槛由调用方（EewAlertManager）控制。
 */
fun warningLevelByIntensity(siteIntensity: Double): WarningLevel = when {
    siteIntensity >= 7.0 -> WarningLevel.RED
    siteIntensity >= 5.0 -> WarningLevel.ORANGE
    siteIntensity >= 3.0 -> WarningLevel.YELLOW
    else -> WarningLevel.BLUE
}

/** 等级对应的展示文字（用于告警页徽标） */
fun WarningLevel.label(): String = when (this) {
    WarningLevel.RED -> "红色预警"
    WarningLevel.ORANGE -> "橙色预警"
    WarningLevel.YELLOW -> "黄色预警"
    WarningLevel.BLUE -> "蓝色预警"
    WarningLevel.NONE -> ""
}

/**
 * 等级对应的地震类别描述（用于告警页"描述地震"，替代原冗余的固定避险提示）。
 * 按用户所在地预估烈度对应的官方灾害类别命名：
 *   红 ≥7°  → 强破坏性地震（灾害性）
 *   橙 5-6° → 破坏性地震（灾害性）
 *   黄 3-4° → 强有感
 *   蓝 <3°  → 有感地震（告知性）
 */
fun WarningLevel.category(): String = when (this) {
    WarningLevel.RED -> "强破坏性地震"
    WarningLevel.ORANGE -> "破坏性地震"
    WarningLevel.YELLOW -> "强有感地震"
    WarningLevel.BLUE -> "有感地震"
    WarningLevel.NONE -> ""
}

/** 等级对应的预警配色资源 id（供 AlertActivity、HistoryFragment 等统一使用） */
fun WarningLevel.colorRes(): Int = when (this) {
    WarningLevel.RED -> R.color.level_red
    WarningLevel.ORANGE -> R.color.level_orange
    WarningLevel.YELLOW -> R.color.level_yellow
    WarningLevel.BLUE -> R.color.level_blue
    WarningLevel.NONE -> R.color.ios_label_secondary
}

/**
 * 震级兜底烈度估算：部分数据源（如福建局）不返回 MaxIntensity 字段，
 * 此时用震级近似“近震中烈度”，仅用于触发阈值判断与等级着色，并非精确烈度。
 * 关系取经验近似：近震中烈度 ≈ 震级。
 */
fun estimateIntensityFromMagnitude(magnitude: Double): Double {
    if (magnitude <= 0) return 0.0
    return magnitude
}

/**
 * 估算【用户所在地】的地震烈度，供告警判定与等级着色使用。
 *
 * v1.3.0 升级为分区模型（GB 18306-2015 + 雷建成等2007 区域细化）：
 * 根据震中坐标自动选择青藏/新疆/东部强震/中强地震/川滇西南/四川盆地的最优短轴衰减系数，
 * 云南深源地震区使用郁曙君1993特殊公式。无坐标时回退汪素云等2000西部短轴。
 *
 * @param magnitude       震级
 * @param epicenterDistKm 震中距（用户 ↔ 震中的地表距离）
 * @param depthKm         震源深度
 * @return 用户所在地预估烈度，范围 [0, 12]
 */
fun estimateSiteIntensity(magnitude: Double, epicenterDistKm: Double, depthKm: Double): Double {
    if (magnitude <= 0) return 0.0
    val d = if (depthKm > 0) depthKm else 10.0
    val hypoDist = sqrt(epicenterDistKm * epicenterDistKm + d * d)
    // 无坐标时回退汪素云等2000
    val i = 2.941 + 1.363 * magnitude - 1.494 * ln(hypoDist + 7.0)
    return i.coerceIn(0.0, 12.0)
}

/** 分区模型版本：传入震中坐标将自动选择最优衰减公式 */
fun estimateSiteIntensity(magnitude: Double, epicenterDistKm: Double, depthKm: Double,
                          epiLat: Double, epiLon: Double): Double {
    if (magnitude <= 0) return 0.0
    return GeoUtils.estimateSiteIntensity(magnitude, epicenterDistKm, depthKm, epiLat, epiLon)
}

/**
 * 解析 Project Podris WebSocket 的 EEW 报文。
 * {"event_type":"EEW","magnitude":6.8,"location":[lat,lon],"depth":10,...}
 */
fun parsePodrisEew(raw: String): Eew? {
    return try {
        val root = JSONObject(raw)
        if (root.optString("event_type", "") != "EEW") return null
        val loc = root.optJSONArray("location") ?: return null
        if (loc.length() < 2) return null
        val lat = loc.optDouble(0, Double.NaN)
        val lon = loc.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        val mag = root.optDouble("magnitude", 0.0)
        if (mag <= 0) return null
        Eew(
            id = root.optString("event_id", ""),
            eventId = root.optString("event_id", ""),
            reportNum = root.optInt("report_num", 1),
            originTime = root.optString("time", ""),
            hypoCenter = root.optString("region", "未知地区"),
            latitude = lat, longitude = lon,
            magnitude = mag,
            depthKm = root.optInt("depth", 0).toDouble(),
            maxIntensity = root.optDouble("intensity", 0.0).let { if (it > 0) "%.1f".format(it) else "" },
            originMs = parseOriginTimeMs(root.optString("time", ""))
        )
    } catch (_: Exception) { null }
}

/**
 * 解析 ICL（成都高新减灾研究所）官方 EEW HTTP 接口报文。
 * {"eventId":...,"updates":2,"latitude":28.5,"longitude":104.6,"depth":5,
 *  "epicenter":"四川宜宾","startAt":1785694905000,"magnitude":5.1,"epiIntensity":7}
 * startAt 为发震时刻（epoch 毫秒），无鉴权。
 */
fun parseIclEew(obj: JSONObject): Eew? {
    return try {
        val eventId = obj.optString("eventId", "")
        if (eventId.isBlank()) return null
        val lat = obj.optDouble("latitude", Double.NaN)
        val lon = obj.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        val mag = obj.optDouble("magnitude", 0.0)
        if (mag <= 0) return null
        val startAt = obj.optLong("startAt", 0L)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val originTime = if (startAt > 0L) fmt.format(java.util.Date(startAt)) else ""
        Eew(
            id = eventId,
            eventId = eventId,
            reportNum = obj.optInt("updates", 1),
            originTime = originTime,
            hypoCenter = obj.optString("epicenter", "未知地区"),
            latitude = lat, longitude = lon,
            magnitude = mag,
            depthKm = obj.optDouble("depth", 0.0),
            maxIntensity = obj.optDouble("epiIntensity", 0.0).let { if (it > 0) "%.1f".format(it) else "" },
            originMs = if (startAt > 0L) startAt else parseOriginTimeMs(originTime)
        )
    } catch (_: Exception) { null }
}

