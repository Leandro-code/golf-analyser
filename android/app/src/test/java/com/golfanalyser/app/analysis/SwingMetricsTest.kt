package com.golfanalyser.app.analysis

import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwingMetricsTest {
    @Test
    fun leadArmMetricUsesRightArmForLeftHandedGolfer() {
        val frames = listOf(
            frame(
                index = 0,
                leftElbowY = 0.5,
                rightElbowY = 0.5,
            ),
            frame(
                index = 10,
                leftElbowY = 0.5,
                rightElbowY = 0.25,
            ),
        )
        val phases = SwingPhases.buildConfirmed(0, listOf(1, 2, 10, 11, 12, 13, 14, 15), fps = 10.0)

        val metrics = SwingMetrics.calculate(
            landmarkFrames = frames,
            phases = phases,
            context = AnalysisContext(
                handedness = "left",
                cameraView = "face_on",
                clubFamily = "iron",
            ),
        )

        val leadArm = metrics.metrics.getValue("lead_arm_angle")
        assertEquals(10, leadArm.frameIndex)
        assertTrue((leadArm.value?.jsonPrimitive?.doubleOrNull ?: 0.0) < 180.0)
    }

    @Test
    fun impactHeadMovementUsesImpactFrameNotFinishExtrema() {
        val frames = listOf(
            frame(index = 0, noseX = 0.50),
            frame(index = 5, noseX = 0.52),
            frame(index = 10, noseX = 0.90),
        )
        val phases = SwingPhases.buildConfirmed(0, listOf(1, 2, 3, 4, 5, 6, 7, 8), fps = 10.0)

        val metrics = SwingMetrics.calculate(frames, phases, AnalysisContext("right", "face_on", "iron"))
        val headMovement = metrics.metrics.getValue("head_movement")

        assertEquals(5, headMovement.frameIndex)
        assertEquals(0.1, headMovement.value?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.0001)
    }

    private fun frame(
        index: Int,
        noseX: Double = 0.5,
        leftElbowY: Double = 0.5,
        rightElbowY: Double = 0.5,
    ): LandmarkFrame =
        LandmarkFrame(
            frameIndex = index,
            timestampSeconds = index / 10.0,
            poseDetected = true,
            landmarks = listOf(
                LandmarkPoint("nose", noseX, 0.2, visibility = 0.99),
                LandmarkPoint("left_shoulder", 0.4, 0.4, visibility = 0.99),
                LandmarkPoint("right_shoulder", 0.6, 0.4, visibility = 0.99),
                LandmarkPoint("left_hip", 0.43, 0.7, visibility = 0.99),
                LandmarkPoint("right_hip", 0.57, 0.7, visibility = 0.99),
                LandmarkPoint("left_elbow", 0.3, leftElbowY, visibility = 0.99),
                LandmarkPoint("right_elbow", 0.7, rightElbowY, visibility = 0.99),
                LandmarkPoint("left_wrist", 0.2, 0.6, visibility = 0.99),
                LandmarkPoint("right_wrist", 0.8, 0.6, visibility = 0.99),
                LandmarkPoint("left_knee", 0.43, 0.85, visibility = 0.99),
                LandmarkPoint("right_knee", 0.57, 0.85, visibility = 0.99),
                LandmarkPoint("left_ankle", 0.43, 1.0, visibility = 0.99),
                LandmarkPoint("right_ankle", 0.57, 1.0, visibility = 0.99),
            ),
        )
}
