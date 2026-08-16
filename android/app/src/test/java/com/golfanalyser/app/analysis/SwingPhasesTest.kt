package com.golfanalyser.app.analysis

import com.golfanalyser.app.analysis.SwingPhases.ADDRESS_PHASE
import com.golfanalyser.app.analysis.SwingPhases.IMPACT_PHASE
import com.golfanalyser.app.analysis.SwingPhases.TOP_PHASE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwingPhasesTest {
    @Test
    fun detectsOrderedSwingWhenFinishHandsRiseHigherThanBackswing() {
        val yValues = buildList {
            repeat(8) { add(0.72) }
            addAll(listOf(0.69, 0.64, 0.57, 0.50, 0.45, 0.42, 0.43, 0.45))
            addAll(listOf(0.50, 0.58, 0.66, 0.74, 0.70, 0.60, 0.48, 0.34, 0.22, 0.18, 0.17))
        }
        val frames = yValues.mapIndexed { index, y -> frame(index, y) }

        val phases = SwingPhases.detect(frames, fps = 30.0)
        val byName = SwingPhases.byName(phases)

        assertTrue(byName.getValue(TOP_PHASE).frameIndex < 20)
        assertTrue(byName.getValue(TOP_PHASE).frameIndex < byName.getValue(IMPACT_PHASE).frameIndex)
        assertTrue(byName.getValue(IMPACT_PHASE).frameIndex < byName.getValue(SwingPhases.FINISH_PHASE).frameIndex)
    }

    @Test
    fun manualConfirmationAcceptsAllNineOrderedMarkers() {
        val phases = SwingPhases.buildConfirmed(0, listOf(5, 10, 15, 20, 25, 30, 35, 40), fps = 20.0)

        assertEquals(SwingPhases.phaseNames, phases.map { it.name })
        assertEquals("user_confirmed_marker", phases.first { it.name == ADDRESS_PHASE }.detectionMethod)
        assertEquals(1.5, phases.first { it.name == IMPACT_PHASE }.timestampSeconds, 0.0001)
    }

    @Test
    fun qualityIssuesRejectImplausibleAutomaticTempo() {
        val phases = SwingPhases.phaseNames.mapIndexed { index, name ->
            SwingPhase(
                name = name,
                frameIndex = listOf(0, 5, 10, 12, 20, 30, 42, 48, 60)[index],
                timestampSeconds = index.toDouble(),
                confidence = 0.7,
                detectionMethod = "automatic",
            )
        }

        val issues = SwingPhases.qualityIssues(phases, fps = 30.0)

        assertTrue(issues.single().contains("downswing is longer"))
    }

    private fun frame(index: Int, wristY: Double): LandmarkFrame =
        LandmarkFrame(
            frameIndex = index,
            timestampSeconds = index / 30.0,
            poseDetected = true,
            landmarks = listOf(
                LandmarkPoint("left_wrist", 0.45, wristY, visibility = 0.99),
                LandmarkPoint("right_wrist", 0.55, wristY, visibility = 0.99),
            ),
        )
}
