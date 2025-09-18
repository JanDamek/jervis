package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.util.ProcessStreamingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File

@Service
class GitTool(
    private val llmGateway: LlmGateway,
    private val timeoutsProperties: TimeoutsProperties,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    companion object {
        // Safe pattern for Git names (branches, remotes, etc.)
        private val SAFE_NAME = Regex("^[a-zA-Z0-9._/@+-]+$")

        private fun requireSafeName(
            value: String,
            field: String,
        ) {
            require(SAFE_NAME.matches(value)) { "Invalid $field: only letters, numbers, . _ / @ + - are allowed" }
        }

        private fun sanitizeCommitMessage(message: String): String = message.replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
    }

    override val name: PromptTypeEnum = PromptTypeEnum.GIT

    @Serializable
    data class GitParams(
        val operation: String = "",
        val parameters: Map<String, String> = emptyMap(),
        val finalPrompt: String? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): GitParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.GIT,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = GitParams(),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val projectDir = File(context.projectDocument.path)

        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ToolResult.error("Project path does not exist or is not a directory: $projectDir")
        }

        val parsed =
            try {
                parseTaskDescription(taskDescription, context, stepContext)
            } catch (e: Exception) {
                return ToolResult.error("Invalid Git parameters: ${e.message}", "Git parameter parsing failed")
            }

        if (parsed.operation.isBlank()) {
            return ToolResult.error("Git operation cannot be empty")
        }

        return withContext(Dispatchers.IO) {
            try {
                executeGitOperation(parsed, projectDir)
            } catch (e: Exception) {
                logger.error(e) { "Git operation failed: ${parsed.operation}" }
                ToolResult.error("Git operation failed: ${e.message}")
            }
        }
    }

    private suspend fun executeGitOperation(
        params: GitParams,
        projectDir: File,
    ): ToolResult {
        val command = buildGitCommand(params)

        val processResult =
            ProcessStreamingUtils.runProcess(
                ProcessStreamingUtils.ProcessConfig(
                    command = command,
                    workingDirectory = projectDir,
                    timeoutSeconds = timeoutsProperties.mcp.terminalToolTimeoutSeconds,
                ),
            )

        val output =
            buildString {
                appendLine("Git Operation: ${params.operation}")
                appendLine("Command: $command")
                appendLine("Working Directory: ${projectDir.absolutePath}")
                appendLine()

                if (processResult.isSuccess) {
                    appendLine("✅ Git operation completed successfully")
                    appendLine()
                    appendLine("Output:")
                    appendLine(processResult.output)
                } else {
                    appendLine("❌ Git operation failed with exit code ${processResult.exitCode}")
                    appendLine()
                    appendLine("Error Output:")
                    appendLine(processResult.output)
                }
            }

        return if (processResult.isSuccess) {
            ToolResult.ok(output)
        } else {
            ToolResult.error(output, "Git operation failed")
        }
    }

    private fun buildGitCommand(params: GitParams): String {
        return when (params.operation.lowercase()) {
            "status" -> "git status --porcelain=v1"
            "log" -> {
                val limit = params.parameters["limit"] ?: "10"
                val format = params.parameters["format"] ?: "oneline"
                when (format) {
                    "full" -> "git log -$limit --pretty=format:'%H|%an|%ad|%s' --date=iso"
                    "detailed" -> "git log -$limit --pretty=format:'%h - %an, %ar : %s'"
                    else -> "git log -$limit --oneline"
                }
            }

            "branch" -> {
                val action = params.parameters["action"]
                when (action) {
                    "list" -> "git branch -a"
                    "current" -> "git branch --show-current"
                    "create" -> {
                        val branchName =
                            params.parameters["name"]
                                ?: return "echo 'Error: Branch name is required for create action'"
                        try {
                            requireSafeName(branchName, "branch name")
                        } catch (e: IllegalArgumentException) {
                            return "echo '${e.message}'"
                        }
                        "git checkout -b $branchName"
                    }

                    "checkout" -> {
                        val branchName =
                            params.parameters["name"]
                                ?: return "echo 'Error: Branch name is required for checkout action'"
                        try {
                            requireSafeName(branchName, "branch name")
                        } catch (e: IllegalArgumentException) {
                            return "echo '${e.message}'"
                        }
                        "git checkout $branchName"
                    }

                    else -> "git branch"
                }
            }

            "add" -> {
                val files = params.parameters["files"] ?: "."
                "git add $files"
            }

            "commit" -> {
                val message =
                    params.parameters["message"]
                        ?: return "echo 'Error: Commit message is required'"
                val safeMessage = sanitizeCommitMessage(message)
                "git commit -m \"$safeMessage\""
            }

            "push" -> {
                val remote = params.parameters["remote"] ?: "origin"
                val branch = params.parameters["branch"] ?: ""
                try {
                    requireSafeName(remote, "remote name")
                    if (branch.isNotEmpty()) {
                        requireSafeName(branch, "branch name")
                    }
                } catch (e: IllegalArgumentException) {
                    return "echo '${e.message}'"
                }
                if (branch.isEmpty()) {
                    "git push $remote"
                } else {
                    "git push $remote $branch"
                }
            }

            "pull" -> {
                val remote = params.parameters["remote"] ?: "origin"
                val branch = params.parameters["branch"] ?: ""
                try {
                    requireSafeName(remote, "remote name")
                    if (branch.isNotEmpty()) {
                        requireSafeName(branch, "branch name")
                    }
                } catch (e: IllegalArgumentException) {
                    return "echo '${e.message}'"
                }
                if (branch.isEmpty()) {
                    "git pull $remote"
                } else {
                    "git pull $remote $branch"
                }
            }

            "diff" -> {
                val target = params.parameters["target"] ?: ""
                if (target.isEmpty()) {
                    "git diff"
                } else {
                    "git diff $target"
                }
            }

            "remote" -> {
                val action = params.parameters["action"] ?: "list"
                when (action) {
                    "list" -> "git remote -v"
                    "add" -> {
                        val name = params.parameters["name"] ?: return "echo 'Error: Remote name is required'"
                        val url = params.parameters["url"] ?: return "echo 'Error: Remote URL is required'"
                        try {
                            requireSafeName(name, "remote name")
                            // Basic URL validation - ensure it doesn't contain shell metacharacters
                            require(!url.contains(";") && !url.contains("&") && !url.contains("|") && !url.contains("`")) {
                                "Invalid remote URL: contains shell metacharacters"
                            }
                        } catch (e: IllegalArgumentException) {
                            return "echo '${e.message}'"
                        }
                        "git remote add $name $url"
                    }

                    else -> "git remote"
                }
            }

            else -> "git ${params.operation}"
        }
    }
}
