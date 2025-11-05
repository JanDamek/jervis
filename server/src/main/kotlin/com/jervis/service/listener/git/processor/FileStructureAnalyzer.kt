package com.jervis.service.listener.git.processor

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.ProjectDocument
import com.jervis.service.background.PendingTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Creates FILE_STRUCTURE_ANALYSIS tasks for changed source files.
 * Generates AI descriptions of classes/files for architectural knowledge.
 *
 * Purpose:
 * - Analyze what each class does, its purpose, key methods
 * - Store descriptions in RAG with FILE_DESCRIPTION type
 * - Enable agents to understand code without reading source repeatedly
 * - Build architectural knowledge base over time
 *
 * Process:
 * 1. Get list of changed files from commit
 * 2. Filter to source files only (.kt, .java, .py, .ts, etc)
 * 3. Create FILE_STRUCTURE_ANALYSIS task for each meaningful file
 * 4. Tasks go through qualification (skip tests, generated code)
 * 5. Background engine processes qualified tasks
 */
@Service
class FileStructureAnalyzer(
    private val pendingTaskService: PendingTaskService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Analyze changed files from commit and create structure analysis tasks.
     * Returns count of tasks created.
     */
    suspend fun analyzeCommitFiles(
        project: ProjectDocument,
        projectPath: Path,
        commitHash: String,
        changedFiles: List<String>,
        dynamicGoal: String? = null,
    ): Int =
        withContext(Dispatchers.IO) {
            logger.info {
                "Analyzing ${changedFiles.size} files from commit ${commitHash.take(8)} in project ${project.name}"
            }

            var tasksCreated = 0

            for (filePath in changedFiles) {
                try {
                    // Check if file should be analyzed
                    if (!shouldAnalyzeFile(filePath)) {
                        logger.debug { "Skipping file analysis: $filePath" }
                        continue
                    }

                    // Read file content (limit to 1000 lines for large files)
                    val fullPath = projectPath.resolve(filePath)
                    val fileContent = readFileWithLimit(fullPath, maxLines = 1000)

                    if (fileContent == null) {
                        logger.debug { "Could not read file: $filePath" }
                        continue
                    }

                    // Create analysis task (with optional dynamic goal)
                    createFileAnalysisTask(project, filePath, commitHash, fileContent, dynamicGoal)
                    tasksCreated++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create file analysis task for $filePath" }
                }
            }

            logger.info { "Created $tasksCreated file structure analysis tasks for commit ${commitHash.take(8)}" }
            tasksCreated
        }

    /**
     * Create FILE_STRUCTURE_ANALYSIS task for a single file.
     *
     * @param dynamicGoal Optional per-task goal specialization (e.g., "Focus on concurrency issues")
     */
    private suspend fun createFileAnalysisTask(
        project: ProjectDocument,
        filePath: String,
        commitHash: String,
        fileContent: String,
        dynamicGoal: String? = null,
    ): PendingTask {
        val context =
            mutableMapOf(
                "projectId" to project.id.toHexString(),
                "filePath" to filePath,
                "commitHash" to commitHash,
                "fileContent" to fileContent,
            )

        // Add dynamic goal if provided and non-blank
        dynamicGoal?.takeIf { it.isNotBlank() }?.let {
            context["dynamicGoal"] = it
            logger.debug { "Task for $filePath has dynamic goal: ${it.take(100)}" }
        }

        val content =
            buildString {
                appendLine("File Structure Analysis Required")
                appendLine()
                appendLine("File: $filePath")
                appendLine("Commit: $commitHash")
                appendLine("Project: ${project.name}")
                appendLine()
                appendLine("Analyze this source file and create description:")
                appendLine("- Class/interface name and purpose")
                appendLine("- Package and module")
                appendLine("- Key methods and their roles")
                appendLine("- Dependencies on other classes")
                appendLine("- Role in overall architecture")
                appendLine()
                appendLine("Store description using knowledge_store with:")
                appendLine("- sourceType: FILE_DESCRIPTION")
                appendLine("- metadata: className, packageName, filePath, commitHash")
            }

        return pendingTaskService.createTask(
            taskType = PendingTaskTypeEnum.FILE_STRUCTURE_ANALYSIS,
            content = content,
            projectId = project.id,
            clientId = project.clientId,
            context = context,
        )
    }

    /**
     * Check if file should be analyzed.
     * Only source files, skip tests, configs, generated code.
     */
    private fun shouldAnalyzeFile(filePath: String): Boolean {
        val extension = filePath.substringAfterLast('.', "")

        // Must be source file
        if (!isSourceFile(extension)) {
            return false
        }

        // Skip test files
        if (isTestFile(filePath)) {
            return false
        }

        // Skip generated code
        if (isGeneratedCode(filePath)) {
            return false
        }

        // Skip build output
        if (isBuildOutput(filePath)) {
            return false
        }

        return true
    }

    /**
     * Check if extension is source code file.
     */
    private fun isSourceFile(extension: String): Boolean =
        when (extension.lowercase()) {
            "kt", "java", "py", "ts", "js", "go", "rs", "cpp", "cc", "c", "cs", "swift", "rb", "php" -> true
            else -> false
        }

    /**
     * Check if file is test file.
     */
    private fun isTestFile(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.contains("test") ||
            lowerPath.contains("spec") ||
            lowerPath.endsWith("test.kt") ||
            lowerPath.endsWith("test.java") ||
            lowerPath.endsWith("spec.kt") ||
            lowerPath.endsWith("spec.ts")
    }

    /**
     * Check if file is generated code.
     */
    private fun isGeneratedCode(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.contains("generated") ||
            lowerPath.contains("build/") ||
            lowerPath.contains("target/") ||
            lowerPath.contains("node_modules/") ||
            lowerPath.contains(".gradle/") ||
            lowerPath.contains("out/")
    }

    /**
     * Check if file is in build output directory.
     */
    private fun isBuildOutput(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.startsWith("build/") ||
            lowerPath.startsWith("target/") ||
            lowerPath.startsWith("out/") ||
            lowerPath.startsWith("dist/")
    }

    /**
     * Read file content with line limit to avoid huge files.
     * Returns null if file doesn't exist or can't be read.
     */
    private fun readFileWithLimit(
        path: Path,
        maxLines: Int,
    ): String? {
        try {
            if (!Files.exists(path)) {
                return null
            }

            val lines = path.readText().lines()
            return if (lines.size <= maxLines) {
                lines.joinToString("\n")
            } else {
                val truncated = lines.take(maxLines).joinToString("\n")
                "$truncated\n\n... (file truncated, ${lines.size - maxLines} more lines)"
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not read file: $path" }
            return null
        }
    }
}
