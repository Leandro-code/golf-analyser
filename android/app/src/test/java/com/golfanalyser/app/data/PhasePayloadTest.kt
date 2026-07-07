package com.golfanalyser.app.data

import com.golfanalyser.app.ui.phaseOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhasePayloadTest {
    @Test
    fun buildsFrameIndicesInBackendPhaseOrder() {
        val frames = phaseOrder.mapIndexed { index, phase -> phase to (index * 5).toString() }.toMap()

        assertEquals(
            listOf(0, 5, 10, 15, 20, 25, 30, 35, 40),
            buildPhaseConfirmationPayload(frames, phaseOrder),
        )
    }

    @Test
    fun rejectsMissingPhaseFrame() {
        val error = assertThrows(IllegalStateException::class.java) {
            buildPhaseConfirmationPayload(emptyMap(), phaseOrder)
        }

        assertEquals("Missing frame for Address", error.message)
    }
}
