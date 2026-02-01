package com.jervis.koog.tools.analysis

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.rpc.withRpcRetry
import com.jervis.entity.TaskDocument
import com.jervis.service.project.ProjectService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.relativeTo

/**
 * Joern tools for deep static code analysis (graph-based code search).
 */
@LLMDescription("Tools for static code analysis using Joern (CPG)")
class JoernTools(
    private val task: TaskDocument,
    private val joernClient: IJoernClient,
    private val projectService: ProjectService,
    private val directoryStructureService: DirectoryStructureService,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Execute a Joern query on the current project's codebase.
        Joern uses CPG (Code Property Graph) and Scala-based DSL.
        
        **Examples of queries:**
        - Find all calls to a method: "cpg.call(\"methodName\").l"
        - Find all definitions of a variable: "cpg.identifier(\"varName\").l"
        - Find data flow from source to sink: "val source = cpg.parameter.name(\"input\"); val sink = cpg.call(\"exec\"); sink.reachableByFlows(source).p"
        
        This tool will package the project, send it to the Joern service, and return the result.
        """,
    )
    suspend fun query(
        @LLMDescription("Joern query in Scala DSL")
        query: String,
    ): JoernQueryResult {
        val projectId =
            task.projectId ?: return JoernQueryResult(
                success = false,
                error = "ProjectId is required for Joern analysis",
            )

        return try {
            val project =
                projectService.getProjectById(projectId)
            val projectPath = directoryStructureService.projectDir(project)

            logger.info { "Preparing Joern query for project ${project.name} (path: $projectPath)" }

            val zipBase64 = packageProject(projectPath)

            val request =
                JoernQueryDto(
                    query = query,
                    projectZipBase64 = zipBase64,
                )

            val result =
                withRpcRetry(
                    name = "Joern",
                    reconnect = { reconnectHandler.reconnectJoern() },
                ) {
                    joernClient.run(request)
                }

            JoernQueryResult(
                success = result.exitCode == 0,
                stdout = result.stdout,
                stderr = result.stderr,
                exitCode = result.exitCode,
            )
        } catch (e: Exception) {
            logger.error(e) { "Joern query failed" }
            JoernQueryResult(success = false, error = e.message)
        }
    }

    private fun packageProject(projectPath: Path): String {
        val outputStream = ByteArrayOutputStream()
        ZipOutputStream(outputStream).use { zos ->
            Files.walk(projectPath).forEach { path ->
                if (Files.isRegularFile(path)) {
                    val relativePath = path.relativeTo(projectPath)
                    if (!shouldSkip(relativePath)) {
                        val entry = ZipEntry(relativePath.toString())
                        zos.putNextEntry(entry)
                        Files.copy(path, zos)
                        zos.closeEntry()
                    }
                }
            }
        }
        return Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }

    private fun shouldSkip(path: Path): Boolean {
        val name = path.toString().lowercase()
        return name.contains(".git/") ||
            name.contains("build/") ||
            name.contains("target/") ||
            name.contains("node_modules/") ||
            name.endsWith(".jar") ||
            name.endsWith(".bin")
    }

    @Serializable
    data class JoernQueryResult(
        val success: Boolean,
        val stdout: String? = null,
        val stderr: String? = null,
        val exitCode: Int? = null,
        val error: String? = null,
    )
}
