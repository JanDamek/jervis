package com.jervis.service.embedding

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.indexing.JoernChunkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service dedicated to code embedding operations.
 * Handles code file processing and embedding generation using code models.
 */
@Service
class CodeEmbeddingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val joernChunkingService: JoernChunkingService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of code embedding operation
     */
    data class CodeEmbeddingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
    )

    data class CodeMetadata(
        val packageName: String?,
        val primaryClassName: String?,
        val language: String,
    )

    /**
     * Process code file using Joern-based method chunking instead of full file embedding
     */
    suspend fun processCodeFile(
        project: ProjectDocument,
        content: String,
        filePath: Path,
        projectPath: Path,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.debug { "Processing code file with Joern chunking: ${filePath.fileName}" }

                if (content.isBlank()) {
                    logger.debug { "Skipping empty file: ${filePath.fileName}" }
                    return@withContext false
                }

                // Create temporary directory structure for Joern analysis
                val tempProjectDir = kotlin.io.path.createTempDirectory("jervis_joern_")
                val tempCodeFile = tempProjectDir.resolve(filePath.fileName.toString())
                val joernDir = tempProjectDir.resolve(".joern")
                
                try {
                    // Write content to temporary file maintaining original structure
                    java.nio.file.Files.createDirectories(tempCodeFile.parent)
                    java.nio.file.Files.writeString(tempCodeFile, content)
                    java.nio.file.Files.createDirectories(joernDir)
                    
                    // Use JoernChunkingService to process with method-level chunking
                    val result = joernChunkingService.performJoernChunking(project, tempProjectDir, joernDir)
                    
                    if (result.generatedChunks > 0) {
                        logger.debug { 
                            "Successfully processed code file with Joern chunking: ${filePath.fileName}, " +
                            "generated ${result.generatedChunks} chunks, processed ${result.processedMethods} methods, ${result.processedClasses} classes" 
                        }
                        return@withContext true
                    } else {
                        logger.warn { "No chunks generated from Joern analysis for file: ${filePath.fileName}" }
                        return@withContext false
                    }
                } finally {
                    // Clean up temporary directory
                    try {
                        tempProjectDir.toFile().deleteRecursively()
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to delete temporary directory: $tempProjectDir" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to process code file with Joern chunking: ${filePath.fileName}" }
                return@withContext false
            }
        }

    /**
     * Process multiple code files in parallel
     */
    suspend fun processCodeFiles(
        project: ProjectDocument,
        codeContents: Map<Path, String>,
        projectPath: Path,
    ): CodeEmbeddingResult =
        withContext(Dispatchers.Default) {
            logger.info { "Processing ${codeContents.size} code files for project: ${project.name}" }

            val jobs =
                codeContents.map { (filePath, content) ->
                    async {
                        processCodeFile(project, content, filePath, projectPath)
                    }
                }

            val results = jobs.awaitAll()
            val processedFiles = results.count { it }
            val errorFiles = results.count { !it }
            val skippedFiles = 0 // Currently no skipping logic in code processing

            logger.info {
                "Code processing batch completed for project ${project.name}: " +
                    "processed=$processedFiles, errors=$errorFiles"
            }

            CodeEmbeddingResult(processedFiles, skippedFiles, errorFiles)
        }

    /**
     * Generate embedding for a single code snippet
     */
    suspend fun generateCodeEmbedding(code: String): List<Float> = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, code)

    /**
     * Check if file should be processed as code
     */
    fun isCodeFile(filePath: Path): Boolean {
        val fileName = filePath.fileName.toString().lowercase()
        val codeExtensions =
            setOf(
                // Java/JVM languages
                "java",
                "kt",
                "kts",
                "scala",
                "groovy",
                "clj",
                "cljs",
                // Web technologies
                "js",
                "ts",
                "jsx",
                "tsx",
                "vue",
                "svelte",
                "html",
                "htm",
                "css",
                "scss",
                "sass",
                "less",
                // Python
                "py",
                "pyx",
                "pyi",
                // C/C++
                "c",
                "cpp",
                "cxx",
                "cc",
                "h",
                "hpp",
                "hxx",
                // C#/.NET
                "cs",
                "vb",
                "fs",
                // Other languages
                "go",
                "rs",
                "php",
                "rb",
                "swift",
                "dart",
                "sql",
                "r",
                "m",
                "mm",
                // Scripts and configs that contain code logic
                "sh",
                "bash",
                "zsh",
                "ps1",
                "bat",
                "cmd",
                "py",
                "pl",
                "awk",
                "sed",
                // Build and config files with code-like content
                "gradle",
                "maven",
                "ant",
                "make",
                "cmake",
                "dockerfile",
                "docker",
                "vagrantfile",
                "jenkinsfile",
                "pipeline",
                // Infrastructure as code
                "tf",
                "tfvars",
                "hcl",
                "yaml",
                "yml",
            )

        return codeExtensions.any { fileName.endsWith(".$it") } ||
            fileName.contains("dockerfile") ||
            fileName.contains("makefile") ||
            fileName.contains("rakefile") ||
            fileName.contains("gemfile")
    }

    /**
     * Check if file content appears to be code based on patterns
     */
    fun looksLikeCode(content: String): Boolean {
        val codeIndicators =
            listOf(
                // Common programming constructs
                Regex("\\b(function|class|interface|import|export)\\b"),
                Regex("\\b(def|let|var|const|public|private)\\b"),
                Regex("\\b(if|else|for|while|switch|case)\\b"),
                Regex("\\b(return|throw|catch|try|finally)\\b"),
                // Common code patterns
                Regex("[{}();]"),
                Regex("=\\s*[\"']|[\"']\\s*[+]"),
                Regex("//|/\\*|\\*/|#\\s*[a-zA-Z]"),
                // Package/import statements
                Regex("^\\s*(package|import|from|using|require)\\s+", RegexOption.MULTILINE),
            )

        val indicatorCount = codeIndicators.count { it.containsMatchIn(content) }
        val totalLines = content.lines().size
        val nonEmptyLines = content.lines().count { it.trim().isNotEmpty() }

        // Consider it code if:
        // - It has multiple code indicators
        // - Or it has at least one strong indicator and reasonable line density
        return indicatorCount >= 2 || (indicatorCount >= 1 && nonEmptyLines > totalLines * 0.5)
    }

    /**
     * Extract package name and primary class name from code content
     */
    private fun extractCodeMetadata(
        content: String,
        filePath: Path,
    ): CodeMetadata {
        val language = detectLanguage(filePath)
        var packageName: String? = null
        var primaryClassName: String? = null

        try {
            val lines = content.lines()

            when (language) {
                "kotlin", "java" -> {
                    // Extract package declaration
                    val packageLine = lines.find { it.trim().startsWith("package ") }
                    packageName =
                        packageLine
                            ?.trim()
                            ?.removePrefix("package ")
                            ?.removeSuffix(";")
                            ?.trim()

                    // Extract primary class/interface/object name
                    val classPatterns =
                        listOf(
                            Regex("""^\s*(public\s+|private\s+|protected\s+)?(class|interface|object|enum)\s+([A-Z][A-Za-z0-9_]*)\b"""),
                            Regex("""^\s*(class|interface|object|enum)\s+([A-Z][A-Za-z0-9_]*)\b"""),
                        )

                    for (line in lines) {
                        for (pattern in classPatterns) {
                            val match = pattern.find(line)
                            if (match != null) {
                                primaryClassName = match.groups.last()?.value
                                break
                            }
                        }
                        if (primaryClassName != null) break
                    }
                }

                "javascript", "typescript" -> {
                    // Extract ES6 module exports or class declarations
                    val classPattern = Regex("""^\s*export\s+(default\s+)?class\s+([A-Z][A-Za-z0-9_]*)\b""")
                    val functionPattern = Regex("""^\s*export\s+(default\s+)?function\s+([A-Za-z][A-Za-z0-9_]*)\b""")

                    for (line in lines) {
                        val classMatch = classPattern.find(line)
                        if (classMatch != null) {
                            primaryClassName = classMatch.groups[2]?.value
                            break
                        }

                        val functionMatch = functionPattern.find(line)
                        if (functionMatch != null && primaryClassName == null) {
                            primaryClassName = functionMatch.groups[2]?.value
                        }
                    }
                }

                "python" -> {
                    // Extract class definitions
                    val classPattern = Regex("""^\s*class\s+([A-Z][A-Za-z0-9_]*)\b""")

                    for (line in lines) {
                        val match = classPattern.find(line)
                        if (match != null) {
                            primaryClassName = match.groups[1]?.value
                            break
                        }
                    }
                }

                "csharp" -> {
                    // Extract namespace and class
                    val namespaceLine = lines.find { it.trim().startsWith("namespace ") }
                    packageName = namespaceLine?.trim()?.removePrefix("namespace ")?.trim()

                    val classPattern =
                        Regex("""^\s*(public\s+|private\s+|protected\s+)?(class|interface|struct)\s+([A-Z][A-Za-z0-9_]*)\b""")

                    for (line in lines) {
                        val match = classPattern.find(line)
                        if (match != null) {
                            primaryClassName = match.groups[3]?.value
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract metadata from code content: ${filePath.fileName}" }
        }

        return CodeMetadata(
            packageName = packageName,
            primaryClassName = primaryClassName,
            language = language,
        )
    }

    /**
     * Detect programming language from file path
     */
    private fun detectLanguage(filePath: Path): String =
        when (filePath.toString().substringAfterLast('.').lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "py", "pyx", "pyi" -> "python"
            "rb" -> "ruby"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp", "hxx" -> "c-header"
            "cs" -> "csharp"
            "php" -> "php"
            "swift" -> "swift"
            "scala" -> "scala"
            "clj", "cljs" -> "clojure"
            else -> "unknown"
        }
}
