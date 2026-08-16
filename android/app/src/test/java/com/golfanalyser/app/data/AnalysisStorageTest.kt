package com.golfanalyser.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `usage includes videos and extracted evidence`() {
        val root = temporaryFolder.newFolder("analyses")
        File(root, "swing_one").apply {
            mkdirs()
            File(this, "original.mp4").writeBytes(ByteArray(11))
            File(this, "llm_frames").apply {
                mkdirs()
                File(this, "frame.jpg").writeBytes(ByteArray(7))
            }
        }
        File(root, "swing_two").apply {
            mkdirs()
            File(this, "result.json").writeBytes(ByteArray(5))
        }

        val usage = AnalysisStorage(root).usage()

        assertEquals(23L, usage.totalBytes)
        assertEquals(2, usage.analysisCount)
        assertEquals(18L, usage.bytesByRunId["swing_one"])
    }

    @Test
    fun `delete analysis removes only requested run`() {
        val root = temporaryFolder.newFolder("analyses")
        val first = File(root, "swing_one").apply { mkdirs() }
        val second = File(root, "swing_two").apply { mkdirs() }

        AnalysisStorage(root).deleteAnalysis("swing_one")

        assertFalse(first.exists())
        assertEquals(true, second.exists())
    }

    @Test
    fun `delete all keeps the analyses root ready for new runs`() {
        val root = temporaryFolder.newFolder("analyses")
        File(root, "swing_one").apply { mkdirs() }

        AnalysisStorage(root).deleteAllAnalyses()

        assertEquals(true, root.isDirectory)
        assertEquals(0, root.listFiles()?.size)
    }

    @Test
    fun `delete rejects path traversal`() {
        val root = temporaryFolder.newFolder("analyses")

        assertThrows(IllegalArgumentException::class.java) {
            AnalysisStorage(root).deleteAnalysis("../outside")
        }
    }
}
