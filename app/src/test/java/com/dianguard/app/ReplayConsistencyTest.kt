package com.dianguard.app

import org.junit.Assert.*
import org.junit.Test

class ReplayConsistencyTest {
    private fun report(source: String, event: String, num: Int, received: Long, mag: Double) =
        QuakeReportSnapshot(source, event, num, received, "2026-08-25 10:00:00", "云南", mag, 10.0, 25.0, 102.0, source)

    private fun record(reports: List<QuakeReportSnapshot>) = QuakeRecord(
        key = "k", timeMs = 1L, originTime = "2026-08-25 10:00:00", place = "云南",
        magnitude = 5.0, depthKm = 10.0, intensity = "4.0", distanceKm = 50.0,
        etaSec = 10.0, sourceName = "source", reportNum = 1, triggered = true,
        backup = false, reports = reports
    )

    @Test fun sameOfficialEventProducesSequence() {
        val seq = officialReportSequence(record(listOf(
            report("cenc", "e1", 2, 2_000L, 5.2),
            report("cenc", "e1", 1, 1_000L, 4.8),
            report("cenc", "e1", 3, 3_000L, 5.4)
        )))
        assertEquals(listOf(1, 2, 3), seq.map { it.reportNum })
    }

    @Test fun crossSourceFirstReportsAreNotFakeSequence() {
        val seq = officialReportSequence(record(listOf(
            report("cenc", "c1", 1, 1_000L, 5.0),
            report("sc", "s1", 1, 1_100L, 5.1),
            report("cq", "q1", 1, 1_200L, 4.9)
        )))
        assertTrue("跨机构首报不能伪装成官方第一/二/三报", seq.isEmpty())
    }

    @Test fun catalogSingleResultHasNoSequence() {
        assertTrue(officialReportSequence(record(emptyList())).isEmpty())
    }

    @Test fun mixedSourcesPickLargestSameSourceGroupAsOfficialSequence() {
        // cenc 有完整 1/2/3 报，sc 只有首报——序列必须取 cenc 三报，
        // 不能把 sc 首报混入当"第四报"。
        val seq = officialReportSequence(record(listOf(
            report("cenc", "e1", 2, 2_000L, 5.2),
            report("sc", "e1", 1, 1_500L, 5.1),
            report("cenc", "e1", 1, 1_000L, 4.8),
            report("cenc", "e1", 3, 3_000L, 5.4)
        )))
        assertEquals(listOf(1, 2, 3), seq.map { it.reportNum })
        assertTrue("序列必须全部来自同一来源", seq.all { it.sourceId == "cenc" })
    }

    @Test fun remainingEtaSubtractsOfficialLatency() {
        // 35km/3.5km/s = 10s，总延迟4s，剩余应为6s（深度=0）。
        assertEquals(6.0, remainingSWaveEtaSeconds(35.0, 0.0, 1_000L, 5_000L), 0.01)
    }

    @Test fun remainingEtaNeverNegative() {
        assertEquals(0.0, remainingSWaveEtaSeconds(3.5, 0.0, 1_000L, 10_000L), 0.01)
    }
}
