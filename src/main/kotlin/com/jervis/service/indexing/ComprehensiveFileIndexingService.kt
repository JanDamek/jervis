package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Service for creating comprehensive file descriptions using LLM analysis.
 * Indexes every source file with detailed textual descriptions for better RAG searchability.
 * This meets requirement: "projde přes všechny úrovně path a najde veškeré soubory v kterých je kód a projede nad tím indexaci celého souboru a udelá textový popis přes model"
 */
@Service
class ComprehensiveFileIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

    data class FileIndexingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
    )

    data class FileMetadata(
        val packageName: String?,
        val primaryClassName: String?,
    )

    /**
     * Index all source files with comprehensive LLM-generated descriptions
     */
    suspend fun indexAllSourceFiles(
        project: ProjectDocument,
        projectPath: Path,
    ): FileIndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting comprehensive file indexing for project: ${project.name}" }

                var processedFiles = 0
                var skippedFiles = 0
                var errorFiles = 0

                // Find all source code files
                val sourceFiles =
                    Files
                        .walk(projectPath)
                        .filter { it.isRegularFile() }
                        .filter { isSourceCodeFile(it) }
                        .filter { shouldProcessFile(it, projectPath) }
                        .toList()

                // Process files sequentially to handle suspend functions properly
                for (filePath in sourceFiles) {
                    try {
                        val success = indexSourceFile(project, filePath, projectPath)
                        if (success) {
                            processedFiles++
                        } else {
                            errorFiles++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index file: ${filePath.pathString}" }
                        errorFiles++
                    }
                }

                val result = FileIndexingResult(processedFiles, skippedFiles, errorFiles)
                logger.info {
                    "Comprehensive file indexing completed for project: ${project.name} - " +
                        "Processed: $processedFiles, Skipped: $skippedFiles, Errors: $errorFiles"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during comprehensive file indexing for project: ${project.name}" }
                FileIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single source file with comprehensive description
     */
    private suspend fun indexSourceFile(
        project: ProjectDocument,
        filePath: Path,
        projectPath: Path,
    ): Boolean {
        try {
            logger.debug { "Indexing source file: ${filePath.pathString}" }

            val fileContent = Files.readString(filePath)
            if (fileContent.isBlank()) {
                logger.debug { "Skipping empty file: ${filePath.pathString}" }
                return false
            }

            // Generate comprehensive file description using LLM
            val fileDescription = generateFileDescription(filePath, fileContent, projectPath, project)

            // Create embedding for the file description
            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, fileDescription)

            // Create RAG document for the file
            val relativePath = projectPath.relativize(filePath).pathString
            val fileMetadata = extractFileMetadata(fileContent, detectLanguage(filePath))
            val ragDocument =
                RagDocument(
                    projectId = project.id!!,
                    documentType = RagDocumentType.TEXT,
                    ragSourceType = RagSourceType.FILE,
                    pageContent = fileDescription,
                    source = "file://${project.name}/$relativePath",
                    path = relativePath,
                    packageName = fileMetadata.packageName,
                    className = fileMetadata.primaryClassName,
                    module = extractModuleName(relativePath),
                    language = detectLanguage(filePath),
                )

            // Store in TEXT vector store for semantic search
            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

            logger.debug { "Successfully indexed source file: ${filePath.pathString}" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index source file: ${filePath.pathString}" }
            return false
        }
    }

    /**
     * Generate comprehensive LLM-based description for a source file
     */
    private suspend fun generateFileDescription(
        filePath: Path,
        fileContent: String,
        projectPath: Path,
        project: ProjectDocument,
    ): String {
        promptRepository.getSystemPrompt(PromptTypeEnum.COMPREHENSIVE_FILE_ANALYSIS)

        val userPrompt =
            buildString {
                appendLine("Analyze this source code file and provide a comprehensive description:")
                appendLine()
                appendLine("File: ${filePath.name}")
                appendLine("Path: ${projectPath.relativize(filePath)}")
                appendLine("Language: ${detectLanguage(filePath)}")
                appendLine("Size: ${fileContent.length} characters")
                appendLine()
                appendLine("Source Code:")
                appendLine("```")
                appendLine(fileContent)
                appendLine("```")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMPREHENSIVE_FILE_ANALYSIS,
                userPrompt = userPrompt,
                quick = false,
                "",
            )

        return buildString {
            appendLine("File Analysis: ${filePath.name}")
            appendLine("=".repeat(80))
            appendLine("Path: ${projectPath.relativize(filePath)}")
            appendLine("Language: ${detectLanguage(filePath)}")
            appendLine("Size: ${fileContent.length} characters")
            appendLine()
            appendLine("Description:")
            appendLine(llmResponse)
            appendLine()
            appendLine("Technical Details:")
            appendLine("- File Extension: ${filePath.extension}")
            appendLine("- Content Length: ${fileContent.length} characters")
            appendLine("- Lines of Code: ${fileContent.lines().size}")
            appendLine("- Module: ${extractModuleName(projectPath.relativize(filePath).pathString)}")
            appendLine()
            appendLine("Source Code Preview:")
            val preview =
                if (fileContent.length > 2000) {
                    fileContent.take(2000) + "\n... (content truncated, full analysis above)"
                } else {
                    fileContent
                }
            appendLine("```")
            appendLine(preview)
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine("Generated by: LLM File Analysis")
            appendLine("Analysis Type: Comprehensive File Description")
            appendLine("Project: ${project.name}")
            appendLine("Indexed for: RAG Text Search and Code Discovery")
        }
    }

    /**
     * Check if the file is a source code file that should be indexed
     */
    private fun isSourceCodeFile(filePath: Path): Boolean {
        val extension = filePath.extension.lowercase()
        val supportedExtensions =
            setOf(
                "kt",
                "java",
                "js",
                "ts",
                "py",
                "rb",
                "go",
                "rs",
                "cpp",
                "c",
                "h",
                "hpp",
                "cs",
                "php",
                "swift",
                "scala",
                "clj",
                "ml",
                "hs",
                "elm",
                "dart",
                "vue",
                "jsx",
                "tsx",
                "mjs",
                "cjs",
                "yaml",
                "yml",
                "json",
                "xml",
                "sql",
                "gradle",
                "properties",
                "conf",
                "config",
                "toml",
                "ini",
                "sh",
                "bash",
                "zsh",
                "fish",
            )
        return extension in supportedExtensions
    }

    /**
     * Check if the specific file should be processed (exclude certain paths)
     */
    private fun shouldProcessFile(
        filePath: Path,
        projectPath: Path,
    ): Boolean {
        val relativePath = projectPath.relativize(filePath).pathString.replace('\\', '/')

        // Exclude common build/dependency directories
        val excludePatterns =
            listOf(
                "target/",
                "build/",
                "node_modules/",
                ".git/",
                ".gradle/",
                ".idea/",
                "bin/",
                "out/",
                "dist/",
                "coverage/",
                ".nyc_output/",
                "vendor/",
                ".vscode/",
                ".settings/",
                "logs/",
                "tmp/",
                "temp/",
            )

        return excludePatterns.none { relativePath.contains(it) }
    }

    /**
     * Detect programming language from file extension
     */
    private fun detectLanguage(filePath: Path): String =
        when (filePath.extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            "rb" -> "ruby"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "c-header"
            "cs" -> "csharp"
            "php" -> "php"
            "swift" -> "swift"
            "scala" -> "scala"
            "clj", "cljs" -> "clojure"
            "ml", "mli" -> "ocaml"
            "hs" -> "haskell"
            "elm" -> "elm"
            "dart" -> "dart"
            "vue" -> "vue"
            "jsx" -> "jsx"
            "tsx" -> "tsx"
            "yaml", "yml" -> "yaml"
            "json" -> "json"
            "xml" -> "xml"
            "sql" -> "sql"
            "gradle" -> "gradle"
            "properties" -> "properties"
            "conf", "config" -> "config"
            "toml" -> "toml"
            "ini" -> "ini"
            "sh", "bash" -> "bash"
            "zsh" -> "zsh"
            "fish" -> "fish"
            else -> "text"
        }

    /**
     * Extract module name from file path
     */
    private fun extractModuleName(relativePath: String): String {
        val pathParts = relativePath.replace('\\', '/').split('/')

        // For Java/Kotlin projects, try to extract meaningful module name
        return when {
            pathParts.size > 2 && pathParts[0] == "src" -> {
                // Standard Maven/Gradle structure: src/main/kotlin/com/example/...
                if (pathParts.size > 4 && (pathParts[1] == "main" || pathParts[1] == "test")) {
                    pathParts.drop(3).dropLast(1).joinToString(".")
                } else {
                    pathParts.dropLast(1).joinToString(".")
                }
            }

            pathParts.size > 1 -> pathParts.dropLast(1).joinToString(".")
            else -> "root"
        }
    }

    /**
     * Extract package name and primary class name from file content
     */
    private fun extractFileMetadata(
        fileContent: String,
        language: String,
    ): FileMetadata {
        var packageName: String? = null
        var primaryClassName: String? = null

        try {
            val lines = fileContent.lines()

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
            logger.warn(e) { "Failed to extract metadata from file content" }
        }

        return FileMetadata(
            packageName = packageName,
            primaryClassName = primaryClassName,
        )
    }
}
