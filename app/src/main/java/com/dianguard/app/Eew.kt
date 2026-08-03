package com.dianguard.app

import kotlin.math.*
import org.json.JSONObject

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
    val maxIntensity: String  // 预估最大烈度（如 "5" / "5-")
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
        Eew(id, eventId, reportNum, originTime, hypo, lat, lon, mag, depth, maxInt)
    } catch (_: Exception) {
        null
    }
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
 * 预警等级（按震级，对齐中国地震预警 红/橙/黄/蓝 四级标准）。
 * 红=6级以上（强破坏），橙=5-6级（破坏），黄=4-5级（强有感），蓝=4级以下（有感），其余不提示。
 */
enum class WarningLevel { RED, ORANGE, YELLOW, BLUE, NONE }

fun warningLevel(magnitude: Double): WarningLevel = when {
    magnitude >= 6.0 -> WarningLevel.RED
    magnitude >= 5.0 -> WarningLevel.ORANGE
    magnitude >= 4.0 -> WarningLevel.YELLOW
    magnitude >= 0.1 -> WarningLevel.BLUE
    else -> WarningLevel.NONE
}

/**
 * 按【用户所在地预估烈度】划分预警等级，对齐中国地震预警 红/橙/黄/蓝 四级标准：
 *   红 ≥ 8°、橙 6-8°、黄 4-6°、蓝 2-4°、其余不提示。
 * 与 warningLevel（按震级）不同，本函数使等级、配色、破坏描述、语音分级四者
 * 与用户实际感受一致。
 */
fun warningLevelByIntensity(siteIntensity: Double): WarningLevel = when {
    siteIntensity >= 8.0 -> WarningLevel.RED
    siteIntensity >= 6.0 -> WarningLevel.ORANGE
    siteIntensity >= 4.0 -> WarningLevel.YELLOW
    siteIntensity >= 2.0 -> WarningLevel.BLUE
    else -> WarningLevel.NONE
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
 * 采用中国西部地区地震烈度衰减关系（汪素云等, 2000）的短轴式，
 * 短轴相对保守，用于预警判定可降低漏报风险：
 *     I = 2.941 + 1.363·M − 1.494·ln(R + 7)
 * 其中 R 取震源距（含深度修正），单位 km。
 *
 * @param magnitude      震级
 * @param epicenterDistKm 震中距（用户 ↔ 震中的地表距离）
 * @param depthKm        震源深度
 * @return 用户所在地预估烈度，范围 [0, 12]
 */
fun estimateSiteIntensity(magnitude: Double, epicenterDistKm: Double, depthKm: Double): Double {
    if (magnitude <= 0) return 0.0
    val d = if (depthKm > 0) depthKm else 10.0
    val hypoDist = sqrt(epicenterDistKm * epicenterDistKm + d * d)
    val i = 2.941 + 1.363 * magnitude - 1.494 * ln(hypoDist + 7.0)
    return i.coerceIn(0.0, 12.0)
}
