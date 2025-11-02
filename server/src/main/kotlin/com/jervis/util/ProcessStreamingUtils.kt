package com.jervis.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Utility for streaming process execution with proper coroutine support.
 * Provides real-time output streaming and reactive process management.
 */
object ProcessStreamingUtils {
    /**
     * Result of process execution containing exit code and output.
     */
    data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val isSuccess: Boolean = exitCode == 0,
    )

    /**
     * Configuration for process execution.
     */
    data class ProcessConfig(
        val command: String,
        val workingDirectory: File? = null,
        val timeoutSeconds: Long = 30000,
        val environment: Map<String, String> = emptyMap(),
    )

    /**
     * Executes a process with streaming output support.
     * Returns a Flow that emits output lines as they are produced.
     *
     * @param config Process configuration
     * @return Flow of output lines
     */
    fun runProcessStreaming(config: ProcessConfig): Flow<String> =
        flow {
            withContext(Dispatchers.IO) {
                try {
                    logger.debug { "[DEBUG_LOG] Starting process: ${config.command}" }

                    val processBuilder =
                        ProcessBuilder().apply {
                            if (System.getProperty("os.name").lowercase().contains("win")) {
                                command("cmd", "/c", config.command)
                            } else {
                                command("sh", "-c", config.command)
                            }

                            config.workingDirectory?.let { directory(it) }
                            redirectErrorStream(true)

                            // Add environment variables
                            if (config.environment.isNotEmpty()) {
                                environment().putAll(config.environment)
                            }
                        }

                    val process = processBuilder.start()

                    // Read output line by line
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            emit(line)
                        }
                    }

                    // Wait for process completion with timeout
                    val completed = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)

                    if (!completed) {
                        process.destroyForcibly()
                        throw ProcessTimeoutException("Process timed out after ${config.timeoutSeconds} seconds")
                    }

                    val exitCode = process.exitValue()
                    logger.debug { "[DEBUG_LOG] Process completed with exit code: $exitCode" }

                    if (exitCode != 0) {
                        throw ProcessExecutionException("Process failed with exit code $exitCode")
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[DEBUG_LOG] Process execution failed: ${e.message}" }
                    throw e
                }
            }
        }

    /**
     * Executes a process and returns the complete result.
     * This is a convenience method for cases where streaming is not needed.
     *
     * @param config Process configuration
     * @return ProcessResult containing exit code and complete output
     */
    suspend fun runProcess(config: ProcessConfig): ProcessResult =
        withContext(Dispatchers.IO) {
            try {
                logger.debug { "[DEBUG_LOG] Starting process: ${config.command}" }

                val processBuilder =
                    ProcessBuilder().apply {
                        if (System.getProperty("os.name").lowercase().contains("win")) {
                            command("cmd", "/c", config.command)
                        } else {
                            command("sh", "-c", config.command)
                        }

                        config.workingDirectory?.let { directory(it) }
                        redirectErrorStream(true)

                        // Add environment variables
                        if (config.environment.isNotEmpty()) {
                            environment().putAll(config.environment)
                        }
                    }

                val process = processBuilder.start()

                // Wait for process completion with timeout
                val completed = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)

                if (!completed) {
                    process.destroyForcibly()
                    return@withContext ProcessResult(
                        exitCode = -1,
                        output = "Process timed out after ${config.timeoutSeconds} seconds",
                    )
                }

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                logger.debug { "[DEBUG_LOG] Process completed with exit code: $exitCode" }

                ProcessResult(
                    exitCode = exitCode,
                    output = output,
                )
            } catch (e: Exception) {
                logger.error(e) { "[DEBUG_LOG] Process execution failed: ${e.message}" }
                ProcessResult(
                    exitCode = -1,
                    output = "Process execution failed: ${e.message}",
                )
            }
        }
}

/**
 * Exception thrown when a process execution fails.
 */
class ProcessExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when a process times out.
 */
class ProcessTimeoutException(
    message: String,
) : Exception(message)
