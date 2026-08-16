package com.golfanalyser.app.data

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisPerformanceReport(
    @SerialName("run_id") val runId: String,
    val outcome: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("peak_pss_kb") val peakPssKb: Long,
    @SerialName("peak_managed_heap_bytes") val peakManagedHeapBytes: Long,
)

internal class AnalysisPerformanceTracker(
    private val runId: String,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val pssKb: () -> Long = Debug::getPss,
    private val managedHeapBytes: () -> Long = {
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    },
    private val logLine: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
) {
    private val startedAt = elapsedRealtime()
    private var peakPssKb = 0L
    private var peakManagedHeapBytes = 0L

    init {
        sample()
        logLine("START run_id=$runId")
    }

    fun sample() {
        peakPssKb = maxOf(peakPssKb, pssKb())
        peakManagedHeapBytes = maxOf(peakManagedHeapBytes, managedHeapBytes())
    }

    fun finish(outcome: String): AnalysisPerformanceReport {
        sample()
        val report = AnalysisPerformanceReport(
            runId = runId,
            outcome = outcome,
            durationMs = (elapsedRealtime() - startedAt).coerceAtLeast(0L),
            peakPssKb = peakPssKb,
            peakManagedHeapBytes = peakManagedHeapBytes,
        )
        logLine(
            "END run_id=$runId outcome=$outcome duration_ms=${report.durationMs} " +
                "peak_pss_kb=${report.peakPssKb} peak_heap_bytes=${report.peakManagedHeapBytes}",
        )
        return report
    }

    companion object {
        const val LOG_TAG = "GolfAnalysisProfile"
    }
}
