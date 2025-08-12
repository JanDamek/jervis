package com.jervis.service.agent

import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.rag.RagContextManager
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Toolbox for the Agent.
 *
 * This service provides a collection of tools that the agent can use to interact
 * with the system and external resources. Each tool has a specific purpose and
 * can be invoked by the agent to perform actions.
 */
@Service
class AgentToolbox(
    private val llmCoordinator: LlmCoordinator,
    private val workingMemory: WorkingMemory,
    private val ragContextManager: RagContextManager,
) {
    private val logger = KotlinLogging.logger {}

    // Map of tool name to tool implementation
    private val tools = mutableMapOf<String, AgentTool>()

    // Initialize the toolbox with default tools
    init {
        registerDefaultTools()
    }

    /**
     * Register a tool in the toolbox.
     *
     * @param tool The tool to register
     */
    fun registerTool(tool: AgentTool) {
        logger.info { "Registering tool: ${tool.name}" }
        tools[tool.name] = tool
    }

    /**
     * Get a tool by name.
     *
     * @param name The name of the tool
     * @return The tool, or null if not found
     */
    fun getTool(name: String): AgentTool? = tools[name]

    /**
     * Get all available tools.
     *
     * @return A list of all registered tools
     */
    fun getAllTools(): List<AgentTool> = tools.values.toList()

    /**
     * Get the RAG context manager.
     *
     * @return The RAG context manager instance
     */
    fun getRagContextManager(): RagContextManager = ragContextManager

    /**
     * Execute a tool by name.
     *
     * @param name The name of the tool to execute
     * @param params The parameters for the tool
     * @param taskId The ID of the current task
     * @return The result of the tool execution
     */
    fun executeTool(
        name: String,
        params: Map<String, Any>,
        taskId: String,
    ): ToolResult {
        logger.info { "Executing tool: $name with parameters: $params" }

        val tool = tools[name]
        if (tool == null) {
            logger.error { "Tool not found: $name" }
            return ToolResult(
                success = false,
                output = "Tool not found: $name",
                error = "Tool not found: $name",
            )
        }

        return try {
            // Record the tool execution in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "tool_execution",
                value = "Executing tool: $name with parameters: $params",
                type = MemoryEntryType.NOTE,
            )

            // Execute the tool
            val result = tool.execute(params, taskId)

            // Record the result in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "tool_result",
                value = "Tool $name result: ${result.output}",
                type = MemoryEntryType.RESULT,
            )

            result
        } catch (e: Exception) {
            logger.error(e) { "Error executing tool $name: ${e.message}" }

            // Record the error in working memory
            workingMemory.addEntry(
                taskId = taskId,
                key = "tool_error",
                value = "Error executing tool $name: ${e.message}",
                type = MemoryEntryType.ERROR,
            )

            ToolResult(
                success = false,
                output = "Error executing tool: ${e.message}",
                error = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * Register the default tools in the toolbox.
     */
    private fun registerDefaultTools() {
        // File System Tools
        registerTool(ReadFileTool(this))
        registerTool(WriteFileTool(this))
        registerTool(ListDirectoryTool(this))

        // Memory Tools
        registerTool(SearchMemoryTool(this))
        registerTool(CreateMemoryTool(this))

        // Terminal Tools
        registerTool(ExecuteCommandTool(this))

        // LLM Tools
        registerTool(QueryLlmTool(this, llmCoordinator))

        // Working Memory Tools
        registerTool(ReadWorkingMemoryTool(this, workingMemory))
        registerTool(WriteWorkingMemoryTool(this, workingMemory))
    }
}

/**
 * Interface for agent tools.
 */
interface AgentTool {
    /**
     * The name of the tool.
     */
    val name: String

    /**
     * A description of what the tool does.
     */
    val description: String

    /**
     * A description of the parameters required by the tool.
     */
    val parameterDescription: String

    /**
     * Execute the tool with the given parameters.
     *
     * @param params The parameters for the tool
     * @param taskId The ID of the current task
     * @param type The type of RAG document (optional, defaults to UNKNOWN)
     * @return The result of the tool execution
     */
    fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType = RagDocumentType.UNKNOWN,
    ): ToolResult
}

/**
 * Result of a tool execution.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Tool for reading files.
 */
class ReadFileTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "read_file"
    override val description = "Reads the content of a file"
    override val parameterDescription = "path: The path to the file to read"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val path =
            params["path"] as? String
                ?: return ToolResult(false, "Missing parameter: path", "Missing parameter: path")

        return try {
            val content = java.io.File(path).readText()
            ToolResult(true, content)
        } catch (e: Exception) {
            ToolResult(false, "Error reading file: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for writing to files.
 */
class WriteFileTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "write_file"
    override val description = "Writes content to a file"
    override val parameterDescription = "path: The path to the file to write, content: The content to write"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val path =
            params["path"] as? String
                ?: return ToolResult(false, "Missing parameter: path", "Missing parameter: path")
        val content =
            params["content"] as? String
                ?: return ToolResult(false, "Missing parameter: content", "Missing parameter: content")

        return try {
            java.io.File(path).writeText(content)
            ToolResult(true, "File written successfully: $path")
        } catch (e: Exception) {
            ToolResult(false, "Error writing file: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for listing directory contents.
 */
class ListDirectoryTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "list_directory"
    override val description = "Lists the contents of a directory"
    override val parameterDescription = "path: The path to the directory to list"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val path =
            params["path"] as? String
                ?: return ToolResult(false, "Missing parameter: path", "Missing parameter: path")

        return try {
            val directory = java.io.File(path)
            if (!directory.isDirectory) {
                return ToolResult(false, "Not a directory: $path", "Not a directory: $path")
            }

            val files = directory.listFiles()?.map { it.name } ?: emptyList()
            ToolResult(true, files.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Error listing directory: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for searching memory.
 */
class SearchMemoryTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "search_memory"
    override val description = "Searches for items in the long-term memory"
    override val parameterDescription =
        "query: The search query, project_id: The ID of the project, limit: Maximum number of results"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val query =
            params["query"] as? String
                ?: return ToolResult(false, "Missing parameter: query", "Missing parameter: query")
        val projectId =
            params["project_id"] as? ObjectId
                ?: return ToolResult(false, "Missing parameter: project_id", "Missing parameter: project_id")
        val limit = params["limit"] as? Int ?: 10

        return try {
            // Get the project
            val projectService = toolbox.getTool("get_project")
            val projectResult = projectService?.execute(mapOf("project_id" to projectId), taskId)

            if (projectResult == null || !projectResult.success) {
                return ToolResult(false, "Error getting project: ${projectResult?.error}", projectResult?.error)
            }

            val project =
                projectResult.metadata["project"] as? ProjectDocument
                    ?: return ToolResult(false, "Project not found", "Project not found")

            // Search memory using RAG context manager
            val results = runBlocking { toolbox.getRagContextManager().searchMemoryItems(project, query, limit) }

            // Format results
            val formattedResults =
                results.joinToString("\n\n") { doc ->
                    """
                    projectId: ${doc.projectId}
                    DocumentType: ${doc.documentType}
                    RagSource: ${doc.ragSourceType}
                    Content: ${doc.pageContent}
                    """.trimIndent()
                }

            ToolResult(
                success = true,
                output = formattedResults,
                metadata = mapOf("results" to results),
            )
        } catch (e: Exception) {
            ToolResult(false, "Error searching memory: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for creating memory items.
 */
class CreateMemoryTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "create_memory"
    override val description = "Creates a new item in the long-term memory"
    override val parameterDescription =
        "project_id: The ID of the project, title: The title of the memory item, content: The content of the memory item, type: The type of memory item (NOTE, DECISION, PLAN, HISTORY)"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val projectId =
            params["project_id"] as? ObjectId
                ?: return ToolResult(false, "Missing parameter: project_id", "Missing parameter: project_id")
        val title =
            params["title"] as? String
                ?: return ToolResult(false, "Missing parameter: title", "Missing parameter: title")
        val content =
            params["content"] as? String
                ?: return ToolResult(false, "Missing parameter: content", "Missing parameter: content")
        params["type"] as? String ?: "NOTE"

        return try {
            // Get the project
            val projectService = toolbox.getTool("get_project")
            val projectResult = projectService?.execute(mapOf("project_id" to projectId), taskId)

            if (projectResult == null || !projectResult.success) {
                return ToolResult(false, "Error getting project: ${projectResult?.error}", projectResult?.error)
            }

            projectResult.metadata["project"] as? ProjectDocument
                ?: return ToolResult(false, "Project not found", "Project not found")

            val documents = RagDocument(projectId, type, RagSourceType.AGENT, content)

            ToolResult(
                success = true,
                output = "Memory item created successfully: $title",
                metadata = mapOf("documents" to documents),
            )
        } catch (e: Exception) {
            ToolResult(false, "Error creating memory item: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for executing terminal commands.
 */
class ExecuteCommandTool(
    private val toolbox: AgentToolbox,
) : AgentTool {
    override val name = "execute_command"
    override val description = "Executes a command in the terminal"
    override val parameterDescription = "command: The command to execute"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val command =
            params["command"] as? String
                ?: return ToolResult(false, "Missing parameter: command", "Missing parameter: command")

        // Security check - in a real implementation, you would have more robust security measures
        if (command.contains("rm") || command.contains("sudo") || command.contains(";")) {
            return ToolResult(
                false,
                "Command rejected for security reasons",
                "Command rejected for security reasons",
            )
        }

        return try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            while (errorReader.readLine().also { line = it } != null) {
                output.append("ERROR: ").append(line).append("\n")
            }

            val exitCode = process.waitFor()
            output.append("Exit code: $exitCode")

            ToolResult(
                success = exitCode == 0,
                output = output.toString(),
                error = if (exitCode != 0) "Command exited with code $exitCode" else null,
            )
        } catch (e: Exception) {
            ToolResult(false, "Error executing command: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for querying the LLM.
 */
class QueryLlmTool(
    private val toolbox: AgentToolbox,
    private val llmCoordinator: LlmCoordinator,
) : AgentTool {
    override val name = "query_llm"
    override val description = "Queries the LLM with a prompt"
    override val parameterDescription = "prompt: The prompt to send to the LLM, context: Optional context for the query"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val prompt =
            params["prompt"] as? String
                ?: return ToolResult(false, "Missing parameter: prompt", "Missing parameter: prompt")
        val context = params["context"] as? String ?: ""

        return try {
            val response = llmCoordinator.processQueryBlocking(prompt, context)

            ToolResult(
                success = true,
                output = response.answer,
                metadata =
                    mapOf(
                        "model" to response.model,
                        "promptTokens" to response.promptTokens,
                        "completionTokens" to response.completionTokens,
                        "totalTokens" to response.totalTokens,
                    ),
            )
        } catch (e: Exception) {
            ToolResult(false, "Error querying LLM: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for reading from working memory.
 */
class ReadWorkingMemoryTool(
    private val toolbox: AgentToolbox,
    private val workingMemory: WorkingMemory,
) : AgentTool {
    override val name = "read_working_memory"
    override val description = "Reads entries from the working memory"
    override val parameterDescription =
        "task_id: The ID of the task, key: Optional key to filter by, type: Optional type to filter by"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val targetTaskId = params["task_id"] as? String ?: taskId
        val key = params["key"] as? String
        val typeStr = params["type"] as? String

        return try {
            val entries =
                when {
                    key != null -> workingMemory.getEntriesByKey(targetTaskId, key)
                    typeStr != null -> {
                        val type =
                            try {
                                MemoryEntryType.valueOf(typeStr.uppercase())
                            } catch (e: Exception) {
                                return ToolResult(
                                    false,
                                    "Invalid memory entry type: $typeStr",
                                    "Invalid memory entry type: $typeStr",
                                )
                            }
                        workingMemory.getEntriesByType(targetTaskId, type)
                    }

                    else -> workingMemory.getEntries(targetTaskId)
                }

            if (entries.isEmpty()) {
                return ToolResult(
                    success = true,
                    output =
                        "No entries found in working memory for task $targetTaskId" +
                            (key?.let { " with key $it" } ?: "") +
                            (typeStr?.let { " of type $it" } ?: ""),
                )
            }

            val formattedEntries =
                entries.joinToString("\n\n") { entry ->
                    """
                    Key: ${entry.key}
                    Type: ${entry.type}
                    Value: ${entry.value}
                    Timestamp: ${entry.timestamp}
                    """.trimIndent()
                }

            ToolResult(
                success = true,
                output = formattedEntries,
                metadata = mapOf("entries" to entries),
            )
        } catch (e: Exception) {
            ToolResult(false, "Error reading working memory: ${e.message}", e.message)
        }
    }
}

/**
 * Tool for writing to working memory.
 */
class WriteWorkingMemoryTool(
    private val toolbox: AgentToolbox,
    private val workingMemory: WorkingMemory,
) : AgentTool {
    override val name = "write_working_memory"
    override val description = "Writes an entry to the working memory"
    override val parameterDescription =
        "task_id: The ID of the task, key: The key for the entry, value: The value to store, type: The type of entry (NOTE, THOUGHT, PLAN, RESULT, CONTEXT, ERROR, DECISION)"

    override fun execute(
        params: Map<String, Any>,
        taskId: String,
        type: RagDocumentType,
    ): ToolResult {
        val targetTaskId = params["task_id"] as? String ?: taskId
        val key =
            params["key"] as? String
                ?: return ToolResult(false, "Missing parameter: key", "Missing parameter: key")
        val value =
            params["value"] as? String
                ?: return ToolResult(false, "Missing parameter: value", "Missing parameter: value")
        val typeStr = params["type"] as? String ?: "NOTE"

        return try {
            val type =
                try {
                    MemoryEntryType.valueOf(typeStr.uppercase())
                } catch (e: Exception) {
                    return ToolResult(
                        false,
                        "Invalid memory entry type: $typeStr",
                        "Invalid memory entry type: $typeStr",
                    )
                }

            val entry = workingMemory.addEntry(targetTaskId, key, value, type)

            ToolResult(
                success = true,
                output = "Entry added to working memory: ${entry.key}",
                metadata = mapOf("entry" to entry),
            )
        } catch (e: Exception) {
            ToolResult(false, "Error writing to working memory: ${e.message}", e.message)
        }
    }
}
