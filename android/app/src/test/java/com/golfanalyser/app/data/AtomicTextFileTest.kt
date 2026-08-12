package com.golfanalyser.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTextFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `atomic replacement leaves only complete destination content`() {
        val destination = File(temporaryFolder.root, "status.json")
        AtomicTextFile.write(destination, "{\"status\":\"processing\"}")
        AtomicTextFile.write(destination, "{\"status\":\"completed\"}")

        assertEquals("{\"status\":\"completed\"}", destination.readText())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.endsWith(".pending") })
    }

    @Test
    fun `reader retries an empty artifact`() {
        val destination = File(temporaryFolder.root, "status.json").apply { writeText("") }
        var sleeps = 0

        val result = AtomicTextFile.read(
            file = destination,
            attempts = 2,
            retryDelayMs = 0,
            sleep = {
                sleeps += 1
                destination.writeText("ready")
            },
        ) { it }

        assertEquals("ready", result)
        assertEquals(1, sleeps)
    }
}
