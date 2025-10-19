package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.util.ProcessStreamingUtils
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ProjectVersionControlTool(
    private val llmGateway: LlmGateway,
    private val timeoutsProperties: TimeoutsProperties,
    override val promptRepository: PromptRepository,
    private val directoryStructureService: DirectoryStructureService,
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

    override val name: PromptTypeEnum = PromptTypeEnum.PROJECT_VERSION_CONTROL_TOOL

    @Serializable
    data class ProjectVersionControlParams(
        val operation: String = "",
        val parameters: Map<String, String> = emptyMap(),
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): ProjectVersionControlParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PROJECT_VERSION_CONTROL_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = context.quick,
                responseSchema = ProjectVersionControlParams(),
            )

        return llmResponse.result
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeProjectVersionControlOperation(parsed, context)
    }

    private suspend fun executeProjectVersionControlOperation(
        params: ProjectVersionControlParams,
        context: TaskContext,
    ): ToolResult {
        val projectDir =
            directoryStructureService
                .projectGitDir(
                    context.clientDocument.id,
                    context.projectDocument.id,
                ).toFile()
        val command = buildGitCommand(params)

        val processResult =
            ProcessStreamingUtils.runProcess(
                ProcessStreamingUtils.ProcessConfig(
                    command = command,
                    workingDirectory = projectDir,
                    timeoutSeconds = timeoutsProperties.mcp.terminalToolTimeoutSeconds,
                ),
            )

        return if (processResult.isSuccess) {
            val summary = "${params.operation} completed successfully"
            val content =
                buildString {
                    appendLine("Command: $command")
                    appendLine("Working Directory: ${projectDir.absolutePath}")
                    if (processResult.output.isNotBlank()) {
                        appendLine()
                        append(processResult.output)
                    }
                }
            ToolResult.success("GIT", summary, content)
        } else {
            val summary = "${params.operation} failed with exit code ${processResult.exitCode}"
            val content =
                buildString {
                    appendLine("Command: $command")
                    appendLine("Working Directory: ${projectDir.absolutePath}")
                    if (processResult.output.isNotBlank()) {
                        appendLine()
                        append(processResult.output)
                    }
                }
            ToolResult.error("$summary\n\n$content", "Git operation failed")
        }
    }

    private fun buildGitCommand(params: ProjectVersionControlParams): String {
        if (params.operation.isBlank()) {
            return "echo 'Error: Git operation is required. Supported operations: status, log, branch, add, commit, push, pull, diff, remote'"
        }

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

            else -> {
                val sanitizedOp = params.operation.trim()
                if (sanitizedOp.contains(" ") || !SAFE_NAME.matches(sanitizedOp)) {
                    "echo 'Error: Invalid Git operation: $sanitizedOp. Supported operations: status, log, branch, add, commit, push, pull, diff, remote'"
                } else {
                    "git $sanitizedOp"
                }
            }
        }
    }
}
