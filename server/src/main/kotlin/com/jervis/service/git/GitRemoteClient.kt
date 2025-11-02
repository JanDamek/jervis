package com.jervis.service.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Centralized Git remote communication client.
 * Enforces mutex per target host (domain/IP) to prevent SSH server overload.
 * Multiple different hosts can run in parallel.
 * Uses Kotlin Flow for streaming results.
 */
@Component
class GitRemoteClient {
    // Mutex per target host (domain or IP address)
    private val hostMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Clone repository with automatic retry for communication errors.
     * Returns Flow with progress updates.
     */
    fun clone(
        repoUrl: String,
        targetPath: Path,
        branch: String = "main",
        envVars: Map<String, String> = emptyMap(),
        sparseCheckoutPath: String? = null,
    ): Flow<GitOperationResult> =
        flow {
            val host = extractHost(repoUrl)
            val mutex = hostMutexes.getOrPut(host) { Mutex() }

            mutex.withLock {
                emit(GitOperationResult.Started("clone", repoUrl))

                if (sparseCheckoutPath != null) {
                    // Sparse checkout for mono-repo subdirectory
                    executeWithRetry(
                        operation = "clone",
                        repoUrl = repoUrl,
                        command = listOf("git", "clone", "--no-checkout", repoUrl, targetPath.toString()),
                        workingDir = null,
                        envVars = envVars,
                    ).collect { emit(it) }

                    executeWithRetry(
                        operation = "sparse-checkout-init",
                        repoUrl = repoUrl,
                        command = listOf("git", "sparse-checkout", "init", "--cone"),
                        workingDir = targetPath,
                        envVars = envVars,
                    ).collect { emit(it) }

                    executeWithRetry(
                        operation = "sparse-checkout-set",
                        repoUrl = repoUrl,
                        command = listOf("git", "sparse-checkout", "set", sparseCheckoutPath),
                        workingDir = targetPath,
                        envVars = envVars,
                    ).collect { emit(it) }

                    executeWithRetry(
                        operation = "checkout",
                        repoUrl = repoUrl,
                        command = listOf("git", "checkout", branch),
                        workingDir = targetPath,
                        envVars = envVars,
                    ).collect { emit(it) }
                } else {
                    // Full clone
                    executeWithRetry(
                        operation = "clone",
                        repoUrl = repoUrl,
                        command = listOf("git", "clone", "--branch", branch, repoUrl, targetPath.toString()),
                        workingDir = null,
                        envVars = envVars,
                    ).collect { emit(it) }
                }

                emit(GitOperationResult.Completed("clone", repoUrl))
            }
        }

    /**
     * Fetch latest changes from remote repository.
     */
    fun fetch(
        repoUrl: String,
        workingDir: Path,
        branch: String = "main",
        envVars: Map<String, String> = emptyMap(),
    ): Flow<GitOperationResult> =
        flow {
            val host = extractHost(repoUrl)
            val mutex = hostMutexes.getOrPut(host) { Mutex() }

            mutex.withLock {
                emit(GitOperationResult.Started("fetch", repoUrl))

                executeWithRetry(
                    operation = "fetch",
                    repoUrl = repoUrl,
                    command = listOf("git", "fetch", "origin", branch),
                    workingDir = workingDir,
                    envVars = envVars,
                ).collect { emit(it) }

                emit(GitOperationResult.Completed("fetch", repoUrl))
            }
        }

    /**
     * Pull latest changes from remote repository.
     */
    fun pull(
        repoUrl: String,
        workingDir: Path,
        branch: String = "main",
        envVars: Map<String, String> = emptyMap(),
    ): Flow<GitOperationResult> =
        flow {
            val host = extractHost(repoUrl)
            val mutex = hostMutexes.getOrPut(host) { Mutex() }

            mutex.withLock {
                emit(GitOperationResult.Started("pull", repoUrl))

                executeWithRetry(
                    operation = "fetch",
                    repoUrl = repoUrl,
                    command = listOf("git", "fetch", "origin"),
                    workingDir = workingDir,
                    envVars = envVars,
                ).collect { emit(it) }

                executeWithRetry(
                    operation = "pull",
                    repoUrl = repoUrl,
                    command = listOf("git", "pull", "origin", branch),
                    workingDir = workingDir,
                    envVars = envVars,
                ).collect { emit(it) }

                emit(GitOperationResult.Completed("pull", repoUrl))
            }
        }

    private fun executeWithRetry(
        operation: String,
        repoUrl: String,
        command: List<String>,
        workingDir: Path?,
        envVars: Map<String, String>,
        maxAttempts: Int = 3,
    ): Flow<GitOperationResult> =
        flow {
            var attempt = 0
            var lastError: Throwable? = null

            while (attempt < maxAttempts) {
                try {
                    if (attempt > 0) {
                        val delayMs = minOf(5000L * attempt, 30000L)
                        emit(GitOperationResult.Retry(operation, repoUrl, attempt, delayMs))
                        delay(delayMs)
                    }

                    val output = executeCommand(command, workingDir, envVars)
                    emit(GitOperationResult.Success(operation, repoUrl, output))
                    return@flow
                } catch (e: Exception) {
                    lastError = e
                    if (isRetryableError(e) && attempt < maxAttempts - 1) {
                        emit(GitOperationResult.Failed(operation, repoUrl, e.message ?: "Unknown error", attempt + 1))
                        attempt++
                    } else {
                        throw e
                    }
                }
            }

            throw lastError ?: RuntimeException("Git $operation failed after $maxAttempts attempts")
        }

    private suspend fun executeCommand(
        command: List<String>,
        workingDir: Path?,
        envVars: Map<String, String>,
    ): String =
        withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(command)
            workingDir?.let { processBuilder.directory(it.toFile()) }

            processBuilder.environment().putAll(envVars)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Command failed with exit code $exitCode: $output")
            }

            output
        }

    private fun isRetryableError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("kex_exchange_identification", ignoreCase = true) ||
            message.contains("Connection closed", ignoreCase = true) ||
            message.contains("banner exchange", ignoreCase = true) ||
            message.contains("Operation timed out", ignoreCase = true) ||
            message.contains("Connection timed out", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("Connection reset", ignoreCase = true)
    }

    /**
     * Extract host from Git URL (supports both HTTPS and SSH formats).
     * Examples:
     * - https://github.com/user/repo.git -> github.com
     * - git@github.com:user/repo.git -> github.com
     * - ssh://git@gitlab.com/user/repo.git -> gitlab.com
     */
    private fun extractHost(repoUrl: String): String =
        when {
            repoUrl.startsWith("git@") -> {
                // SSH format: git@host:path
                repoUrl.substringAfter("git@").substringBefore(":")
            }

            repoUrl.startsWith("ssh://") || repoUrl.startsWith("https://") || repoUrl.startsWith("http://") -> {
                // Standard URI format
                runCatching { URI(repoUrl).host }.getOrNull() ?: repoUrl
            }

            else -> {
                // Fallback - use whole URL as key
                logger.warn { "Unable to parse Git URL format: $repoUrl, using full URL as mutex key" }
                repoUrl
            }
        }
}

/**
 * Result types for Git operations using sealed hierarchy.
 */
sealed class GitOperationResult {
    data class Started(
        val operation: String,
        val repoUrl: String,
    ) : GitOperationResult()

    data class Success(
        val operation: String,
        val repoUrl: String,
        val output: String,
    ) : GitOperationResult()

    data class Failed(
        val operation: String,
        val repoUrl: String,
        val error: String,
        val attempt: Int,
    ) : GitOperationResult()

    data class Retry(
        val operation: String,
        val repoUrl: String,
        val attempt: Int,
        val delayMs: Long,
    ) : GitOperationResult()

    data class Completed(
        val operation: String,
        val repoUrl: String,
    ) : GitOperationResult()
}
