package com.dianguard.app

import org.junit.Assert.*
import org.junit.Test

/**
 * FusionCalibration 自学习校准模块单元测试。
 * 覆盖：真值回填匹配/偏差统计/修正应用/最小样本防护/修正钳制/源独立修正。
 */
class FusionCalibrationTest {

    private val now = System.currentTimeMillis()

    private fun seedSource(sourceId: String, reportMag: Double, truthMag: Double, count: Int) {
        FusionCalibration.reset()
        for (i in 0 until count) {
            FusionCalibration.collectSample("evt_${sourceId}_$i", sourceId, reportMag, now + i, 28.0, 104.0)
            FusionCalibration.fillGroundTruth(now + i, truthMag, 28.0, 104.0)
        }
    }

    // ===================== 真值匹配 =====================

    @Test fun matchWithinWindowAndDist() {
        FusionCalibration.reset()
        FusionCalibration.collectSample("evt1", "usgs", 4.9, now, 28.0, 104.0)
        // 时间差 30s、距离 ~7km（0.05° 经度）→ 匹配回填
        FusionCalibration.fillGroundTruth(now + 30_000, 4.2, 28.05, 104.05)
        assertEquals(1, FusionCalibration.sampleCount())
    }

    @Test fun matchRejectedWhenTimeTooFar() {
        FusionCalibration.reset()
        FusionCalibration.collectSample("evt2", "usgs", 4.9, now, 28.0, 104.0)
        // 时间差 10 分钟 > 60s 窗口 → 不匹配
        FusionCalibration.fillGroundTruth(now + 600_000, 4.2, 28.05, 104.05)
        assertEquals(0, FusionCalibration.sampleCount())
    }

    @Test fun matchRejectedWhenDistTooFar() {
        FusionCalibration.reset()
        FusionCalibration.collectSample("evt3", "usgs", 4.9, now, 28.0, 104.0)
        // 距离约 480km（5 度经度差）> 200km → 不匹配
        FusionCalibration.fillGroundTruth(now, 4.2, 28.0, 109.0)
        assertEquals(0, FusionCalibration.sampleCount())
    }

    // ===================== 偏差统计与修正 =====================

    @Test fun correctionNotAppliedBelowMinSamples() {
        // 仅 5 个样本（< MIN_SAMPLES=10）→ 不做修正
        seedSource("usgs", 4.9, 4.2, 5)
        assertEquals(4.9, FusionCalibration.correctMagnitude("usgs", 4.9), 0.001)
    }

    @Test fun correctionAppliedAfterMinSamples() {
        // 12 个样本：源报 4.5、真值 4.2 → μ=+0.3（未超钳制）→ M' = 4.5 − 0.3 = 4.2
        seedSource("usgs", 4.5, 4.2, 12)
        assertEquals(4.2, FusionCalibration.correctMagnitude("usgs", 4.5), 0.01)
    }

    @Test fun correctionClampedToMax() {
        // 极端偏差 μ=+3.0 → 钳制 MAX_CORRECTION=0.5 → M' = 6.0 − 0.5 = 5.5
        seedSource("cenc", 6.0, 3.0, 12)
        assertEquals(5.5, FusionCalibration.correctMagnitude("cenc", 6.0), 0.01)
    }

    @Test fun correctionPerSourceIndependent() {
        FusionCalibration.reset()
        for (i in 0 until 12) {
            FusionCalibration.collectSample("evt_$i", "usgs", 4.5, now + i, 28.0, 104.0)
            FusionCalibration.collectSample("evt_$i", "icl", 4.2, now + i, 28.0, 104.0)
            FusionCalibration.fillGroundTruth(now + i, 4.2, 28.0, 104.0)
        }
        // USGS 偏高 μ=+0.3 → 修正到 4.2；ICL 准确 μ=0 → 保持不变
        assertEquals(4.2, FusionCalibration.correctMagnitude("usgs", 4.5), 0.01)
        assertEquals(4.2, FusionCalibration.correctMagnitude("icl", 4.2), 0.01)
    }

    // ===================== 边界防护 =====================

    @Test fun correctionIgnoresInvalidMag() {
        seedSource("usgs", 4.9, 4.2, 12)
        assertEquals(0.0, FusionCalibration.correctMagnitude("usgs", 0.0), 0.001)
        assertEquals(-1.0, FusionCalibration.correctMagnitude("usgs", -1.0), 0.001)
    }

    @Test fun collectSampleRejectsNonPositiveMag() {
        FusionCalibration.reset()
        FusionCalibration.collectSample("evtX", "usgs", 0.0, now, 28.0, 104.0)
        assertEquals(0, FusionCalibration.sampleCount())
    }

    @Test fun profilesExposedForUi() {
        seedSource("usgs", 4.9, 4.2, 12)
        val profiles = FusionCalibration.allProfiles()
        assertEquals(1, profiles.size)
        assertEquals("usgs", profiles[0].sourceId)
        assertEquals(12, profiles[0].sampleCount)
        assertEquals(0.7, profiles[0].meanBias, 0.01)
    }
}
