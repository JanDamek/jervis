package com.jervis.service.analysis

import com.jervis.service.indexing.pipeline.domain.JoernSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.pathString

/**
 * Service dedicated to Joern static code analysis operations.
 * Handles Joern installation checks, command execution, and result storage.
 */
@Service
class JoernAnalysisService(
    private val joernResultParser: JoernResultParser,
) {
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
     * Runs a process and streams its output during execution:
     * - STDOUT to logger.debug (line by line)
     * - STDERR to logger.error (line by line)
     * Simultaneously collects outputs in StringBuilders for further use.
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
                true // waitFor() without timeout always returns when process completes
            }

        if (!completed) {
            logger.error { "[PROC] Timeout: $displayName (PID=$pid) after $timeout ${unit.name.lowercase()}. Killing..." }
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
     * Validate CPG file integrity by checking basic file properties and structure
     */
    private fun validateCpgIntegrity(cpgPath: Path): Boolean {
        return try {
            if (!Files.exists(cpgPath)) {
                logger.debug { "CPG file does not exist: ${cpgPath.pathString}" }
                return false
            }

            val fileSize = Files.size(cpgPath)
            if (fileSize < 1024) { // CPG files should be at least 1KB
                logger.debug { "CPG file is too small ($fileSize bytes): ${cpgPath.pathString}" }
                return false
            }

            // Try to read the first few bytes to check if it's a binary file
            Files.newInputStream(cpgPath).use { inputStream ->
                val buffer = ByteArray(16)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 4) {
                    logger.debug { "CPG file is too small to be valid: ${cpgPath.pathString}" }
                    return false
                }

                // Check if it looks like a valid binary file (not all zeros or all text)
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
     * Ensures per-language CPG files exist and are up to date
     */
    suspend fun ensurePerLanguageCpg(
        projectPath: Path,
        languageBuckets: Map<String, List<Path>>,
    ): Map<String, Path> =
        withContext(Dispatchers.IO) {
            val joernDir = setupJoernDirectory(projectPath)

            try {
                // Process all languages in parallel
                coroutineScope {
                    val cpgTasks =
                        languageBuckets.map { (language, files) ->
                            async {
                                if (files.isEmpty()) return@async null

                                val cpgPath = joernDir.resolve("cpg_$language.bin")

                                // Check if CPG exists and is up to date with source files
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

                                // Skip CPG generation if not needed
                                if (!needsRegeneration) {
                                    return@async language to cpgPath
                                }

                                // Create temporary directory with only files for this language
                                val tempLanguageDir = Files.createTempDirectory("joern-$language-")
                                try {
                                    // Copy files for this language to temp directory
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
                                        language to cpgPath
                                    } else {
                                        logger.warn { "Failed to create CPG for language $language (exit=${res.exitCode})" }
                                        null
                                    }
                                } finally {
                                    // Clean up temporary directory
                                    runCatching {
                                        Files
                                            .walk(tempLanguageDir)
                                            .sorted { a, b -> b.compareTo(a) }
                                            .forEach { Files.deleteIfExists(it) }
                                    }
                                }
                            }
                        }

                    // Collect successful results
                    cpgTasks.awaitAll().filterNotNull().toMap()
                }
            } catch (e: Exception) {
                logger.error(e) { "Error ensuring per-language CPGs: ${e.message}" }
                emptyMap()
            }
        }

    /**
     * Returns existing per-language CPG files from .joern directory without creating new ones
     * Used by MCP tools that should only work with already generated CPGs
     */
    suspend fun ensurePerLanguageCpgs(projectPath: Path): List<Path> =
        withContext(Dispatchers.IO) {
            try {
                val joernDir = setupJoernDirectory(projectPath)
                val cpgFiles = mutableListOf<Path>()

                if (Files.exists(joernDir)) {
                    Files.list(joernDir).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .filter {
                                it.fileName.toString().startsWith("cpg_") && it.fileName.toString().endsWith(".bin")
                            }.filter { validateCpgIntegrity(it) }
                            .forEach { cpgFiles.add(it) }
                    }

                    logger.info { "Found ${cpgFiles.size} existing CPG files: ${cpgFiles.map { it.fileName }}" }
                } else {
                    logger.warn { "No .joern directory found at: ${joernDir.pathString}" }
                }

                cpgFiles.toList()
            } catch (e: Exception) {
                logger.error(e) { "Error scanning for existing CPG files: ${e.message}" }
                emptyList()
            }
        }

    /**
     * Extracts all symbols from language-specific CPGs with parallel processing
     */
    fun extractAllSymbolsFromCpGs(
        projectPath: Path,
        cpgPaths: Map<String, Path>,
    ): Flow<JoernSymbol> =
        flow {
            // Process all languages in parallel
            coroutineScope {
                val flows =
                    cpgPaths.map { (language, cpgPath) ->
                        async {
                            try {
                                logger.info { "Extracting symbols from $language CPG: ${cpgPath.pathString}" }

                                val script = createUniversalSymbolExtractionScript(cpgPath, language)
                                val joernDir = cpgPath.parent
                                val scriptFile = joernDir.resolve("extract_symbols_$language.sc")

                                withContext(Dispatchers.IO) {
                                    Files.writeString(scriptFile, script)
                                }

                                val result = executeJoernScript(joernDir, scriptFile)
                                val symbolFlow =
                                    joernResultParser.parseJoernSymbolResults(result, projectPath, language)

                                // Clean up script file
                                withContext(Dispatchers.IO) {
                                    Files.deleteIfExists(scriptFile)
                                }

                                symbolFlow
                            } catch (e: Exception) {
                                logger.error(e) { "Error extracting symbols from $language CPG: ${e.message}" }
                                flow<JoernSymbol> { /* empty flow for failed language */ }
                            }
                        }
                    }

                // Collect results from all parallel flows
                flows.awaitAll().forEach { symbolFlow ->
                    emitAll(symbolFlow)
                }
            }
        }

    /**
     * Creates universal symbol extraction script without file filtering
     */
    private fun createUniversalSymbolExtractionScript(
        cpgPath: Path,
        language: String,
    ): String =
        try {
            // Read template from resources
            val templateResource =
                this::class.java.getResourceAsStream("/joern/universal_symbol_extraction.sc")
                    ?: throw RuntimeException("Universal symbol extraction template not found in resources")

            val template = templateResource.bufferedReader().use { it.readText() }

            // Replace placeholders
            template
                .replace("{{CPG_PATH}}", cpgPath.pathString)
                .replace("{{LANGUAGE}}", language)
        } catch (e: Exception) {
            logger.error(e) { "Error reading universal symbol extraction template: ${e.message}" }
            throw RuntimeException("Failed to create universal symbol extraction script", e)
        }

    /**
     * Main orchestrator for Joern-based project indexing using language-grouped CPGs
     */
    fun indexProjectWithJoern(projectPath: Path): Flow<JoernSymbol> =
        flow {
            try {
                logger.info { "Starting Joern analysis for project: ${projectPath.pathString}" }

                // Step 1: Detect language buckets
                val languageBuckets = detectLanguageBuckets(projectPath)
                if (languageBuckets.isEmpty()) {
                    logger.warn { "No supported source files found in project: ${projectPath.pathString}" }
                    return@flow
                }

                // Step 2: Ensure per-language CPGs exist
                val cpgPaths = ensurePerLanguageCpg(projectPath, languageBuckets)
                if (cpgPaths.isEmpty()) {
                    logger.error { "Failed to create any CPG files for project: ${projectPath.pathString}" }
                    return@flow
                }

                // Step 3: Extract all symbols from CPGs
                emitAll(extractAllSymbolsFromCpGs(projectPath, cpgPaths))

                logger.info { "Completed Joern analysis for project: ${projectPath.pathString}" }
            } catch (e: Exception) {
                logger.error(e) { "Error in Joern project indexing: ${e.message}" }
            }
        }

    /**
     * Execute Joern script and return output
     */
    private suspend fun executeJoernScript(
        joernDir: Path,
        scriptFile: Path,
    ): String =
        withContext(Dispatchers.IO) {
            val res =
                runProcessStreaming(
                    displayName = "joern-script",
                    command = listOf("joern", "--script", scriptFile.pathString),
                    workingDir = joernDir,
                    timeout = 300,
                    unit = TimeUnit.SECONDS,
                )

            if (res.exitCode != 0) {
                logger.error { "Joern script execution failed (exit=${res.exitCode}): ${res.stderr}" }
                throw RuntimeException("Joern script execution failed: ${res.stderr}")
            }

            res.stdout
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
