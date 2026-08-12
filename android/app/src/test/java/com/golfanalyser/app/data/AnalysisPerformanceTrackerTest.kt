package com.golfanalyser.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisPerformanceTrackerTest {
    @Test
    fun `report retains peak samples and elapsed processing time`() {
        var now = 100L
        var pss = 10L
        var heap = 20L
        val tracker = AnalysisPerformanceTracker(
            runId = "swing_test",
            elapsedRealtime = { now },
            pssKb = { pss },
            managedHeapBytes = { heap },
            logLine = {},
        )
        pss = 42
        heap = 75L
        tracker.sample()
        pss = 30
        heap = 60L
        now = 2_600L

        val report = tracker.finish("completed")

        assertEquals(2_500L, report.durationMs)
        assertEquals(42L, report.peakPssKb)
        assertEquals(75L, report.peakManagedHeapBytes)
        assertEquals("completed", report.outcome)
    }
}
