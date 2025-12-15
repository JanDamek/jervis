package com.jervis.coding.service

import com.jervis.coding.configuration.CodingEngineProperties
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?
)

@Service
class CodingEngineService(
    private val properties: CodingEngineProperties
) {
    private val dataRootDir: String get() = properties.dataRootDir

    suspend fun executeOpenHands(request: CodingExecuteRequest): CodingExecuteResponse = withContext(Dispatchers.IO) {
        logger.info { "Executing OpenHands for correlationId=${request.correlationId}" }

        val projectDir = resolveAndValidateProjectDir(request.clientId, request.projectId)

        projectDir.fold(
            onSuccess = { dir ->
                runCatching { executeOpenHandsJob(request, dir) }
                    .map { result ->
                        CodingExecuteResponse(
                            success = result.exitCode == 0,
                            engine = "openhands",
                            summary = when (result.exitCode) {
                                0 -> "OpenHands completed successfully"
                                else -> "OpenHands failed with exit code ${result.exitCode}"
                            },
                            details = result.output,
                            metadata = mapOf(
                                "correlationId" to request.correlationId,
                                "exitCode" to (result.exitCode?.toString() ?: "timeout")
                            )
                        )
                    }
                    .getOrElse { e ->
                        logger.error(e) { "OpenHands execution failed for ${request.correlationId}" }
                        CodingExecuteResponse(
                            success = false,
                            engine = "openhands",
                            summary = "Execution failed: ${e.message}",
                            details = null,
                            metadata = mapOf("error" to (e.message ?: "unknown"))
                        )
                    }
            },
            onFailure = { e ->
                logger.error { "Project directory validation failed: ${e.message}" }
                CodingExecuteResponse(
                    success = false,
                    engine = "openhands",
                    summary = "Validation failed: ${e.message}",
                    details = null,
                    metadata = mapOf("error" to (e.message ?: "validation failed"))
                )
            }
        )
    }

    private fun resolveAndValidateProjectDir(clientId: String, projectId: String?): Result<File> = runCatching {
        val rootDir = File(dataRootDir).canonicalFile
        val projectPath = when (projectId) {
            null -> Path(rootDir.path, clientId).toString()
            else -> Path(rootDir.path, clientId, projectId).toString()
        }

        val projectDir = File(projectPath).canonicalFile

        require(projectDir.absolutePath.startsWith(rootDir.absolutePath)) {
            "Project path is outside of allowed data root directory"
        }

        require(projectDir.exists()) {
            "Project directory does not exist: ${projectDir.absolutePath}"
        }

        projectDir
    }

    private fun executeOpenHandsJob(req: CodingExecuteRequest, projectDir: File): ProcessResult {
        logger.info { "Starting OpenHands in ${projectDir.name}" }

        val command = buildList {
            add("python3")
            add("-m")
            add("openhands.core.main")
            add("--task")
            add(req.taskDescription)
            add("--workspace")
            add(projectDir.absolutePath)
            add("--sandbox-image")
            add(properties.sandboxImage)
            add("--max-iterations")
            add(properties.maxIterations.toString())

            req.extra["model"]?.let { model ->
                add("--model")
                add(model)
            }
        }

        logger.info { "Executing OpenHands: ${command.joinToString(" ")}" }

        val result = executeProcess(command, projectDir, timeoutMinutes = 60)

        return result
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        timeoutMinutes: Long
    ): ProcessResult {
        val process = ProcessBuilder(command).apply {
            directory(workingDir)
            redirectErrorStream(true)
            environment().apply {
                putAll(System.getenv())
                put("DOCKER_HOST", properties.dockerHost)
                put("OLLAMA_API_BASE", properties.ollamaBaseUrl)
            }
        }.start()

        val output = buildString {
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    logger.info { "[OpenHands] $line" }
                    appendLine(line)
                }
            }
        }

        val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

        return if (completed) {
            ProcessResult(output = output, exitCode = process.exitValue())
        } else {
            process.destroyForcibly()
            ProcessResult(output = output, exitCode = null)
        }
    }
}
