package com.golfanalyser.app.data

import java.io.File

data class AnalysisStorageUsage(
    val totalBytes: Long,
    val analysisCount: Int,
    val bytesByRunId: Map<String, Long>,
)

internal class AnalysisStorage(private val rootDir: File) {
    init {
        rootDir.mkdirs()
    }

    fun usage(): AnalysisStorageUsage {
        val byRun = rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.associate { it.name to it.treeBytes() }
            .orEmpty()
        return AnalysisStorageUsage(
            totalBytes = byRun.values.sum(),
            analysisCount = byRun.size,
            bytesByRunId = byRun,
        )
    }

    fun deleteAnalysis(runId: String) {
        val target = safeRunDir(runId)
        if (target.exists() && !target.deleteRecursively()) {
            error("Unable to delete analysis $runId.")
        }
    }

    fun deleteAllAnalyses() {
        rootDir.listFiles()?.forEach { target ->
            check(target.parentFile?.canonicalFile == rootDir.canonicalFile) {
                "Refusing to delete data outside the analyses directory."
            }
            if (!target.deleteRecursively()) error("Unable to delete ${target.name}.")
        }
        rootDir.mkdirs()
    }

    private fun safeRunDir(runId: String): File {
        require(runId.isNotBlank() && File(runId).name == runId) { "Invalid analysis id." }
        val target = File(rootDir, runId).canonicalFile
        require(target.parentFile == rootDir.canonicalFile) { "Invalid analysis id." }
        return target
    }

    private fun File.treeBytes(): Long = when {
        isFile -> length()
        isDirectory -> listFiles()?.sumOf { it.treeBytes() } ?: 0L
        else -> 0L
    }
}
