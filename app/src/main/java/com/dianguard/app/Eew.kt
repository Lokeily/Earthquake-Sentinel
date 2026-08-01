package com.dianguard.app

import kotlin.math.*

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
 *   "6-" / "6+" 等同理；无法解析时返回 0.0（不会触发阈值）。
 */
fun parseIntensity(s: String): Double {
    val t = s.trim()
    if (t.isEmpty() || t == "-") return 0.0
    val digits = StringBuilder()
    var delta = 0.0
    for (ch in t) {
        when {
            ch.isDigit() || ch == '.' -> digits.append(ch)
            ch == '-' || ch == '−' || ch == '－' -> delta = -0.3
            ch == '+' || ch == '＋' -> delta = 0.3
        }
    }
    val base = digits.toString().toDoubleOrNull() ?: return 0.0
    return (base + delta).coerceIn(0.0, 12.0)
}

/**
 * 预警等级（按预估烈度，对齐中国大陆地震预警 红/橙/黄/蓝 四级标准）。
 * 红=预估烈度≥7（强破坏），橙≥6（破坏），黄≥4（强有感），蓝≥3（有感），其余不提示。
 */
enum class WarningLevel { RED, ORANGE, YELLOW, BLUE, NONE }

fun warningLevel(intensity: Double): WarningLevel = when {
    intensity >= 7.0 -> WarningLevel.RED
    intensity >= 6.0 -> WarningLevel.ORANGE
    intensity >= 4.0 -> WarningLevel.YELLOW
    intensity >= 3.0 -> WarningLevel.BLUE
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
