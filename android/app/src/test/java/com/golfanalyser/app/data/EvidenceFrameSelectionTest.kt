package com.golfanalyser.app.data

import com.golfanalyser.app.analysis.SwingPhases
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceFrameSelectionTest {
    @Test
    fun `keeps neighboring images around visually dynamic key phases`() {
        assertEquals(listOf(-3, 0, 3), evidenceFrameOffsets(SwingPhases.TOP_PHASE, 3))
        assertEquals(listOf(-3, 0, 3), evidenceFrameOffsets(SwingPhases.P6_PHASE, 3))
        assertEquals(listOf(-3, 0, 3), evidenceFrameOffsets(SwingPhases.IMPACT_PHASE, 3))
    }

    @Test
    fun `uses a single anchor image for less dynamic review phases`() {
        assertEquals(listOf(0), evidenceFrameOffsets(SwingPhases.ADDRESS_PHASE, 3))
        assertEquals(listOf(0), evidenceFrameOffsets(SwingPhases.FINISH_PHASE, 3))
    }
}
