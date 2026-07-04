package com.golfanalyser.app.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesAndReadsCachedArtifact() {
        val cache = ArtifactCache(temporaryFolder.newFolder("artifacts"))

        val file = cache.write(
            runId = "swing_1",
            artifactName = ArtifactCache.ANNOTATED_VIDEO,
            artifactPath = "/analyses/swing_1/artifacts/annotated_video",
            input = ByteArrayInputStream("video".toByteArray()),
        )

        assertTrue(file.exists())
        assertEquals("video", file.readText())
        assertEquals(
            file.absolutePath,
            cache.cachedFile(
                runId = "swing_1",
                artifactName = ArtifactCache.ANNOTATED_VIDEO,
                artifactPath = "/analyses/swing_1/artifacts/annotated_video",
            )?.absolutePath,
        )
    }

    @Test
    fun invalidatesRunArtifactsExceptProtectedFile() {
        val cache = ArtifactCache(temporaryFolder.newFolder("artifacts"))
        val protected = cache.write("swing_1", ArtifactCache.ANNOTATED_VIDEO, "protected", bytes("a"))
        val stale = cache.write("swing_1", ArtifactCache.ANNOTATED_VIDEO, "stale", bytes("b"))

        cache.invalidateRun("swing_1", protectedFile = protected)

        assertTrue(protected.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun clearAllRemovesCachedFilesExceptProtectedFile() {
        val cache = ArtifactCache(temporaryFolder.newFolder("artifacts"))
        val protected = cache.write("swing_1", ArtifactCache.ANNOTATED_VIDEO, "protected", bytes("a"))
        val other = cache.write("swing_2", ArtifactCache.ANNOTATED_VIDEO, "other", bytes("b"))

        cache.clearAll(protectedFile = protected)

        assertTrue(protected.exists())
        assertFalse(other.exists())
    }

    @Test
    fun pruneRemovesOldestFilesWhenOverLimit() {
        val cache = ArtifactCache(
            rootDir = temporaryFolder.newFolder("artifacts"),
            maxBytes = Long.MAX_VALUE,
            maxFiles = 2,
        )
        val first = cache.write("swing_1", ArtifactCache.ANNOTATED_VIDEO, "first", bytes("1"))
        first.setLastModified(1)
        val second = cache.write("swing_2", ArtifactCache.ANNOTATED_VIDEO, "second", bytes("2"))
        second.setLastModified(2)
        val third = cache.write("swing_3", ArtifactCache.ANNOTATED_VIDEO, "third", bytes("3"))

        cache.prune(protectedFile = third)

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(third.exists())
        assertNotNull(cache.cachedFile("swing_3", ArtifactCache.ANNOTATED_VIDEO, "third"))
    }

    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray())
}
