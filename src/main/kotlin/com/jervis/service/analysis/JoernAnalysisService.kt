package com.jervis.service.analysis

import com.jervis.configuration.TimeoutsProperties
import com.jervis.entity.mongo.ProjectDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

/**
 * Service dedicated to Joern static code analysis operations.
 * Handles Joern installation checks, command execution, and result storage.
 */
@Service
class JoernAnalysisService(
    private val timeoutsProperties: TimeoutsProperties,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of Joern analysis operation
     */
    data class JoernAnalysisResult(
        val operationsCompleted: Int,
        val operationsFailed: Int,
        val isAvailable: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Internal result of process execution including outputs and timeout status
     */
    private data class ProcessRunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    /**
     * Runs a process and streams its output during execution:
     * - STDOUT to logger.debug (line by line)
     * - STDERR to logger.error (line by line)
     * Simultaneously collects outputs in StringBuilders for further use.
     */
    private fun runProcessStreaming(
        displayName: String,
        command: List<String>,
        workingDir: Path?,
        timeout: Long,
        unit: TimeUnit = TimeUnit.SECONDS,
        redirectErrorStream: Boolean = false,
    ): ProcessRunResult {
        val pb =
            ProcessBuilder().apply {
                command(command)
                workingDir?.toFile()?.let { directory(it) }
                redirectErrorStream(redirectErrorStream)
            }

        logger.debug {
            "[PROC] Starting: $displayName | cmd=${
                command.joinToString(
                    " ",
                )
            } | dir=${workingDir?.pathString ?: "(default)"} | timeout=$timeout ${unit.name.lowercase()}"
        }

        val process = pb.start()
        val pid = runCatching { process.pid() }.getOrNull()
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutThread =
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stdoutBuf.appendLine(line)
                        logger.debug { "[$displayName][stdout] $line" }
                    }
                }
            }, "$displayName-stdout").apply { isDaemon = true }

        val stderrThread =
            if (!redirectErrorStream) {
                Thread({
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stderrBuf.appendLine(line)
                            logger.error { "[$displayName][stderr] $line" }
                        }
                    }
                }, "$displayName-stderr").apply { isDaemon = true }
            } else {
                null
            }

        stdoutThread.start()
        stderrThread?.start()

        val completed = process.waitFor(timeout, unit)
        if (!completed) {
            logger.error { "[PROC] Timeout: $displayName (PID=${pid ?: "n/a"}) after $timeout ${unit.name.lowercase()}. Killing..." }
            process.destroyForcibly()
        }

        // wait for streams to be read (short join)
        runCatching { stdoutThread.join(1000) }
        runCatching { stderrThread?.join(1000) }

        val exit = if (completed) process.exitValue() else -1
        logger.debug { "[PROC] Finished: $displayName | exit=$exit" }

        return ProcessRunResult(
            exitCode = exit,
            stdout = stdoutBuf.toString(),
            stderr = stderrBuf.toString(),
            timedOut = !completed,
        )
    }

    /**
     * Check if Joern is installed and accessible in system PATH
     */
    suspend fun isJoernAvailable(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val joernRes =
                    runProcessStreaming(
                        displayName = "joern --help",
                        command = listOf("joern", "--help"),
                        workingDir = null,
                        timeout = timeoutsProperties.joern.helpCommandTimeoutMinutes,
                        unit = TimeUnit.MINUTES,
                    )
                val scanRes =
                    runProcessStreaming(
                        displayName = "joern-scan --help",
                        command = listOf("joern-scan", "--help"),
                        workingDir = null,
                        timeout = timeoutsProperties.joern.helpCommandTimeoutMinutes,
                        unit = TimeUnit.MINUTES,
                    )

                val joernAvailable = !joernRes.timedOut && joernRes.exitCode == 0
                val scanAvailable = !scanRes.timedOut && scanRes.exitCode == 0

                if (!joernAvailable) logger.warn { "joern is not available in system PATH" }
                if (!scanAvailable) logger.warn { "joern-scan is not available in system PATH" }

                joernAvailable || scanAvailable
            } catch (e: Exception) {
                logger.debug(e) { "Joern availability check failed: ${e.message}" }
                false
            }
        }

    /**
     * Setup .joern directory for storing Joern analysis results
     */
    suspend fun setupJoernDirectory(projectPath: Path): Path =
        withContext(Dispatchers.IO) {
            val joernDir = projectPath.resolve(".joern")
            try {
                if (!Files.exists(joernDir)) {
                    Files.createDirectories(joernDir)
                    logger.info { "Created .joern directory at: ${joernDir.pathString}" }
                } else {
                    logger.info { "Using existing .joern directory at: ${joernDir.pathString}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to create .joern directory at: ${joernDir.pathString}" }
                throw e
            }
            return@withContext joernDir
        }

    /**
     * Perform comprehensive Joern analysis and store results in .joern directory
     */
    suspend fun performJoernAnalysis(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): JoernAnalysisResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting Joern analysis for project: ${project.name}" }

            if (!isJoernAvailable()) {
                val errorMsg = "Joern is not installed or not accessible in system PATH"
                logger.warn { "$errorMsg. Skipping Joern analysis." }
                return@withContext JoernAnalysisResult(0, 0, false, errorMsg)
            }

            var errorFiles = 0
            val analysisOperations =
                listOf(
                    "analyze" to "cpg.method.name.toJson",
                    "scan" to "cpg.finding.toJson",
                    "cpg-info" to "cpg.metaData.toJson",
                )

            try {
                for ((operation, query) in analysisOperations) {
                    try {
                        logger.info { "Executing Joern $operation for project: ${project.name}" }

                        val success = executeJoernOperation(operation, query, projectPath, joernDir)
                        if (!success) {
                            errorFiles++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error during Joern $operation for project: ${project.name}" }
                        errorFiles++
                    }
                }

                createAnalysisSummary(project, projectPath, joernDir, analysisOperations.size, errorFiles)
            } catch (e: Exception) {
                logger.error(e) { "Critical error during Joern analysis for project: ${project.name}" }
                errorFiles = analysisOperations.size
            }

            val operationsCompleted = analysisOperations.size - errorFiles
            logger.info { "Joern analysis completed - Completed: $operationsCompleted, Errors: $errorFiles" }

            JoernAnalysisResult(operationsCompleted, errorFiles, true)
        }

    /**
     * Execute a single Joern operation following proper Joern usage patterns
     */
    private suspend fun executeJoernOperation(
        operation: String,
        query: String,
        projectPath: Path,
        joernDir: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                when (operation) {
                    "scan" -> executeScanOperation(projectPath, joernDir)
                    "analyze", "query", "cpg-info" -> executeQueryOperation(operation, query, projectPath, joernDir)
                    else -> {
                        logger.warn { "Unknown operation: $operation" }
                        false
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute Joern $operation: ${e.message}" }
                false
            }
        }

    /**
     * Execute Joern scan operation using joern-scan
     */
    private suspend fun executeScanOperation(
        projectPath: Path,
        joernDir: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val outputFile = joernDir.resolve("scan.json")

            val res =
                runProcessStreaming(
                    displayName = "joern-scan",
                    command = listOf("joern-scan", "--format", "json", "--path", projectPath.pathString),
                    workingDir = joernDir,
                    timeout = timeoutsProperties.joern.scanTimeoutMinutes,
                    unit = TimeUnit.MINUTES,
                )

            if (!res.timedOut && res.exitCode == 0) {
                Files.writeString(outputFile, res.stdout)
                logger.info { "Joern scan results saved to: ${outputFile.pathString}" }
                true
            } else {
                val errorFile = joernDir.resolve("scan_error.txt")
                Files.writeString(
                    errorFile,
                    "Exit code: ${res.exitCode}\nTimedOut: ${res.timedOut}\nStderr:\n${res.stderr}\n\nStdout:\n${res.stdout}",
                )
                logger.warn { "Joern scan failed (exit=${res.exitCode}, timedOut=${res.timedOut}), details: ${errorFile.pathString}" }
                false
            }
        }

    /**
     * Execute Joern query operation using proper CPG and script approach
     */
    private suspend fun executeQueryOperation(
        operation: String,
        query: String,
        projectPath: Path,
        joernDir: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val cpgPath = joernDir.resolve("cpg.bin")
            if (!ensureCpgExists(projectPath, cpgPath)) {
                logger.warn { "Failed to create or find CPG for project: ${projectPath.pathString}" }
                return@withContext false
            }

            val scriptFile = joernDir.resolve("query_${System.currentTimeMillis()}.sc")
            val scriptContent =
                buildString {
                    appendLine("importCpg(\"${cpgPath.pathString}\")")
                    appendLine("val res = $query")
                    appendLine("println(res)")
                }
            Files.writeString(scriptFile, scriptContent)

            val res =
                runProcessStreaming(
                    displayName = "joern --script ${scriptFile.fileName}",
                    command = listOf("joern", "--script", scriptFile.pathString),
                    workingDir = joernDir,
                    timeout = timeoutsProperties.joern.scriptTimeoutMinutes,
                    unit = TimeUnit.MINUTES,
                )

            return@withContext runCatching {
                if (!res.timedOut && res.exitCode == 0) {
                    val outputFile = joernDir.resolve("${operation}_results.json")
                    Files.writeString(outputFile, res.stdout)
                    logger.info { "Joern $operation results saved to: ${outputFile.pathString}" }
                    true
                } else {
                    val errorFile = joernDir.resolve("${operation}_error.txt")
                    Files.writeString(
                        errorFile,
                        "Exit code: ${res.exitCode}\nTimedOut: ${res.timedOut}\nStderr:\n${res.stderr}\n\nStdout:\n${res.stdout}",
                    )
                    logger.warn {
                        "Joern $operation failed (exit=${res.exitCode}, timedOut=${res.timedOut}), details: ${errorFile.pathString}"
                    }
                    false
                }
            }.also {
                runCatching { Files.deleteIfExists(scriptFile) }
            }.getOrDefault(false)
        }

    /**
     * Ensure .joernignore file exists with required patterns
     */
    private fun ensureJoernIgnoreExists(projectPath: Path) {
        val joernIgnoreFile = projectPath.resolve(".joernignore")
        val requiredPatterns =
            listOf(
                "**/*.class",
                "**/*.jar",
                "**/target/**",
                "**/build/**",
                "**/.idea/**",
                "**/out/**",
                "**/node_modules/**",
            )

        try {
            val existingContent =
                if (Files.exists(joernIgnoreFile)) {
                    Files.readAllLines(joernIgnoreFile).toMutableSet()
                } else {
                    mutableSetOf()
                }

            val missingPatterns =
                requiredPatterns.filterNot { pattern ->
                    existingContent.any { it.trim() == pattern }
                }

            if (missingPatterns.isNotEmpty() || !Files.exists(joernIgnoreFile)) {
                val allContent = mutableListOf<String>()

                if (existingContent.isNotEmpty()) {
                    allContent.addAll(existingContent)
                    allContent.add("") // Empty line separator
                }

                if (missingPatterns.isNotEmpty()) {
                    allContent.add("# Required patterns for Joern analysis")
                    allContent.addAll(missingPatterns)
                }

                Files.write(joernIgnoreFile, allContent)

                if (!Files.exists(joernIgnoreFile)) {
                    logger.info { "Created .joernignore file with required patterns: ${joernIgnoreFile.pathString}" }
                } else {
                    logger.info { "Updated .joernignore file with missing patterns: ${missingPatterns.joinToString(", ")}" }
                }
            } else {
                logger.debug { ".joernignore file already contains all required patterns: ${joernIgnoreFile.pathString}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error ensuring .joernignore file exists: ${e.message}" }
        }
    }

    /**
     * Ensure CPG exists and is up to date
     */
    private suspend fun ensureCpgExists(
        projectPath: Path,
        cpgPath: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Check if CPG already exists and is reasonably fresh
                if (Files.exists(cpgPath)) {
                    val cpgModified = Files.getLastModifiedTime(cpgPath).toMillis()
                    val projectModified = getLastModifiedTimeRecursiveSourceOnly(projectPath)

                    // If CPG is newer than project files, reuse it
                    if (cpgModified > projectModified) {
                        logger.debug { "CPG is up to date, reusing existing: ${cpgPath.pathString}" }
                        return@withContext true
                    }
                }

                logger.info { "Creating CPG for project: ${projectPath.pathString}" }

                // Ensure .joernignore file exists with required patterns
                ensureJoernIgnoreExists(projectPath)

                // Create temporary directory with only source files to avoid Java 20 bytecode issues
                val tempSourceDir = Files.createTempDirectory("joern-source-")
                try {
                    copySourceFilesOnly(projectPath, tempSourceDir)
                    logger.debug { "Created filtered source directory: ${tempSourceDir.pathString}" }

                    val res =
                        runProcessStreaming(
                            displayName = "joern-parse",
                            command = listOf("joern-parse", tempSourceDir.pathString, "-o", cpgPath.pathString),
                            workingDir = projectPath.parent ?: projectPath,
                            timeout = timeoutsProperties.joern.parseTimeoutMinutes,
                            unit = TimeUnit.MINUTES,
                        )

                    if (!res.timedOut && res.exitCode == 0 && Files.exists(cpgPath)) {
                        logger.info { "CPG created successfully: ${cpgPath.pathString}" }
                        true
                    } else {
                        val errorFile = cpgPath.parent.resolve("parse_error.txt")
                        Files.writeString(
                            errorFile,
                            "Exit code: ${res.exitCode}\nTimedOut: ${res.timedOut}\nStderr:\n${res.stderr}\n\nStdout:\n${res.stdout}",
                        )
                        logger.warn {
                            "CPG creation failed (exit=${res.exitCode}, timedOut=${res.timedOut}), details: ${errorFile.pathString}"
                        }
                        false
                    }
                } finally {
                    // Clean up temporary directory
                    runCatching {
                        Files
                            .walk(tempSourceDir)
                            .sorted { a, b -> b.compareTo(a) } // Delete files before directories
                            .forEach { Files.deleteIfExists(it) }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error ensuring CPG exists: ${e.message}" }
                false
            }
        }

    /**
     * Get the last modified time of the most recent file in the project tree
     */
    private fun getLastModifiedTimeRecursive(path: Path): Long =
        try {
            Files
                .walk(path)
                .filter { Files.isRegularFile(it) }
                .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                .max()
                .orElse(0L)
        } catch (e: Exception) {
            logger.debug(e) { "Error getting last modified time for: ${path.pathString}" }
            0L
        }

    /**
     * Get the last modified time of the most recent source file only
     */
    private fun getLastModifiedTimeRecursiveSourceOnly(path: Path): Long =
        try {
            Files
                .walk(path)
                .filter { Files.isRegularFile(it) }
                .filter { isSourceFile(it) }
                .filter { !isInExcludedDirectory(path, it) }
                .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                .max()
                .orElse(0L)
        } catch (e: Exception) {
            logger.debug(e) { "Error getting last modified time for source files in: ${path.pathString}" }
            0L
        }

    /**
     * Copy only source files to target directory, preserving structure but excluding build directories
     */
    private fun copySourceFilesOnly(
        sourceRoot: Path,
        targetRoot: Path,
    ) {
        try {
            Files
                .walk(sourceRoot)
                .filter { Files.isRegularFile(it) }
                .filter { isSourceFile(it) }
                .filter { !isInExcludedDirectory(sourceRoot, it) }
                .forEach { sourceFile ->
                    val relativePath = sourceRoot.relativize(sourceFile)
                    val targetFile = targetRoot.resolve(relativePath)

                    // Create parent directories if they don't exist
                    Files.createDirectories(targetFile.parent)
                    Files.copy(sourceFile, targetFile)
                }
            logger.debug { "Copied source files from ${sourceRoot.pathString} to ${targetRoot.pathString}" }
        } catch (e: Exception) {
            logger.error(e) { "Error copying source files: ${e.message}" }
            throw e
        }
    }

    /**
     * Check if a file is a source code file based on extension
     */
    private fun isSourceFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        return SOURCE_FILE_EXTENSIONS.any { fileName.endsWith(it) }
    }

    /**
     * Check if a file is in an excluded directory (build/target directories)
     */
    private fun isInExcludedDirectory(
        rootPath: Path,
        filePath: Path,
    ): Boolean {
        val relativePath = rootPath.relativize(filePath).toString()
        return EXCLUDED_DIRECTORIES.any { excludedDir ->
            relativePath.startsWith(excludedDir) || relativePath.contains("/$excludedDir/")
        }
    }

    companion object {
        private val SOURCE_FILE_EXTENSIONS =
            setOf(
                ".kt",
                ".java",
                ".scala",
                ".groovy",
                ".js",
                ".ts",
                ".py",
                ".rb",
                ".php",
                ".cpp",
                ".c",
                ".h",
                ".hpp",
                ".cs",
                ".go",
                ".rs",
                ".swift",
                ".m",
                ".mm",
                ".sql",
                ".xml",
                ".yaml",
                ".yml",
                ".json",
                ".properties",
            )

        private val EXCLUDED_DIRECTORIES =
            setOf(
                "target",
                "build",
                ".gradle",
                ".mvn",
                "node_modules",
                ".git",
                ".idea",
                ".vscode",
                "out",
                "bin",
                "classes",
                "compiled",
                ".class",
            )
    }

    /**
     * Create analysis summary file with version information and execution details
     */
    private suspend fun createAnalysisSummary(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
        totalOperations: Int,
        errorFiles: Int,
    ) = withContext(Dispatchers.IO) {
        val summaryFile = joernDir.resolve("analysis_summary.txt")
        val summary =
            buildString {
                appendLine("Joern Analysis Summary")
                appendLine("=".repeat(50))
                appendLine("Project: ${project.name}")
                appendLine("Path: ${projectPath.pathString}")
                appendLine("Analysis Date: ${Instant.now()}")
                appendLine("Operations Completed: ${totalOperations - errorFiles}")
                appendLine("Errors: $errorFiles")
                appendLine()

                // Add tool version information
                appendLine("Tool Versions:")
                val joernVersion = getToolVersion("joern")
                val scanVersion = getToolVersion("joern-scan")
                appendLine("- joern: $joernVersion")
                appendLine("- joern-scan: $scanVersion")
                appendLine()

                // List generated artifacts with their paths
                appendLine("Generated Artifacts:")
                try {
                    Files
                        .list(joernDir)
                        .filter {
                            !it.fileName.toString().startsWith("query_") || !it.fileName.toString().endsWith(".sc")
                        }.sorted()
                        .forEach { file ->
                            val size = Files.size(file)
                            val sizeStr =
                                when {
                                    size > 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                                    size > 1024 -> "${size / 1024} KB"
                                    else -> "$size B"
                                }
                            appendLine("- ${file.fileName} ($sizeStr)")
                            appendLine("  Full path: ${file.pathString}")
                        }
                } catch (e: Exception) {
                    appendLine("Error listing files: ${e.message}")
                }

                appendLine()
                appendLine("Working Directory: ${joernDir.pathString}")

                if (Files.exists(joernDir.resolve("cpg.bin"))) {
                    appendLine("CPG Status: Available at ${joernDir.resolve("cpg.bin").pathString}")
                } else {
                    appendLine("CPG Status: Not created")
                }
            }
        Files.writeString(summaryFile, summary)
        logger.info { "Analysis summary created: ${summaryFile.pathString}" }
    }

    /**
     * Get version information for Joern tools
     */
    private suspend fun getToolVersion(toolName: String): String =
        withContext(Dispatchers.IO) {
            try {
                // Joern doesn't support --version flag, use --help instead
                val command =
                    when (toolName) {
                        "joern" -> listOf(toolName, "--help")
                        "joern-scan" -> {
                            // Try --version first for joern-scan, fallback to --help
                            val versionRes =
                                runCatching {
                                    runProcessStreaming(
                                        displayName = "$toolName --version",
                                        command = listOf(toolName, "--version"),
                                        workingDir = null,
                                        timeout = timeoutsProperties.joern.versionTimeoutMinutes,
                                        unit = TimeUnit.MINUTES,
                                    )
                                }.getOrNull()

                            if (versionRes != null && !versionRes.timedOut && versionRes.exitCode == 0) {
                                return@withContext extractVersionFromOutput(versionRes.stdout, versionRes.stderr)
                            }

                            listOf(toolName, "--help")
                        }

                        else -> listOf(toolName, "--version")
                    }

                val res =
                    runProcessStreaming(
                        displayName = "$toolName version check",
                        command = command,
                        workingDir = null,
                        timeout = timeoutsProperties.joern.versionTimeoutMinutes,
                        unit = TimeUnit.MINUTES,
                    )

                if (!res.timedOut && res.exitCode == 0) {
                    extractVersionFromOutput(res.stdout, res.stderr)
                } else {
                    "Not available"
                }
            } catch (e: Exception) {
                "Not available (${e.message?.take(50) ?: "unknown error"})"
            }
        }

    /**
     * Extract version information from Joern tool output
     */
    private fun extractVersionFromOutput(
        stdout: String,
        stderr: String,
    ): String {
        val output = "$stdout\n$stderr"

        // Look for "Version: " pattern in the output
        val versionRegex = """Version:\s*([^\s\n]+)""".toRegex()
        val versionMatch = versionRegex.find(output)

        return versionMatch?.groupValues?.get(1)
            ?: throw IllegalStateException("Unable to extract version information from tool output: $output")
    }

    /**
     * Execute a single Joern operation (public method for external use)
     */
    suspend fun executeSingleOperation(
        operation: String,
        query: String,
        projectPath: Path,
        joernDir: Path,
    ): Boolean = executeJoernOperation(operation, query, projectPath, joernDir)
}
