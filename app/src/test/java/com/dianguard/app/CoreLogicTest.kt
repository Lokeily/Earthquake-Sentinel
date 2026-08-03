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
        // 同一震中, 经纬度微小差异应在舍入后得到相同 key
        val e1 = Eew("ev1", "ev1", 1, "2026-08-02 15:30:00",
            "云南", 22.8001, 101.0001, 5.2, 10.0, "6")
        val e2 = Eew("ev2", "ev2", 2, "2026-08-02 15:30:02",
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

    // ===================== warningLevel (震级) =====================

    @Test fun warningLevelBlue() = assertEquals(WarningLevel.BLUE, warningLevel(3.5))
    @Test fun warningLevelBlueUpper() = assertEquals(WarningLevel.BLUE, warningLevel(3.99))
    @Test fun warningLevelYellow() = assertEquals(WarningLevel.YELLOW, warningLevel(4.0))
    @Test fun warningLevelYellowUpper() = assertEquals(WarningLevel.YELLOW, warningLevel(4.99))
    @Test fun warningLevelOrange() = assertEquals(WarningLevel.ORANGE, warningLevel(5.0))
    @Test fun warningLevelOrangeUpper() = assertEquals(WarningLevel.ORANGE, warningLevel(5.99))
    @Test fun warningLevelRed() = assertEquals(WarningLevel.RED, warningLevel(6.0))
    @Test fun warningLevelRedStrong() = assertEquals(WarningLevel.RED, warningLevel(8.0))
    @Test fun warningLevelNone() = assertEquals(WarningLevel.NONE, warningLevel(0.0))

    // ===================== estimateIntensityFromMagnitude =====================

    @Test fun estIntensityM4() = assertEquals(4.0, estimateIntensityFromMagnitude(4.0), 0.1)
    @Test fun estIntensityM5() = assertEquals(6.0, estimateIntensityFromMagnitude(5.0), 0.1)
    @Test fun estIntensityM7() = assertEquals(9.0, estimateIntensityFromMagnitude(7.0), 0.1)
    @Test fun estIntensityM8() = assertEquals(12.0, estimateIntensityFromMagnitude(8.0), 0.1)
}
