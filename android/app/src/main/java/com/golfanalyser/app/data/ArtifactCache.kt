package com.golfanalyser.app.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class ArtifactCache(
    private val rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    fun cachedFile(runId: String, artifactName: String, artifactPath: String): File? {
        val file = targetFile(runId, artifactName, artifactPath)
        if (!file.exists() || !file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun write(
        runId: String,
        artifactName: String,
        artifactPath: String,
        input: InputStream,
        protectedFile: File? = null,
    ): File {
        val target = targetFile(runId, artifactName, artifactPath)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { output ->
            input.copyTo(output)
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Unable to cache downloaded replay." }
        target.setLastModified(System.currentTimeMillis())
        prune(protectedFile = protectedFile ?: target)
        return target
    }

    fun invalidateRun(runId: String, protectedFile: File? = null) {
        val runDir = File(rootDir, safeSegment(runId))
        if (!runDir.exists()) return
        runDir.walkBottomUp().forEach { file ->
            if (file == runDir || file == protectedFile) return@forEach
            file.delete()
        }
    }

    fun clearAll(protectedFile: File? = null) {
        if (!rootDir.exists()) return
        rootDir.walkBottomUp().forEach { file ->
            if (file == rootDir || file == protectedFile) return@forEach
            file.delete()
        }
    }

    fun prune(protectedFile: File? = null) {
        val files = cachedFiles().sortedBy { it.lastModified() }.toMutableList()
        fun totalBytes() = files.sumOf { it.length() }

        while (
            files.isNotEmpty() &&
            (files.size > maxFiles || totalBytes() > maxBytes)
        ) {
            val candidate = files.firstOrNull { it != protectedFile } ?: break
            candidate.delete()
            files.remove(candidate)
        }
    }

    private fun targetFile(runId: String, artifactName: String, artifactPath: String): File {
        val extension = when (artifactName) {
            ANNOTATED_VIDEO -> "mp4"
            else -> "artifact"
        }
        val name = "${safeSegment(artifactName)}_${hash(artifactPath)}.$extension"
        return File(File(rootDir, safeSegment(runId)), name)
    }

    private fun cachedFiles(): List<File> =
        if (rootDir.exists()) {
            rootDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".tmp") }
                .toList()
        } else {
            emptyList()
        }

    companion object {
        const val ANNOTATED_VIDEO = "annotated_video"
        const val DEFAULT_MAX_FILES = 20
        const val DEFAULT_MAX_BYTES = 500L * 1024L * 1024L

        private fun safeSegment(value: String): String =
            value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "artifact" }

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
