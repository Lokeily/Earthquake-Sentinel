package com.dianguard.app

import kotlin.math.*

/**
 * 地理位置公共工具（省级边界表、分区烈度衰减公式等）。
 *
 * 烈度模型参考文献：
 * - 肖亮, 俞言祥. 新一代地震区划图地震动参数衰减关系的建立与特点分析. 2011.
 *   （GB 18306-2015 四分区短轴衰减系数）
 * - 雷建成, 高孟潭, 俞言祥. 四川及邻区地震动衰减关系. 地震学报, 2007, 29(5).
 *   （川滇西南地区短轴衰减系数）
 */

/** 分区烈度衰减系数 */
data class AttenuationCoeff(val A: Double, val B: Double, val C: Double, val R0: Double, val sigma: Double, val regionName: String)

object GeoUtils {

    // ==================== GB 18306-2015 四分区短轴衰减系数 ====================
    // 模型：I = A + B*M + C*lg(R + R0)，σ 为标准差。
    // 来源：肖亮、俞言祥，2011

    private val COEFF_EAST_STRONG = AttenuationCoeff(3.6588, 1.3626, -3.5406, 13.0, 0.5826, "东部强震区")
    private val COEFF_MODERATE   = AttenuationCoeff(3.9440, 1.0710, -2.8450,  7.0, 0.5200, "中强地震区")
    private val COEFF_XINJIANG   = AttenuationCoeff(3.6113, 1.4347, -3.8477, 13.0, 0.5924, "新疆区")
    private val COEFF_QINGZANG   = AttenuationCoeff(3.3682, 1.2746, -3.3119,  9.0, 0.6636, "青藏区")

    /** 汪素云等 2000 西部短轴（兜底） */
    private val COEFF_FALLBACK = AttenuationCoeff(2.941, 1.363, -1.494, 7.0, 0.6500, "中国西部（兜底）")

    // ==================== 局部校正（不改变衰减梯度C值） ====================
    // 盆地软土放大效应仅叠加独立的 +ΔI，不混入基岩衰减公式。
    // 近场 (R < 30km) 时盆地效应减弱，远场 (R > 50km) 时放大 0.5°。

    /** 四川盆地场地放大（deprecated in v1.3.0：改用独立的 siteCorrection） */
    private const val BASIN_AMPLIFICATION_FAR = 0.5   // R > 50km
    private const val BASIN_AMPLIFICATION_NEAR = 0.2  // R <= 50km

    /** 检查是否在四川盆地沉积层区域内 */
    private fun isSichuanBasin(lat: Double, lon: Double): Boolean =
        lat in 28.0..33.0 && lon in 103.0..108.0

    /** 场地校正：仅对盆地软土站点 */
    private fun siteCorrection(epiLat: Double, epiLon: Double, epicenterDistKm: Double): Double {
        if (!isSichuanBasin(epiLat, epiLon)) return 0.0
        return if (epicenterDistKm > 50.0) BASIN_AMPLIFICATION_FAR else BASIN_AMPLIFICATION_NEAR
    }

    // ==================== 分区选择 ====================

    /**
     * 根据震中位置选择 GB 18306-2015 最优分区衰减系数。
     * 四分区边界近似：
     *  - 新疆区：lat > 34° 且 lon < 97°
     *  - 青藏区：lon < 105°（含青藏高原、川西、滇西）
     *  - 东部强震区：lat > 35° 且 lon > 105°
     *  - 中强地震区：其余区域
     */
    fun selectAttenuation(epiLat: Double, epiLon: Double): AttenuationCoeff {
        if (epiLat > 34.2 && epiLon < 96.7) return COEFF_XINJIANG
        if (epiLon < 105.0) return COEFF_QINGZANG
        if (epiLat > 35.0) return COEFF_EAST_STRONG
        if (epiLat < 35.0 && epiLon > 105.0) return COEFF_MODERATE
        return COEFF_FALLBACK
    }

    /**
     * 估算用户所在地的地震烈度（v1.3.0 分区模型 + 场地校正）。
     *
     * I = A + B*M + C*lg(R + R0) + 0.5σ + ΔI_site
     * 其中 ΔI_site 为盆地软土放大（仅四川盆地，独立于基岩衰减公式）。
     * M < 3.0 或 R < 0 返回 -1（无效值）。
     */
    fun estimateSiteIntensity(magnitude: Double, epicenterDistKm: Double, depthKm: Double,
                               epiLat: Double, epiLon: Double): Double {
        if (magnitude < 3.0 || epicenterDistKm < 0) return -1.0
        if (magnitude <= 0) return 0.0
        val d = if (depthKm > 0) depthKm else 10.0
        val hypoDist = sqrt(epicenterDistKm * epicenterDistKm + d * d)

        val coeff = selectAttenuation(epiLat, epiLon)
        val isNearBoundary = isNearPartitionBoundary(epiLat, epiLon)

        val baseI = if (isNearBoundary) {
            val altCoeff = selectAlternativeAttenuation(epiLat, epiLon)
            max(calcIntensity(magnitude, hypoDist, coeff), calcIntensity(magnitude, hypoDist, altCoeff))
        } else {
            calcIntensity(magnitude, hypoDist, coeff)
        }

        // +0.5σ 安全边际 + 盆地场地校正
        return (baseI + 0.5 * coeff.sigma + siteCorrection(epiLat, epiLon, epicenterDistKm))
            .coerceIn(0.0, 12.0)
    }

    /** 标准 GB 18306-2015 短轴衰减计算（不含σ修正、不含场地校正） */
    private fun calcIntensity(magnitude: Double, hypoDist: Double, coeff: AttenuationCoeff): Double {
        return (coeff.A + coeff.B * magnitude + coeff.C * log10(hypoDist + coeff.R0))
            .coerceIn(0.0, 12.0)
    }

    /** 检查坐标是否在分区边界 15km 内 */
    private fun isNearPartitionBoundary(lat: Double, lon: Double): Boolean =
        partitionBoundaryDistance(lat, lon) < 15.0

    /** 估算距最近分区边界的距离（km） */
    private fun partitionBoundaryDistance(lat: Double, lon: Double): Double {
        // 关键分区边界线：[起点(lat,lon), 终点(lat,lon)]
        data class Line(val ax: Double, val ay: Double, val bx: Double, val by: Double)
        val boundaries = listOf(
            Line(21.0, 105.0, 45.0, 105.0),    // 东西分区
            Line(35.0, 96.7, 45.0, 96.7),      // 新疆/青海边界
            Line(28.0, 103.0, 33.0, 108.0),    // 川滇/盆地
        )
        var minDist = Double.MAX_VALUE
        for (l in boundaries) {
            val dist = pointToLineDistance(lat, lon, l.ax, l.ay, l.bx, l.by)
            if (dist < minDist) minDist = dist
        }
        return minDist
    }

    /** 点到线段的最短距离（球面近似，单位 km） */
    private fun pointToLineDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dLatAB = bx - ax
        val dLonAB = by - ay
        if (dLatAB == 0.0 && dLonAB == 0.0) return haversineKm(px, py, ax, ay)
        val t = ((px - ax) * dLatAB + (py - ay) * dLonAB) / (dLatAB * dLatAB + dLonAB * dLonAB)
        val nearestLat: Double
        val nearestLon: Double
        if (t < 0) { nearestLat = ax; nearestLon = ay }
        else if (t > 1) { nearestLat = bx; nearestLon = by }
        else { nearestLat = ax + t * dLatAB; nearestLon = ay + t * dLonAB }
        return haversineKm(px, py, nearestLat, nearestLon)
    }

    /** Haversine公式（避免循环引用，GeoUtils内部副本） */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 获取备选分区系数（用于边界双区并行计算取MAX） */
    private fun selectAlternativeAttenuation(lat: Double, lon: Double): AttenuationCoeff {
        if (lat > 34.2 && lon < 96.7) return COEFF_QINGZANG     // 新疆→青藏
        if (lon < 105.0) return COEFF_MODERATE                   // 青藏→中强
        if (lat > 35.0) return COEFF_MODERATE                    // 东部→中强
        if (lat < 35.0 && lon > 105.0) return COEFF_EAST_STRONG  // 中强→东部
        return COEFF_FALLBACK
    }

    /**
     * 获取烈度估算的不确定性范围（±1σ）。
     * @return null 如果震级≤0，否则返回 (下限, 上限)
     */
    fun intensityRange(magnitude: Double, epicenterDistKm: Double, depthKm: Double,
                       epiLat: Double, epiLon: Double): Pair<Double, Double>? {
        if (magnitude <= 0) return null
        val mean = estimateSiteIntensity(magnitude, epicenterDistKm, depthKm, epiLat, epiLon)
        // 已含 +0.5σ，所以范围是 [mean-1σ, mean+0.5σ]
        val coeff = selectAttenuation(epiLat, epiLon)
        val lower = (mean - coeff.sigma).coerceAtLeast(0.0)
        val upper = (mean + 0.5 * coeff.sigma).coerceAtMost(12.0)
        return lower to upper
    }

    /** 获取当前使用的衰减公式的区域名称（供 UI 展示/调试） */
    fun attenuationRegionName(epiLat: Double, epiLon: Double): String =
        selectAttenuation(epiLat, epiLon).regionName

    // ==================== 省级边界表 ====================

    fun provinceOf(lat: Double, lon: Double): String? {
        val provinces = arrayOf(
            "北京市" to arrayOf(39.4, 41.1, 115.4, 117.5),
            "天津市" to arrayOf(38.5, 40.5, 116.5, 118.3),
            "上海市" to arrayOf(30.5, 32.1, 120.8, 122.1),
            "重庆市" to arrayOf(28.4, 32.0, 105.3, 110.2),
            "河北省" to arrayOf(36.0, 42.6, 113.0, 120.0),
            "山西省" to arrayOf(34.3, 40.8, 110.0, 114.6),
            "内蒙古自治区" to arrayOf(37.3, 53.5, 97.0, 126.5),
            "辽宁省" to arrayOf(38.4, 43.6, 118.8, 125.9),
            "吉林省" to arrayOf(40.8, 46.3, 121.0, 131.3),
            "黑龙江省" to arrayOf(43.3, 53.6, 121.0, 135.1),
            "江苏省" to arrayOf(30.5, 35.2, 116.5, 121.9),
            "浙江省" to arrayOf(27.0, 31.2, 118.0, 123.1),
            "安徽省" to arrayOf(29.3, 34.8, 114.8, 120.0),
            "福建省" to arrayOf(23.3, 28.4, 116.2, 120.9),
            "江西省" to arrayOf(24.3, 30.1, 113.3, 118.4),
            "山东省" to arrayOf(34.3, 38.5, 114.4, 122.9),
            "河南省" to arrayOf(31.3, 36.4, 110.2, 116.7),
            "湖北省" to arrayOf(29.0, 33.4, 108.2, 116.3),
            "湖南省" to arrayOf(24.5, 30.2, 109.0, 114.3),
            "广东省" to arrayOf(20.1, 25.6, 109.7, 117.4),
            "广西壮族自治区" to arrayOf(20.8, 26.5, 104.4, 112.2),
            "海南省" to arrayOf(18.1, 20.1, 108.5, 111.3),
            "四川省" to arrayOf(26.0, 34.4, 97.3, 108.7),
            "贵州省" to arrayOf(24.4, 29.3, 103.5, 109.8),
            "云南省" to arrayOf(21.1, 29.3, 97.3, 106.3),
            "西藏自治区" to arrayOf(26.8, 36.5, 78.2, 99.3),
            "陕西省" to arrayOf(31.6, 39.6, 105.3, 111.4),
            "甘肃省" to arrayOf(32.5, 42.8, 92.2, 108.8),
            "青海省" to arrayOf(31.3, 39.2, 89.3, 103.2),
            "宁夏回族自治区" to arrayOf(34.3, 39.5, 104.2, 107.8),
            "新疆维吾尔自治区" to arrayOf(34.2, 49.2, 73.5, 96.7),
            "中国台湾" to arrayOf(21.9, 25.3, 119.5, 122.1),
            "香港特别行政区" to arrayOf(22.1, 22.6, 113.5, 114.5),
            "澳门特别行政区" to arrayOf(21.9, 22.3, 113.2, 113.7)
        )
        for ((name, box) in provinces) {
            val (minLat, maxLat, minLon, maxLon) = box
            if (lat in minLat..maxLat && lon in minLon..maxLon) return name
        }
        return null
    }
}
