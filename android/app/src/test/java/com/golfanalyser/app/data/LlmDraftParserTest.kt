package com.golfanalyser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmDraftParserTest {
    private val parser = LlmDraftParser()

    @Test
    fun exposesAnIncompleteStringAsReadableDraftText() {
        val draft = parser.parse("""{"overview":"A balanced start is vis""")

        assertEquals("A balanced start is vis", draft.overview)
        assertTrue(draft.hasVisibleContent)
    }

    @Test
    fun extractsNestedPrioritiesAndArraysWithoutDependingOnFieldOrder() {
        val draft = parser.parse(
            """{
                "priorities":[{
                    "practice_cue":"Turn, then swing",
                    "title":"Sequence",
                    "drills":["Pause drill","Step drill"],
                    "rationale":"The transition is visible"
                }],
                "overview":"Useful evidence",
                "strengths":["Stable finish"],
                "limitations":["Single camera view"]
            }""".trimIndent(),
        )

        assertEquals("Useful evidence", draft.overview)
        assertEquals(listOf("Stable finish"), draft.strengths)
        assertEquals("Sequence", draft.priorities.single().title)
        assertEquals("Turn, then swing", draft.priorities.single().practiceCue)
        assertEquals(listOf("Pause drill", "Step drill"), draft.priorities.single().drills)
        assertEquals(listOf("Single camera view"), draft.limitations)
    }

    @Test
    fun decodesEscapesAndIgnoresAnIncompleteEscapeSequence() {
        val complete = parser.parse("""{"overview":"Keep the \"triangle\"\nsteady"}""")
        val incomplete = parser.parse("""{"overview":"Keep the slash\""")

        assertEquals("Keep the \"triangle\"\nsteady", complete.overview)
        assertEquals("Keep the slash", incomplete.overview)
    }
}
