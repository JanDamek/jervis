package com.jervis.koog.tools.indexing

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.IngestRequest
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.internal.graphdb.GraphDBService
import com.jervis.knowledgebase.internal.graphdb.model.GraphEdge
import com.jervis.knowledgebase.internal.graphdb.model.GraphNode
import com.jervis.service.project.ProjectService
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.types.SourceUrn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.relativeTo

/**
 * Specialized tools for Git and Code structure indexing.
 * Converts Joern CPG structure into GraphDB nodes and RAG entries.
 */
@LLMDescription("Tools for automated Git and Code structure indexing into Knowledgebase.")
class GitIndexingTools(
    private val task: TaskDocument,
    private val joernClient: IJoernClient,
    private val projectService: ProjectService,
    private val directoryStructureService: DirectoryStructureService,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val promptExecutor: ai.koog.prompt.executor.model.PromptExecutor,
) : ToolSet {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    @Tool
    @LLMDescription(
        "Analyze and index the whole project structure using Joern. Creates Class and Method nodes in GraphDB with RAG descriptions.",
    )
    suspend fun indexProjectStructure(): IndexingResult {
        val projectId = task.projectId ?: return IndexingResult(false, "ProjectId is required")
        val project = projectService.getProjectById(projectId)
        val projectPath = directoryStructureService.projectDir(project)

        logger.info { "Starting full project structure indexing for ${project.name}" }

        try {
            // 1. Package and run Joern to get JSON structure
            val zipBase64 = packageProject(projectPath)
            val query =
                """
                val classes = cpg.typeDecl.filter(t => !t.isExternal).map(t => Map(
                    "fullName" -> t.fullName,
                    "name" -> t.name,
                    "filename" -> t.filename,
                    "methods" -> t.method.map(m => Map(
                        "name" -> m.name,
                        "fullName" -> m.fullName,
                        "signature" -> m.signature,
                        "code" -> m.code.take(500)
                    )).l
                )).toJson
                println(classes)
                """.trimIndent()

            val joernResponse = joernClient.run(JoernQueryDto(query, zipBase64))
            if (joernResponse.exitCode != 0) {
                return IndexingResult(false, "Joern failed: ${joernResponse.stderr}")
            }

            val rawJson = joernResponse.stdout
            // Find the JSON array in the output
            val jsonStart = rawJson.indexOf("[")
            val jsonEnd = rawJson.lastIndexOf("]")
            if (jsonStart == -1 || jsonEnd == -1) {
                return IndexingResult(false, "No JSON structure found in Joern output")
            }
            val jsonText = rawJson.substring(jsonStart, jsonEnd + 1)

            val classes =
                runCatching {
                    json.decodeFromString<List<JoernClassDto>>(jsonText)
                }.getOrElse {
                    logger.error(it) { "Failed to parse Joern JSON" }
                    return IndexingResult(false, "Failed to parse Joern JSON: ${it.message}")
                }

            // 2. For each class/method, generate description and store
            for (clazz in classes) {
                val classKey = "class:${clazz.fullName}"
                val classSourceUrn = SourceUrn.git(projectId, "path:${clazz.filename}")

                // Generate RAG description for class via LLM
                val classDesc = generateDescription("class", clazz.name, clazz.fullName, "")

                val ingestResult =
                    knowledgeService.ingest(
                        IngestRequest(
                            clientId = task.clientId,
                            projectId = task.projectId,
                            sourceUrn = classSourceUrn,
                            kind = "CODE_CLASS",
                            content = "Class: ${clazz.fullName}\n\n$classDesc",
                            metadata = mapOf("fullName" to clazz.fullName, "filename" to clazz.filename),
                        ),
                    )

                graphDBService.upsertNode(
                    task.clientId,
                    GraphNode(
                        key = classKey,
                        entityType = "class",
                        ragChunks = emptyList(), // Will be linked by ingest
                    ),
                )

                for (method in clazz.methods) {
                    val methodKey = "method:${method.fullName}"
                    val methodDesc = generateDescription("method", method.name, method.fullName, method.code)

                    knowledgeService.ingest(
                        IngestRequest(
                            clientId = task.clientId,
                            projectId = task.projectId,
                            sourceUrn = classSourceUrn, // use class file as source
                            kind = "CODE_METHOD",
                            content =
                                "Method: ${method.fullName}\nSignature: ${method.signature}\n\n" +
                                    "$methodDesc\n\nCode snippet:\n${method.code}",
                            metadata = mapOf("fullName" to method.fullName, "className" to clazz.fullName),
                        ),
                    )

                    graphDBService.upsertNode(
                        task.clientId,
                        GraphNode(
                            key = methodKey,
                            entityType = "method",
                            ragChunks = emptyList(),
                        ),
                    )

                    // Link method to class
                    graphDBService.upsertEdge(
                        task.clientId,
                        GraphEdge(
                            edgeType = "has_method",
                            fromKey = classKey,
                            toKey = methodKey,
                        ),
                    )
                }
            }

            return IndexingResult(
                true,
                "Project structure indexed: ${classes.size} classes, ${classes.sumOf { it.methods.size }} methods",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to index project structure" }
            return IndexingResult(false, e.message)
        }
    }

    private suspend fun generateDescription(
        type: String,
        name: String,
        fullName: String,
        code: String,
    ): String {
        val promptText =
            """
            Describe the purpose and functionality of the following $type:
            Name: $name
            Full Name: $fullName
            ${if (code.isNotBlank()) "Code snippet (first 500 chars):\n$code" else ""}
            
            Write a concise 2-3 sentence description in Czech.
            """.trimIndent()

        return runCatching {
            val response =
                promptExecutor.execute(
                    prompt =
                        ai.koog.prompt.dsl.prompt("desc-gen") {
                            system { +"You are a senior software architect. Provide clear and technical descriptions of code entities." }
                            user { +promptText }
                        },
                    model =
                        ai.koog.prompt.llm.LLModel(
                            provider = ai.koog.prompt.llm.LLMProvider.Ollama,
                            id = "qwen2.5:32b",
                            contextLength = 32768,
                            capabilities = emptyList(),
                        ),
                )
            response.first().content
        }.getOrDefault("Popis pro $type $name.")
    }

    @Serializable
    data class JoernClassDto(
        val name: String,
        val fullName: String,
        val filename: String,
        val methods: List<JoernMethodDto> = emptyList(),
    )

    @Serializable
    data class JoernMethodDto(
        val name: String,
        val fullName: String,
        val signature: String,
        val code: String,
    )

    @Tool
    @LLMDescription("Index a specific Git commit, linking it to changed code entities (classes, methods).")
    suspend fun indexCommit(
        @LLMDescription("Commit hash") commitHash: String,
        @LLMDescription("Commit message") message: String,
        @LLMDescription("Diff content") diff: String,
    ): IndexingResult {
        logger.info { "Indexing commit $commitHash" }

        val sourceUrn = SourceUrn.git(task.projectId!!, commitHash)

        // 1. Store commit in RAG
        knowledgeService.ingest(
            IngestRequest(
                clientId = task.clientId,
                projectId = task.projectId,
                sourceUrn = sourceUrn,
                kind = "GIT_COMMIT",
                content = "Commit: $commitHash\nAuthor: ${task.correlationId}\n\n$message\n\n$diff",
            ),
        )

        // 2. Store in GraphDB
        val commitKey = "git:$commitHash"
        graphDBService.upsertNode(
            task.clientId,
            GraphNode(
                key = commitKey,
                entityType = "commit",
                ragChunks = emptyList(), // Will be updated by ingest above
            ),
        )

        // 3. Find affected classes from diff (simplified)
        val affectedClasses = extractAffectedClasses(diff)
        for (className in affectedClasses) {
            val classKey = "class:$className"
            graphDBService.upsertEdge(
                task.clientId,
                GraphEdge(
                    edgeType = "modified",
                    fromKey = commitKey,
                    toKey = classKey,
                ),
            )
        }

        return IndexingResult(true, "Commit $commitHash indexed with ${affectedClasses.size} links")
    }

    private fun extractAffectedClasses(diff: String): List<String> {
        // Mock extraction
        return emptyList()
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
        return name.contains(".git/") || name.contains("build/") || name.contains("target/")
    }

    @Serializable
    data class IndexingResult(
        val success: Boolean,
        val message: String? = null,
    )
}
