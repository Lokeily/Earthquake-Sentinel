package com.dianguard.app

/**
 * 地理位置公共工具（省级边界表等），供 LocationHelper、HistoryFetcher、BackupSource 等复用，
 * 消除多文件中完全重复的 provinceOf() 实现。
 */
object GeoUtils {

    /**
     * 内置省级边界表（近似），反向地理编码失败时的中文地名兜底（离线可用）。
     * 覆盖中国 34 个省级行政区，含台湾/香港/澳门。
     */
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
