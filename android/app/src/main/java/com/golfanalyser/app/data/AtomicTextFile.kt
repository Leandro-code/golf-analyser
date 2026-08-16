package com.golfanalyser.app.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicTextFile {
    fun write(destination: File, content: String) {
        val staged = File(
            destination.parentFile,
            ".${destination.name}.${Thread.currentThread().id}.pending",
        )
        try {
            staged.outputStream().buffered().use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            try {
                Files.move(
                    staged.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staged.delete()
        }
    }

    fun <T> read(
        file: File,
        attempts: Int = 5,
        retryDelayMs: Long = 40L,
        sleep: (Long) -> Unit = Thread::sleep,
        decode: (String) -> T,
    ): T {
        require(attempts > 0)
        var latestFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val content = file.readText()
                if (content.isBlank()) error("${file.name} is temporarily empty.")
                return decode(content)
            } catch (throwable: Throwable) {
                latestFailure = throwable
                if (attempt < attempts - 1) sleep(retryDelayMs)
            }
        }
        throw latestFailure ?: error("Unable to read ${file.name}.")
    }
}
