package com.golfanalyser.app.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisCancellationTest {
    @Test
    fun `cancel gate interrupts running analysis`() {
        assertThrows(CancellationException::class.java) {
            throwIfAnalysisCancelled { true }
        }
    }

    @Test
    fun `cancel gate permits active analysis`() {
        throwIfAnalysisCancelled { false }
    }
}
