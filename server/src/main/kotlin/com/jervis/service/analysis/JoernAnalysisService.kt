package com.jervis.service.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.pathString

/**
 * Service for Joern static code analysis operations.
 * Handles CPG (Code Property Graph) creation for supported programming languages.
 */
@Service
class JoernAnalysisService {
    private val logger = KotlinLogging.logger {}

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
     * Runs a process and streams its output during execution.
     * STDOUT is logged at debug level, STDERR at error level.
     * Outputs are collected for further processing.
     */
    private fun runProcessStreaming(
        displayName: String,
        command: List<String>,
        workingDir: Path?,
        timeout: Long? = null,
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
            } | dir=${workingDir?.pathString ?: "(default)"}${if (timeout != null) " | timeout=$timeout ${unit.name.lowercase()}" else " | no timeout"}"
        }

        val process = pb.start()
        val pid = process.pid()
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

        val completed =
            if (timeout != null) {
                process.waitFor(timeout, unit)
            } else {
                process.waitFor()
                true
            }

        if (!completed) {
            logger.error { "[PROC] Timeout: $displayName (PID=$pid) after $timeout ${unit.name.lowercase()}. Killing..." }
            process.destroyForcibly()
        }

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
            joernDir
        }

    /**
     * Validate CPG file integrity by checking basic file properties and structure
     */
    private fun validateCpgIntegrity(cpgPath: Path): Boolean {
        return try {
            if (!Files.exists(cpgPath)) {
                logger.debug { "CPG file does not exist: ${cpgPath.pathString}" }
                return false
            }

            val fileSize = Files.size(cpgPath)
            if (fileSize < 1024) {
                logger.debug { "CPG file is too small ($fileSize bytes): ${cpgPath.pathString}" }
                return false
            }

            Files.newInputStream(cpgPath).use { inputStream ->
                val buffer = ByteArray(16)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 4) {
                    logger.debug { "CPG file is too small to be valid: ${cpgPath.pathString}" }
                    return false
                }

                val hasNonAscii =
                    buffer.take(bytesRead).any { byte ->
                        byte < 0 || (byte in 1..8) || (byte in 14..31)
                    }

                if (!hasNonAscii) {
                    logger.debug { "CPG file appears to be text-only, not binary: ${cpgPath.pathString}" }
                    return false
                }
            }

            logger.debug { "CPG file validation passed: ${cpgPath.pathString} (size: $fileSize bytes)" }
            true
        } catch (e: Exception) {
            logger.debug(e) { "CPG file validation failed: ${cpgPath.pathString}" }
            false
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

    /**
     * Detects language buckets by scanning project files and grouping them by programming language
     */
    suspend fun detectLanguageBuckets(projectPath: Path): Map<String, List<Path>> =
        withContext(Dispatchers.IO) {
            val languageBuckets = mutableMapOf<String, MutableList<Path>>()

            try {
                Files
                    .walk(projectPath)
                    .filter { Files.isRegularFile(it) }
                    .filter { isSourceFile(it) }
                    .filter { !isInExcludedDirectory(projectPath, it) }
                    .forEach { file ->
                        val language = detectLanguageFromFile(file)
                        if (language != null) {
                            languageBuckets.getOrPut(language) { mutableListOf() }.add(file)
                        }
                    }

                logger.info { "Detected language buckets: ${languageBuckets.mapValues { it.value.size }}" }
                languageBuckets.mapValues { it.value.toList() }
            } catch (e: Exception) {
                logger.error(e) { "Error detecting language buckets: ${e.message}" }
                emptyMap()
            }
        }

    /**
     * Detects programming language from file extension
     */
    private fun detectLanguageFromFile(file: Path): String? =
        when (file.extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            "rb" -> "ruby"
            "php" -> "php"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "swift" -> "swift"
            "scala" -> "scala"
            "groovy" -> "groovy"
            else -> null
        }

    /**
     * Ensures per-language CPG files exist and are up to date.
     * Creates or regenerates CPG files for each language when source files have changed.
     */
    suspend fun ensurePerLanguageCpg(
        projectPath: Path,
        languageBuckets: Map<String, List<Path>>,
    ): Map<String, Path> =
        withContext(Dispatchers.IO) {
            val joernDir = setupJoernDirectory(projectPath)
            val result = mutableMapOf<String, Path>()

            languageBuckets.forEach { (language, files) ->
                if (files.isEmpty()) return@forEach

                val cpgPath = joernDir.resolve("cpg_$language.bin")

                val needsRegeneration =
                    if (Files.exists(cpgPath)) {
                        val cpgLastModified = cpgPath.toFile().lastModified()
                        val hasNewerFiles =
                            files.any { sourceFile ->
                                sourceFile.toFile().lastModified() > cpgLastModified
                            }

                        if (hasNewerFiles) {
                            logger.info {
                                "CPG for $language is outdated - source files have been modified since CPG creation"
                            }
                            true
                        } else {
                            logger.debug { "CPG for $language is up to date, skipping regeneration" }
                            false
                        }
                    } else {
                        logger.debug { "CPG for $language does not exist, creating new one" }
                        true
                    }

                if (!needsRegeneration) {
                    result[language] = cpgPath
                    return@forEach
                }

                val tempLanguageDir = Files.createTempDirectory("joern-$language-")
                try {
                    files.forEach { sourceFile ->
                        val relativePath = projectPath.relativize(sourceFile)
                        val targetFile = tempLanguageDir.resolve(relativePath)
                        Files.createDirectories(targetFile.parent)
                        Files.copy(sourceFile, targetFile)
                    }

                    logger.info { "Creating CPG for language: $language with ${files.size} files" }

                    val res =
                        runProcessStreaming(
                            displayName = "joern-parse-$language",
                            command =
                                listOf(
                                    "joern-parse",
                                    tempLanguageDir.pathString,
                                    "-o",
                                    cpgPath.pathString,
                                ),
                            workingDir = projectPath.parent ?: projectPath,
                        )

                    if (res.exitCode == 0 && Files.exists(cpgPath) && validateCpgIntegrity(cpgPath)) {
                        val cpgCreationTime =
                            java.time.Instant.ofEpochMilli(cpgPath.toFile().lastModified())
                        logger.info {
                            "CPG created successfully for $language: ${cpgPath.pathString} at $cpgCreationTime"
                        }
                        result[language] = cpgPath
                    } else {
                        logger.warn { "Failed to create CPG for language $language (exit=${res.exitCode})" }
                    }
                } finally {
                    runCatching {
                        Files
                            .walk(tempLanguageDir)
                            .sorted { a, b -> b.compareTo(a) }
                            .forEach { Files.deleteIfExists(it) }
                    }
                }
            }

            return@withContext result
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
}
