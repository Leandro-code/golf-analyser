package com.golfanalyser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingFocusTest {
    @Test
    fun toggleLimitsGoalsAndKeepsShotShapeGoalsMutuallyExclusive() {
        val focus = CoachingFocusDto()
            .toggle("reduce_fade_or_slice")
            .toggle("improve_consistency")
            .toggle("improve_posture_and_balance")
            .toggle("improve_rotation_and_turn")

        assertEquals(3, focus.goals.size)
        assertFalse("improve_rotation_and_turn" in focus.goals)

        val oppositeShape = focus.toggle("reduce_draw_or_hook")
        assertFalse("reduce_fade_or_slice" in oppositeShape.goals)
        assertTrue("reduce_draw_or_hook" in oppositeShape.goals)
    }

    @Test
    fun normalizationTrimsAndCapsTheCustomNote() {
        val normalized = CoachingFocusDto(
            goals = listOf("unknown", "improve_consistency", "improve_consistency"),
            customNote = "  ${"x".repeat(300)}  ",
        ).normalized()

        assertEquals(listOf("improve_consistency"), normalized.goals)
        assertEquals(MAX_COACHING_NOTE_LENGTH, normalized.customNote?.length)
    }

    @Test
    fun copiedPracticeAdviceContainsOnlyActionableSections() {
        val assessment = LlmAssessmentDto(
            schemaVersion = "1.1.0",
            promptVersion = "1.1.0",
            model = "test-model",
            generatedAt = "2026-08-13T00:00:00Z",
            evidenceFingerprint = "fingerprint",
            coachingFocus = CoachingFocusDto(
                goals = listOf("reduce_fade_or_slice"),
                customNote = "My iron shots start right.",
            ),
            content = LlmContentDto(
                overview = "Overview should not be copied.",
                strengths = listOf("Strength should not be copied."),
                limitations = listOf("Limitation should not be copied."),
                priorities = listOf(
                    LlmPriorityDto(
                        title = "Control the transition",
                        rationale = "The transition frames support a smoother sequence.",
                        practiceCue = "Pause, then turn.",
                        drills = listOf("Pause drill"),
                        practicePlan = listOf("Five rehearsals", "Hit three easy shots"),
                        confidence = 0.8,
                    ),
                ),
            ),
        )

        val copied = formatPracticeAdvice(assessment)

        assertTrue(copied.contains("Reduce a fade or slice"))
        assertTrue(copied.contains("Practice cue: Pause, then turn."))
        assertTrue(copied.contains("- Pause drill"))
        assertTrue(copied.contains("2. Hit three easy shots"))
        assertFalse(copied.contains("Overview should not be copied"))
        assertFalse(copied.contains("Strength should not be copied"))
        assertFalse(copied.contains("Limitation should not be copied"))
        assertFalse(copied.contains("test-model"))
    }
}
