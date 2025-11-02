package com.jervis.joern.domain

import java.nio.file.Path

data class RunResult(
    val stdout: String,
    val stderr: String?,
    val exitCode: Int,
)

interface JoernRunner {
    suspend fun run(
        query: String,
        workingDir: Path? = null,
    ): RunResult
}
