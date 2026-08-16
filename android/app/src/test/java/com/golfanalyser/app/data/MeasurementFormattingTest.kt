package com.golfanalyser.app.data

import com.golfanalyser.app.ui.displayValue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementFormattingTest {
    @Test
    fun `rounds measurements sent to the model to two decimal places`() {
        assertEquals("0.87", roundedMeasurement(JsonPrimitive(0.8742)).toString())
        assertEquals("12.35", roundedMeasurement(JsonPrimitive(12.346)).toString())
    }

    @Test
    fun `displays decimal measurements with two decimal places`() {
        assertEquals("0.87", JsonPrimitive(0.8742).displayValue())
        assertEquals("12.00", JsonPrimitive(12.0).displayValue())
        assertEquals("12", JsonPrimitive(12).displayValue())
    }
}
