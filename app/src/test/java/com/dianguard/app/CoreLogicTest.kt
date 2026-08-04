package com.dianguard.app

import org.junit.Assert.*
import org.junit.Test

/**
 * 核心逻辑单元测试 — parseEew / parseIntensity / haversineKm / makeQuakeKey
 */
class CoreLogicTest {

    // ===================== parseIntensity =====================

    @Test fun intensityEmpty() = assertEquals(0.0, parseIntensity(""), 0.01)
    @Test fun intensityDash() = assertEquals(0.0, parseIntensity("-"), 0.01)
    @Test fun intensityBlank() = assertEquals(0.0, parseIntensity("   "), 0.01)
    @Test fun intensityRoman3() = assertEquals(3.0, parseIntensity("Ⅲ"), 0.01)
    @Test fun intensityRoman5() = assertEquals(5.0, parseIntensity("Ⅴ"), 0.01)
    @Test fun intensityRoman8() = assertEquals(8.0, parseIntensity("Ⅷ"), 0.01)
    @Test fun intensityArabic() = assertEquals(6.0, parseIntensity("6"), 0.01)
    @Test fun intensityArabicFloat() = assertEquals(4.5, parseIntensity("4.5"), 0.01)
    @Test fun intensityOutOfRangeHigh() = assertEquals(12.0, parseIntensity("99"), 0.01)
    @Test fun intensityOutOfRangeLow() = assertEquals(0.0, parseIntensity("-5"), 0.01)
    @Test fun intensityInvalid() = assertEquals(0.0, parseIntensity("abc"), 0.01)
    @Test fun intensityWithChinese() = assertEquals(5.0, parseIntensity("5度"), 0.01)

    // ===================== haversineKm =====================

    @Test fun haversineBeijingToShanghai() {
        // 北京 (39.9, 116.4) → 上海 (31.2, 121.5) ≈ 1068 km
        val d = haversineKm(39.9, 116.4, 31.2, 121.5)
        assertTrue("Expected ~1068km, got ${d.toInt()}km", d in 1050.0..1090.0)
    }

    @Test fun haversineSamePoint() {
        assertEquals(0.0, haversineKm(25.0, 102.0, 25.0, 102.0), 0.01)
    }

    @Test fun haversineEquator() {
        // 赤道上经度差 1° ≈ 111.32 km
        val d = haversineKm(0.0, 0.0, 0.0, 1.0)
        assertTrue("Expected ~111km, got ${d.toInt()}km", d in 109.0..113.0)
    }

    @Test fun haversineAntipodes() {
        // 对跖点距离 ≈ 20015 km (半周长)
        val d = haversineKm(25.0, 102.0, -25.0, -78.0)
        assertTrue("Expected ~20000km, got ${d.toInt()}km", d in 19500.0..20500.0)
    }

    // ===================== makeQuakeKey =====================

    @Test fun quakeKeyDeterministic() {
        val eew = Eew("ev1", "ev1", 1, "2026-08-02 15:30:00",
            "云南普洱", 22.8, 101.0, 5.2, 10.0, "6")
        val k1 = makeQuakeKey(eew)
        val k2 = makeQuakeKey(eew)
        assertEquals(k1, k2)
    }

    @Test fun quakeKeyDifferentEvents() {
        val e1 = Eew("ev1", "ev1", 1, "2026-08-02 15:30:00",
            "云南普洱", 22.8, 101.0, 5.2, 10.0, "6")
        val e2 = Eew("ev2", "ev2", 1, "2026-08-02 16:00:00",
            "四川宜宾", 28.5, 104.5, 5.0, 8.0, "5")
        assertNotEquals(makeQuakeKey(e1), makeQuakeKey(e2))
    }

    @Test fun quakeKeyRobustToRounding() {
        // 同一震中, 经纬度微小差异应在舍入后得到相同 key（同一地震的发震时刻应一致）
        val e1 = Eew("ev1", "ev1", 1, "2026-08-02 15:30:00",
            "云南", 22.8001, 101.0001, 5.2, 10.0, "6")
        val e2 = Eew("ev2", "ev2", 2, "2026-08-02 15:30:00",
            "云南", 22.8002, 101.0002, 5.2, 10.0, "6")
        assertEquals(makeQuakeKey(e1), makeQuakeKey(e2))
    }

    // ===================== parseEew =====================

    @Test fun parseEewValid() {
        val raw = """{"EventID":"test1","ReportNum":1,
            "OriginTime":"2026-08-02 15:30:00",
            "HypoCenter":"云南普洱市思茅区",
            "Latitude":22.8,"Longitude":101.0,
            "Magnitude":5.2,"Depth":10}"""
        val eew = parseEew(raw)
        assertNotNull(eew)
        assertEquals("test1", eew!!.eventId)
        assertEquals("云南普洱市思茅区", eew.hypoCenter)
        assertEquals(5.2, eew.magnitude, 0.01)
    }

    @Test fun parseEewLatitudeOutOfRange() {
        val raw = """{"Latitude":200,"Longitude":100,"Magnitude":5.0}"""
        assertNull(parseEew(raw))
    }

    @Test fun parseEewLongitudeOutOfRange() {
        val raw = """{"Latitude":25,"Longitude":300,"Magnitude":5.0}"""
        assertNull(parseEew(raw))
    }

    @Test fun parseEewEmptyJson() {
        assertNull(parseEew("{}"))
    }

    @Test fun parseEewInvalidJson() {
        assertNull(parseEew("not json"))
    }

    @Test fun parseEewMagunitudeTypo() {
        // 老版 CENC 报文有时拼成 Magunitude
        val raw = """{"EventID":"t2","Latitude":25.0,"Longitude":100.0,
            "Magunitude":6.1,"Depth":12}"""
        val eew = parseEew(raw)
        assertNotNull(eew)
        assertEquals(6.1, eew!!.magnitude, 0.01)
    }

    @Test fun parseEewMissingFields() {
        assertNull(parseEew("""{"EventID":"t3","Latitude":25.0}"""))
    }

    // ===================== warningLevel (按震级，历史列表用，王暾团队标准) =====================
    // 历史列表颜色代表地震本身能量规模：红 ≥6.0 / 橙 5.0-5.9 / 黄 3.0-4.9 / 蓝 <3.0

    @Test fun warningLevelBlue() = assertEquals(WarningLevel.BLUE, warningLevel(2.9))
    @Test fun warningLevelBlueLower() = assertEquals(WarningLevel.BLUE, warningLevel(0.5))
    @Test fun warningLevelYellow() = assertEquals(WarningLevel.YELLOW, warningLevel(3.0))
    @Test fun warningLevelYellowUpper() = assertEquals(WarningLevel.YELLOW, warningLevel(4.9))
    @Test fun warningLevelOrange() = assertEquals(WarningLevel.ORANGE, warningLevel(5.0))
    @Test fun warningLevelOrangeUpper() = assertEquals(WarningLevel.ORANGE, warningLevel(5.9))
    @Test fun warningLevelRed() = assertEquals(WarningLevel.RED, warningLevel(6.0))
    @Test fun warningLevelRedStrong() = assertEquals(WarningLevel.RED, warningLevel(8.0))
    @Test fun warningLevelNone() = assertEquals(WarningLevel.NONE, warningLevel(0.0))

    // ===================== warningLevelByIntensity（用户所在地烈度，官方标准 7/5/3 分界） =====================
    // 中国地震局标准：红 ≥ 7°（灾害性）｜橙 5–6°（灾害性）｜黄 3–4°（告知性）｜蓝 < 3°（告知性）

    @Test fun warningLevelByIntensityBlue() = assertEquals(WarningLevel.BLUE, warningLevelByIntensity(0.5))
    @Test fun warningLevelByIntensityBlueUpper() = assertEquals(WarningLevel.BLUE, warningLevelByIntensity(2.9))
    @Test fun warningLevelByIntensityYellow() = assertEquals(WarningLevel.YELLOW, warningLevelByIntensity(3.0))
    @Test fun warningLevelByIntensityYellowUpper() = assertEquals(WarningLevel.YELLOW, warningLevelByIntensity(4.9))
    @Test fun warningLevelByIntensityOrange() = assertEquals(WarningLevel.ORANGE, warningLevelByIntensity(5.0))
    @Test fun warningLevelByIntensityOrangeUpper() = assertEquals(WarningLevel.ORANGE, warningLevelByIntensity(6.9))
    @Test fun warningLevelByIntensityRed() = assertEquals(WarningLevel.RED, warningLevelByIntensity(7.0))
    @Test fun warningLevelByIntensityRedStrong() = assertEquals(WarningLevel.RED, warningLevelByIntensity(9.0))

    // ===================== estimateIntensityFromMagnitude =====================
    // 文档约定：近震中烈度 ≈ 震级（兜底估算，非精确烈度）

    @Test fun estIntensityM4() = assertEquals(4.0, estimateIntensityFromMagnitude(4.0), 0.1)
    @Test fun estIntensityM5() = assertEquals(5.0, estimateIntensityFromMagnitude(5.0), 0.1)
    @Test fun estIntensityM7() = assertEquals(7.0, estimateIntensityFromMagnitude(7.0), 0.1)
    @Test fun estIntensityM8() = assertEquals(8.0, estimateIntensityFromMagnitude(8.0), 0.1)
    @Test fun estIntensityZero() = assertEquals(0.0, estimateIntensityFromMagnitude(0.0), 0.1)

    // ===================== estimateSiteIntensity（用户所在地烈度衰减，P0-1/P0-2 核心） =====================
    // 汪素云等 2000 短轴衰减模型：I = 2.941 + 1.363·M − 1.494·ln(R+7)，深度缺失按 10km 兜底。
    // 下列用例直接锁定「判定层语义修复」：远震/弱震必须静默，近场中强震才告警。

    @Test fun estSiteIntensityNearM5() {
        // M5.0 / R20km / h10 → ~4.7（≥阈值3，应告警）
        assertEquals(4.7, estimateSiteIntensity(5.0, 20.0, 10.0), 0.1)
    }

    @Test fun estSiteIntensityPuerM58() {
        // 普洱 M5.8 / R280km / h12 → ~2.4（低于阈值3，昆明不应全屏）
        assertEquals(2.4, estimateSiteIntensity(5.8, 280.0, 12.0), 0.1)
    }

    @Test fun estSiteIntensityFarM5() {
        // M5.0 / R500km → ~0.5（静默）
        assertEquals(0.5, estimateSiteIntensity(5.0, 500.0, 0.0), 0.1)
    }

    @Test fun estSiteIntensityXinjiangM32() {
        // 新疆 M3.2 / R2600km → 0.0（全国小震误报场景，必须静默）
        assertEquals(0.0, estimateSiteIntensity(3.2, 2600.0, 0.0), 0.01)
    }

    @Test fun estSiteIntensityM70R100() {
        // M7.0 / R100km → ~5.5（中强震近场，橙色告警）
        assertEquals(5.5, estimateSiteIntensity(7.0, 100.0, 0.0), 0.1)
    }

    @Test fun estSiteIntensityWenchuanM79() {
        // 汶川级 M7.9 / R30km → ~8.3（近场，红色）
        assertEquals(8.3, estimateSiteIntensity(7.9, 30.0, 0.0), 0.1)
    }

    @Test fun estSiteIntensityDistantStrongSilent() {
        // P0-2 核心反例：新疆 M6.0 / R2600km → 0.0（震中烈度高但用户所在地无感，必须静默）
        assertEquals(0.0, estimateSiteIntensity(6.0, 2600.0, 0.0), 0.01)
    }

    @Test fun estSiteIntensityZeroMag() {
        // 震级非法 → 0.0，不触发
        assertEquals(0.0, estimateSiteIntensity(0.0, 100.0, 10.0), 0.01)
    }

    // ===================== parsePodrisEew（R5-1：解析器已接入 handleRaw 回退链） =====================

    @Test fun parsePodrisValid() {
        val raw = """{"event_type":"EEW","event_id":"podris-001","report_num":2,
            "magnitude":6.8,"location":[28.5,104.6],"depth":10,
            "region":"四川宜宾","time":"2026-08-05 00:00:00","intensity":8}"""
        val eew = parsePodrisEew(raw)
        assertNotNull(eew)
        assertEquals("podris-001", eew!!.eventId)
        assertEquals(6.8, eew.magnitude, 0.01)
        assertEquals(28.5, eew.latitude, 0.01)
        assertEquals(104.6, eew.longitude, 0.01)
        assertEquals("四川宜宾", eew.hypoCenter)
        assertEquals(2, eew.reportNum)
    }

    @Test fun parsePodrisWrongEventType() {
        // 非 EEW 类型（心跳/状态帧）必须返回 null，不得被误解析
        val raw = """{"event_type":"heartbeat","data":"ok"}"""
        assertNull(parsePodrisEew(raw))
    }

    @Test fun parsePodrisMissingLocation() {
        assertNull(parsePodrisEew("""{"event_type":"EEW","magnitude":5.0}"""))
    }

    @Test fun parsePodrisInvalidJson() {
        assertNull(parsePodrisEew("not json"))
    }

    @Test fun parsePodrisInvalidCoords() {
        val raw = """{"event_type":"EEW","location":[200,100],"magnitude":5.0}"""
        assertNull(parsePodrisEew(raw))
    }

    @Test fun parsePodrisZeroMag() {
        // 震级 ≤ 0（心跳/非预警帧）→ null
        val raw = """{"event_type":"EEW","location":[28.5,104.6],"magnitude":0}"""
        assertNull(parsePodrisEew(raw))
    }

    // ===================== parseIclEew（ICL 减灾所官方 HTTP 轮询源，v1.3.0） =====================

    @Test fun parseIclValid() {
        val obj = org.json.JSONObject("""{"eventId":"icl-abc","updates":3,
            "latitude":28.5,"longitude":104.6,"depth":5,
            "epicenter":"四川宜宾","startAt":1785694905000,"magnitude":5.1,"epiIntensity":7}""")
        val eew = parseIclEew(obj)
        assertNotNull(eew)
        assertEquals("icl-abc", eew!!.eventId)
        assertEquals(5.1, eew.magnitude, 0.01)
        assertEquals(3, eew.reportNum)
        assertEquals("四川宜宾", eew.hypoCenter)
        // startAt 毫秒时间戳 → 本地时区（Asia/Shanghai）字符串，仅断言非空
        assertTrue(eew.originTime.isNotBlank())
    }

    @Test fun parseIclMissingEventId() {
        assertNull(parseIclEew(org.json.JSONObject("""{"magnitude":5.0,"latitude":28.5,"longitude":104.6}""")))
    }

    @Test fun parseIclInvalidCoords() {
        assertNull(parseIclEew(org.json.JSONObject("""{"eventId":"x","magnitude":5.0,"latitude":999,"longitude":104.6}""")))
    }

    @Test fun parseIclZeroMag() {
        assertNull(parseIclEew(org.json.JSONObject("""{"eventId":"x","magnitude":0,"latitude":28.5,"longitude":104.6}""")))
    }
}
